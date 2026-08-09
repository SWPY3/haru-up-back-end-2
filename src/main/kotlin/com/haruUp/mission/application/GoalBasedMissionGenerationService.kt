package com.haruUp.mission.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.DailyMissionFromGoalPrompt
import com.haruUp.global.util.KoreanGrammarChecker
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MissionStatus
import com.haruUp.mission.infrastructure.MemberMissionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 목표 기반 미션 생성 공통 서비스
 * - 챗봇 완료 시 즉시 미션 생성
 * - 매일 자정 배치에서 미션 재생성
 */
@Service
class GoalBasedMissionGenerationService(
    private val memberMissionRepository: MemberMissionRepository,
    private val openAiApiClient: OpenAiApiClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 목표와 대화 내용을 바탕으로 오늘의 미션을 생성하고 저장합니다.
     * @param conversationRaw 원본 Q&A 대화 텍스트 (있으면 우선 사용)
     * @param conversationSummary AI 요약 대화 (conversationRaw가 없을 때 fallback)
     * @param goalStartDate 현재 목표가 시작된 날짜 (이전 목표의 미션은 중복 방지에서 제외)
     */
    @Transactional
    fun generateAndSaveMissions(
        memberId: Long,
        goalText: String,
        conversationSummary: String = "",
        conversationRaw: String? = null,
        goalStartDate: LocalDate = LocalDate.now()
    ): List<MemberMissionEntity> {
        val today = LocalDate.now()

        // 중복 방지 목록은 반드시 삭제 "전"에 조회한다.
        // 재추천(retryWithGoal)은 오늘의 READY 미션을 지우고 다시 생성하는데,
        // 삭제 후에 조회하면 방금 추천했던 미션이 목록에서 빠져 같은 미션이 다시 나온다.
        val pastMissionContents = findPastMissionContents(memberId, goalStartDate)

        // 오늘 이미 생성된 미션이 있으면 삭제 후 재생성 (이미 선택된 미션은 보존하기 위해 READY 상태만 삭제)
        memberMissionRepository.deleteByMemberIdAndTargetDateAndMemberInterestIdAndMissionStatus(
            memberId, today, GOAL_BASED_INTEREST_ID, MissionStatus.READY
        )

        val conversationContext = conversationRaw ?: conversationSummary
        val missionList = generateMissionsFromOpenAi(
            memberId, goalText, conversationContext, goalStartDate, pastMissionContents
        )

        val missions = missionList.map { parsed ->
            MemberMissionEntity(
                memberId = memberId,
                memberInterestId = GOAL_BASED_INTEREST_ID,
                missionContent = parsed.content,
                missionDescription = parsed.description,
                difficulty = parsed.difficulty,
                missionStatus = MissionStatus.READY,
                expEarned = when (parsed.difficulty) {
                    1 -> 50   // 하
                    2 -> 100  // 중
                    3 -> 200  // 상
                    else -> 50
                },
                targetDate = today
            )
        }

        val saved = memberMissionRepository.saveAll(missions)
        logger.info("미션 생성 완료 - memberId: $memberId, 미션 수: ${saved.size}개")

        return saved
    }

    /**
     * 현재 목표 시작일 이후에 이미 제공한 미션 제목을 조회합니다. (이전 목표 미션은 제외)
     * 반드시 오늘의 READY 미션을 삭제하기 전에 호출해야 재추천 시 중복을 막을 수 있습니다.
     */
    private fun findPastMissionContents(memberId: Long, goalStartDate: LocalDate): List<String> {
        return memberMissionRepository
            .findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(
                memberId, GOAL_BASED_INTEREST_ID, goalStartDate
            )
            .map { it.missionContent }
            .distinct()
    }

    /**
     * OpenAI로 미션 목록을 생성합니다. 난이도 분포가 올바르지 않으면 최대 3회 재시도합니다.
     * @param conversationContext 대화 내용 (원본 또는 요약)
     * @param pastMissions 이미 제공한 미션 제목 (중복 방지용, 삭제 전에 조회한 값)
     * @return List<ParsedMission> (하5 + 중5 + 상5 = 15개 보장)
     */
    private fun generateMissionsFromOpenAi(
        memberId: Long,
        goalText: String,
        conversationContext: String,
        goalStartDate: LocalDate,
        pastMissions: List<String>
    ): List<ParsedMission> {
        val pastMissionSet = pastMissions.toSet()

        // 목표 시작일로부터 오늘이 몇 일차인지 계산 (최소 1일차)
        val dayNumber = ChronoUnit.DAYS.between(goalStartDate, LocalDate.now()).toInt().coerceAtLeast(0) + 1

        val userMessage = DailyMissionFromGoalPrompt.buildUserMessage(goalText, conversationContext, pastMissions, dayNumber)
        logger.info("미션 생성 프롬프트 - memberId: $memberId, D+$dayNumber, userMessage:\n$userMessage")

        var lastException: Exception? = null
        // 난이도 분포는 맞지만 description 글자수만 미달인 결과 (전부 실패 시 fallback으로 사용)
        var fallbackMissions: List<ParsedMission>? = null
        repeat(MAX_MISSION_RETRY) { attempt ->
            try {
                val rawResponse = openAiApiClient.generateText(
                    userMessage = userMessage,
                    systemMessage = DailyMissionFromGoalPrompt.SYSTEM_PROMPT,
                    model = OpenAiApiClient.MODEL_DEFAULT,
                    temperature = 0.5,
                    maxTokens = MISSION_GENERATION_MAX_TOKENS
                ).trim()

                val missions = parseMissions(rawResponse)

                if (validateDifficultyDistribution(missions)) {
                    val shortDescriptions = missions.filter { it.description.length < MIN_DESCRIPTION_LENGTH }
                    val longDescriptions = missions.filter { it.description.length > MAX_DESCRIPTION_LENGTH }
                    val grammarViolations = findGrammarViolations(missions)
                    // 프롬프트로 "반복 금지"를 지시해도 모델이 같은 미션을 다시 내는 경우가 있어 코드로도 막는다
                    val duplicated = missions.filter { it.content in pastMissionSet }

                    if (shortDescriptions.isEmpty() && longDescriptions.isEmpty() &&
                        grammarViolations.isEmpty() && duplicated.isEmpty()
                    ) {
                        if (attempt > 0) {
                            logger.info("미션 검증 통과 (${attempt + 1}번째 시도) - memberId: $memberId")
                        }
                        logGeneratedMissions(memberId, missions)
                        return missions
                    }

                    // 전부 실패하면 가장 덜 어긋난 결과를 쓰기 위해 위반 수가 적은 쪽을 남긴다
                    if (fallbackMissions == null ||
                        countViolations(missions, pastMissionSet) < countViolations(fallbackMissions!!, pastMissionSet)
                    ) {
                        fallbackMissions = missions
                    }
                    if (duplicated.isNotEmpty()) {
                        logger.warn(
                            "이미 제공한 미션과 중복 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) " +
                            "- ${duplicated.size}개: " + duplicated.joinToString { "\"${it.content}\"" } +
                            " - memberId: $memberId"
                        )
                    }
                    if (shortDescriptions.isNotEmpty()) {
                        logger.warn(
                            "미션 description 글자수 미달 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) " +
                            "- ${MIN_DESCRIPTION_LENGTH}자 미만 ${shortDescriptions.size}개: " +
                            shortDescriptions.joinToString { "\"${it.description}\"(${it.description.length}자)" } +
                            " - memberId: $memberId"
                        )
                    }
                    if (longDescriptions.isNotEmpty()) {
                        logger.warn(
                            "미션 description 글자수 초과 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) " +
                            "- ${MAX_DESCRIPTION_LENGTH}자 초과 ${longDescriptions.size}개: " +
                            longDescriptions.joinToString { "\"${it.description}\"(${it.description.length}자)" } +
                            " - memberId: $memberId"
                        )
                    }
                    if (grammarViolations.isNotEmpty()) {
                        logger.warn(
                            "미션 한국어 문법 오류 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) " +
                            "- ${grammarViolations.size}개: " + grammarViolations.joinToString("; ") +
                            " - memberId: $memberId"
                        )
                    }
                } else {
                    val grouped = missions.groupBy { it.difficulty }
                    logger.warn(
                        "미션 난이도 분포 불일치 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) " +
                        "- 기대: 난이도별 ${MISSIONS_PER_DIFFICULTY}개, 실제 " +
                        "하:${grouped[1]?.size ?: 0}개, 중:${grouped[2]?.size ?: 0}개, 상:${grouped[3]?.size ?: 0}개 " +
                        "- memberId: $memberId"
                    )
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn("미션 생성 실패 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) - memberId: $memberId, 오류: ${e.message}")
            }
        }

        // 난이도 분포까지 실패하면 예외, 글자수/문법/중복만 어긋나면 가장 덜 어긋난 결과라도 사용
        fallbackMissions?.let { missions ->
            logger.warn(
                "${MAX_MISSION_RETRY}회 시도 후에도 글자수·문법·중복 검증을 통과하지 못해 " +
                "가장 위반이 적은 결과를 사용합니다 (위반 ${countViolations(missions, pastMissionSet)}건) - memberId: $memberId"
            )
            logGeneratedMissions(memberId, missions)
            return missions
        }

        throw lastException ?: IllegalStateException(
            "${MAX_MISSION_RETRY}회 시도 후에도 올바른 난이도 분포(난이도별 ${MISSIONS_PER_DIFFICULTY}개)의 미션 생성 실패 - memberId: $memberId"
        )
    }

    /**
     * 생성된 미션 전체 목록을 로그로 남깁니다. (프롬프트 결과 추적용)
     */
    private fun logGeneratedMissions(memberId: Long, missions: List<ParsedMission>) {
        val formatted = missions.joinToString("\n") {
            "[난이도 ${it.difficulty}] ${it.content} | ${it.description} (${it.description.length}자)"
        }
        logger.info("생성된 미션 목록 - memberId: $memberId, ${missions.size}개\n$formatted")
    }

    /**
     * description 글자수 기준을 벗어나거나 이미 제공한 미션과 중복되는 건수를 셉니다.
     * 재시도가 모두 실패했을 때 가장 덜 어긋난 결과를 고르는 데 사용합니다.
     */
    private fun countViolations(missions: List<ParsedMission>, pastMissionSet: Set<String>): Int {
        return missions.count {
            it.description.length !in MIN_DESCRIPTION_LENGTH..MAX_DESCRIPTION_LENGTH ||
                it.content in pastMissionSet
        }
    }

    /**
     * 생성된 미션의 제목/설명에서 명백한 한국어 문법 오류를 찾아 사유 목록으로 반환합니다.
     * 사용자에게 그대로 노출되는 문장이므로 오류가 있으면 재생성합니다.
     */
    private fun findGrammarViolations(missions: List<ParsedMission>): List<String> {
        return missions.flatMap { mission ->
            listOfNotNull(
                KoreanGrammarChecker.findViolation(mission.content)
                    ?.let { "content \"${mission.content}\" - $it" },
                KoreanGrammarChecker.findViolation(mission.description)
                    ?.let { "description \"${mission.description}\" - $it" }
            )
        }
    }

    /**
     * 미션 난이도 분포가 하/중/상 각각 [MISSIONS_PER_DIFFICULTY]개인지 검증합니다.
     */
    private fun validateDifficultyDistribution(missions: List<ParsedMission>): Boolean {
        val grouped = missions.groupBy { it.difficulty }
        return grouped[1]?.size == MISSIONS_PER_DIFFICULTY &&
            grouped[2]?.size == MISSIONS_PER_DIFFICULTY &&
            grouped[3]?.size == MISSIONS_PER_DIFFICULTY
    }

    /**
     * OpenAI 응답 JSON을 파싱하여 미션 목록을 반환합니다.
     * 예: {"missions":[{"content":"미션1","description":"실행방법","difficulty":1}, ...]}
     */
    private fun parseMissions(rawResponse: String): List<ParsedMission> {
        return try {
            // 모델이 마크다운 코드블록(```json ... ```)으로 감싸서 반환하는 경우 제거
            val cleaned = rawResponse
                .replace(Regex("^```[a-zA-Z]*\\s*"), "")
                .replace(Regex("```\\s*$"), "")
                .trim()

            val jsonNode = objectMapper.readTree(cleaned)
            val missionsNode = jsonNode.get("missions")
                ?: throw IllegalArgumentException("missions 필드가 없습니다.")

            val missions = missionsNode.mapNotNull { node ->
                val content = node.get("content")?.asText()?.trim()
                val description = node.get("description")?.asText()?.trim()
                val difficulty = node.get("difficulty")?.asInt() ?: 1
                if (!content.isNullOrBlank()) ParsedMission(content, description ?: "", difficulty) else null
            }

            if (missions.isEmpty()) throw IllegalArgumentException("파싱된 미션이 없습니다.")
            missions
        } catch (e: Exception) {
            logger.warn("미션 JSON 파싱 실패, 응답 원문: $rawResponse, 오류: ${e.message}")
            throw IllegalArgumentException("OpenAI 응답 파싱 실패: ${e.message}", e)
        }
    }

    /** OpenAI 파싱 결과를 담는 내부 데이터 클래스 */
    private data class ParsedMission(val content: String, val description: String, val difficulty: Int)

    companion object {
        const val GOAL_BASED_INTEREST_ID = 0L

        /** 난이도(하/중/상)별로 생성할 미션 수. 프롬프트(DailyMissionFromGoalPrompt)의 개수와 반드시 일치해야 한다. */
        const val MISSIONS_PER_DIFFICULTY = 5

        /** description 최소 글자수. 프롬프트(DailyMissionFromGoalPrompt)의 기준과 반드시 일치해야 한다. */
        private const val MIN_DESCRIPTION_LENGTH = 20

        /**
         * description 최대 글자수. 프롬프트(DailyMissionFromGoalPrompt)의 기준과 반드시 일치해야 한다.
         * UI 표시 폭이 넓어지면 이 값과 프롬프트의 기준을 함께 올리면 된다.
         */
        private const val MAX_DESCRIPTION_LENGTH = 30

        private const val MAX_MISSION_RETRY = 3

        /** 난이도별 5개(총 15개) + 설명까지 잘리지 않고 응답받기 위한 출력 토큰 상한 */
        private const val MISSION_GENERATION_MAX_TOKENS = 4096
    }
}
