package com.haruUp.missionembedding.service

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.haruUp.interest.dto.InterestPath
import com.haruUp.mission.domain.MissionExpCalculator
import com.haruUp.missionembedding.dto.MissionDto
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ImprovedMissionRecommendationPrompt
import com.haruUp.global.prompt.MissionMemberProfile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 미션 추천 서비스
 *
 * LLM 기반 미션 추천
 * 1. OpenAI API로 미션 생성
 * 2. 난이도 검증 (중복/누락 체크)
 * 3. DTO 반환 (DB 저장은 호출자에서 처리)
 */
@Service
class MissionRecommendationService(
    private val openAiApiClient: OpenAiApiClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        private const val MAX_RETRIES = 3
    }

    /**
     * 오늘의 미션 추천
     *
     * @param directFullPath 관심사 경로 (예: ["체력관리 및 운동", "헬스", "근력 키우기"])
     * @param memberProfile 멤버 프로필
     * @param difficulties 추천할 난이도 목록 (기본: 1~5)
     * @param excludeContents 제외할 미션 내용 목록
     * @return 미션 목록
     */
    suspend fun recommendTodayMissions(
        directFullPath: List<String>,
        memberProfile: MissionMemberProfile,
        difficulties: List<Int>? = null,
        excludeContents: List<String> = emptyList()
    ): List<MissionDto> {
        val targetDifficulties = difficulties ?: listOf(1, 2, 3, 4, 5)
        val pathString = directFullPath.joinToString(" > ")
        logger.info("미션 추천 시작 - 관심사: $pathString, 난이도: $targetDifficulties")

        return try {
            // 1. LLM으로 미션 생성 (재시도 포함)
            val missions = generateMissionsWithRetry(
                directFullPath = directFullPath,
                memberProfile = memberProfile,
                difficulties = targetDifficulties,
                excludeContents = excludeContents
            )

            // 2. DTO 변환 (DB 저장은 호출자에서 처리)
            missions.map { mission ->
                MissionDto(
                    member_mission_id = null,
                    content = mission.content,
                    directFullPath = mission.directFullPath,
                    difficulty = mission.difficulty,
                    expEarned = MissionExpCalculator.calculateByDifficulty(mission.difficulty),
                    createdType = "AI"
                )
            }.also {
                logger.info("미션 추천 완료: ${it.size}개")
            }
        } catch (e: Exception) {
            logger.error("미션 추천 실패: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * LLM으로 미션 생성 (재시도 로직 포함)
     */
    private fun generateMissionsWithRetry(
        directFullPath: List<String>,
        memberProfile: MissionMemberProfile,
        difficulties: List<Int>,
        excludeContents: List<String>
    ): List<Mission> {
        val interestPath = InterestPath(
            mainCategory = directFullPath.getOrNull(0) ?: "",
            middleCategory = directFullPath.getOrNull(1),
            subCategory = directFullPath.getOrNull(2)
        )

        repeat(MAX_RETRIES) { attempt ->
            try {
                val missions = callLLMForMissions(
                    interestPath = interestPath,
                    memberProfile = memberProfile,
                    difficulties = difficulties,
                    excludeContents = excludeContents,
                    attempt = attempt + 1
                )

                // 난이도 검증
                val returnedDifficulties = missions.mapNotNull { it.difficulty }
                if (isValidDifficulties(returnedDifficulties, difficulties)) {
                    logger.info("미션 생성 성공 (시도 ${attempt + 1}): 난이도 $returnedDifficulties")
                    return missions.map { it.copy(directFullPath = directFullPath) }
                }

                val duplicates = returnedDifficulties.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                val missing = difficulties - returnedDifficulties.toSet()
                logger.warn("난이도 검증 실패 (시도 ${attempt + 1}): 중복=$duplicates, 누락=$missing")

            } catch (e: Exception) {
                logger.warn("미션 생성 실패 (시도 ${attempt + 1}): ${e.message}")
            }
        }

        throw IllegalStateException("$MAX_RETRIES 회 시도 후에도 미션 생성 실패")
    }

    private fun isValidDifficulties(returned: List<Int>, expected: List<Int>): Boolean {
        return returned.toSet() == expected.toSet() && returned.size == expected.size
    }

    /**
     * LLM API 호출
     */
    private fun callLLMForMissions(
        interestPath: InterestPath,
        memberProfile: MissionMemberProfile,
        difficulties: List<Int>,
        excludeContents: List<String>,
        attempt: Int
    ): List<Mission> {
        val userMessage = buildPrompt(interestPath, memberProfile, difficulties, excludeContents, attempt)

        logger.debug("LLM 요청 (시도 $attempt)")

        val response = openAiApiClient.generateText(
            userMessage = userMessage,
            systemMessage = ImprovedMissionRecommendationPrompt.SYSTEM_PROMPT,
            temperature = 0.3  // 안정적인 출력을 위해 낮은 temperature 사용
        )

        return parseMissions(response, difficulties)
    }

    /**
     * 프롬프트 생성
     */
    private fun buildPrompt(
        interestPath: InterestPath,
        memberProfile: MissionMemberProfile,
        difficulties: List<Int>,
        excludeContents: List<String>,
        attempt: Int
    ): String {
        val basePrompt = ImprovedMissionRecommendationPrompt.createUserMessageForAllInterests(
            interests = listOf(interestPath),
            missionMemberProfile = memberProfile
        )

        val excludeSection = buildExcludeSection(excludeContents)
        val retryWarning = if (attempt > 1) "\n⚠️ 이전 시도에서 난이도가 중복되었습니다. 각 난이도가 정확히 1번씩만 나오도록 하세요!\n" else ""
        val difficultyList = difficulties.joinToString(", ")
        val jsonExample = difficulties.joinToString(",\n    ") {
            """{"content": "미션내용", "relatedInterest": ["대분류", "중분류", "소분류"], "difficulty": $it}"""
        }

        return """
$basePrompt

===== 생성 요청 =====
난이도 $difficultyList 각각 1개씩, 총 ${difficulties.size}개의 미션을 생성하세요.
$retryWarning$excludeSection
===== 난이도 기준 (하루 안에 완료 가능해야 함) =====
- 난이도 1 (초등학생): 5-10분, 아주 간단한 활동
- 난이도 2 (중학생): 15-30분, 기본적인 노력 필요
- 난이도 3 (고등학생): 30분-1시간, 집중력과 계획 필요
- 난이도 4 (대학생): 1-2시간, 전문 지식/기술 필요
- 난이도 5 (직장인): 2-3시간, 높은 전문성 필요

===== 좋은 미션 (필수) =====
✅ 구체적이고 측정 가능 (횟수, 시간, 개수 등 수치 포함)
✅ 하루 안에 완료 가능
예: "영어 단어 20개 암기", "스쿼트 3세트×15회", "책 50페이지 읽기"

===== 나쁜 미션 (금지) =====
❌ 모호함: "운동하기", "공부하기"
❌ 장기 목표: "한 달간 다이어트"
❌ 일회성: "헬스장 등록하기"
❌ 측정 불가: "건강해지기"

===== 응답 형식 (JSON만 출력) =====
```json
{
  "missions": [
    $jsonExample
  ]
}
```
        """.trim()
    }

    /**
     * 제외 미션 섹션 생성
     */
    private fun buildExcludeSection(excludeContents: List<String>): String {
        if (excludeContents.isEmpty()) return ""

        val missionList = excludeContents.joinToString("\n") { "- $it" }

        return """

###############################################
# 중요: 아래 미션들은 절대 추천하지 마세요 #
###############################################

<EXCLUDED_MISSIONS>
$missionList
</EXCLUDED_MISSIONS>

위 목록과 동일하거나 유사한 미션은 제외하세요.
"""
    }

    /**
     * LLM 응답 파싱
     */
    private fun parseMissions(response: String, difficulties: List<Int>): List<Mission> {
        return try {
            val jsonContent = extractJson(response)
            val parsed = objectMapper.readValue<MissionResponse>(jsonContent)

            // 요청한 난이도만 필터링하고 난이도별 1개씩만 선택
            parsed.missions
                .filter { it.difficulty in difficulties }
                .groupBy { it.difficulty }
                .mapValues { (_, missions) -> missions.first() }
                .values.toList()

        } catch (e: Exception) {
            logger.error("응답 파싱 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * JSON 추출 (견고한 파싱)
     */
    private fun extractJson(response: String): String {
        val trimmed = response.trim()

        // 1. 마크다운 코드블록 제거: ```json ... ``` 또는 ``` ... ```
        val withoutCodeBlock = trimmed
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*"), "")
            .replace(Regex("\\s*```"), "")
            .trim()

        // 2. JSON 객체 추출: { ... } 패턴 찾기
        val jsonRegex = Regex("\\{[\\s\\S]*\"missions\"[\\s\\S]*\\}")
        val jsonMatch = jsonRegex.find(withoutCodeBlock)
        if (jsonMatch != null) {
            return jsonMatch.value
        }

        // 3. 그래도 못 찾으면 원본 반환
        return withoutCodeBlock
    }

    // DTO 클래스
    private data class MissionResponse(val missions: List<Mission>)

    private data class Mission(
        val content: String,
        @JsonAlias("relatedInterest")
        val directFullPath: List<String> = emptyList(),
        val difficulty: Int? = null
    )
}
