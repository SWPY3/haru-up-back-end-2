package com.haruUp.mission.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.global.clova.ClovaApiClient
import com.haruUp.global.clova.DailyMissionFromGoalPrompt
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
    private val clovaApiClient: ClovaApiClient,
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

        // 오늘 이미 생성된 미션이 있으면 삭제 후 재생성 (이미 선택된 미션은 보존하기 위해 READY 상태만 삭제)
        memberMissionRepository.deleteByMemberIdAndTargetDateAndMemberInterestIdAndMissionStatus(
            memberId, today, GOAL_BASED_INTEREST_ID, MissionStatus.READY
        )

        val conversationContext = conversationRaw ?: conversationSummary
        val missionList = generateMissionsFromClova(memberId, goalText, conversationContext, goalStartDate)

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
     * Clova AI로 미션 목록을 생성합니다. 난이도 분포가 올바르지 않으면 최대 3회 재시도합니다.
     * @param conversationContext 대화 내용 (원본 또는 요약)
     * @return List<ParsedMission> (하3 + 중3 + 상3 = 9개 보장)
     */
    private fun generateMissionsFromClova(
        memberId: Long,
        goalText: String,
        conversationContext: String,
        goalStartDate: LocalDate
    ): List<ParsedMission> {
        // 현재 목표 시작일 이후의 미션만 중복 방지에 포함 (이전 목표 미션은 제외)
        val pastMissions = memberMissionRepository
            .findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(
                memberId, GOAL_BASED_INTEREST_ID, goalStartDate
            )
            .map { it.missionContent }
            .distinct()

        // 목표 시작일로부터 오늘이 몇 일차인지 계산 (최소 1일차)
        val dayNumber = ChronoUnit.DAYS.between(goalStartDate, LocalDate.now()).toInt().coerceAtLeast(0) + 1

        val userMessage = DailyMissionFromGoalPrompt.buildUserMessage(goalText, conversationContext, pastMissions, dayNumber)

        var lastException: Exception? = null
        repeat(MAX_MISSION_RETRY) { attempt ->
            try {
                val rawResponse = clovaApiClient.generateText(
                    userMessage = userMessage,
                    systemMessage = DailyMissionFromGoalPrompt.SYSTEM_PROMPT,
                    model = ClovaApiClient.MODEL_HCX_007,
                    temperature = 0.5
                ).trim()

                val missions = parseMissions(rawResponse)

                if (validateDifficultyDistribution(missions)) {
                    if (attempt > 0) {
                        logger.info("미션 난이도 분포 정상화 완료 (${attempt + 1}번째 시도) - memberId: $memberId")
                    }
                    return missions
                }

                val grouped = missions.groupBy { it.difficulty }
                logger.warn(
                    "미션 난이도 분포 불일치 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) " +
                    "- 하:${grouped[1]?.size ?: 0}개, 중:${grouped[2]?.size ?: 0}개, 상:${grouped[3]?.size ?: 0}개 " +
                    "- memberId: $memberId"
                )
            } catch (e: Exception) {
                lastException = e
                logger.warn("미션 생성 실패 (시도 ${attempt + 1}/$MAX_MISSION_RETRY) - memberId: $memberId, 오류: ${e.message}")
            }
        }

        throw lastException ?: IllegalStateException(
            "${MAX_MISSION_RETRY}회 시도 후에도 올바른 난이도 분포(하3+중3+상3)의 미션 생성 실패 - memberId: $memberId"
        )
    }

    /**
     * 미션 난이도 분포가 하3, 중3, 상3인지 검증합니다.
     */
    private fun validateDifficultyDistribution(missions: List<ParsedMission>): Boolean {
        val grouped = missions.groupBy { it.difficulty }
        return grouped[1]?.size == 3 && grouped[2]?.size == 3 && grouped[3]?.size == 3
    }

    /**
     * Clova 응답 JSON을 파싱하여 미션 목록을 반환합니다.
     * 예: {"missions":[{"content":"미션1","description":"실행방법","difficulty":1}, ...]}
     */
    private fun parseMissions(rawResponse: String): List<ParsedMission> {
        return try {
            // Clova가 마크다운 코드블록(```json ... ```)으로 감싸서 반환하는 경우 제거
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
            throw IllegalArgumentException("Clova 응답 파싱 실패: ${e.message}", e)
        }
    }

    /** Clova 파싱 결과를 담는 내부 데이터 클래스 */
    private data class ParsedMission(val content: String, val description: String, val difficulty: Int)

    companion object {
        const val GOAL_BASED_INTEREST_ID = 0L
        private const val MAX_MISSION_RETRY = 3
    }
}
