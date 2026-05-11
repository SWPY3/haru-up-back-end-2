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

    companion object {
        const val GOAL_BASED_INTEREST_ID = 0L
    }

    /**
     * 목표와 대화 요약을 바탕으로 오늘의 미션을 생성하고 저장합니다.
     * @param goalStartDate 현재 목표가 시작된 날짜 (이전 목표의 미션은 중복 방지에서 제외)
     */
    @Transactional
    fun generateAndSaveMissions(
        memberId: Long,
        goalText: String,
        conversationSummary: String,
        goalStartDate: LocalDate = LocalDate.now()
    ): List<String> {
        val today = LocalDate.now()

        // 오늘 이미 생성된 미션이 있으면 삭제 후 재생성
        memberMissionRepository.deleteByMemberIdAndTargetDateAndMemberInterestId(
            memberId, today, GOAL_BASED_INTEREST_ID
        )

        val missionList = generateMissionsFromClova(memberId, goalText, conversationSummary, goalStartDate)

        val missions = missionList.map { (content, difficulty) ->
            MemberMissionEntity(
                memberId = memberId,
                memberInterestId = GOAL_BASED_INTEREST_ID,
                missionContent = content,
                difficulty = difficulty,
                missionStatus = MissionStatus.READY,
                expEarned = when (difficulty) {
                    1 -> 10  // 하
                    2 -> 20  // 중
                    3 -> 30  // 상
                    else -> 10
                },
                targetDate = today
            )
        }

        memberMissionRepository.saveAll(missions)
        logger.info("미션 생성 완료 - memberId: $memberId, 미션 수: ${missions.size}개")

        return missionList.map { it.first }
    }

    /**
     * Clova AI로 미션 목록을 생성합니다.
     * @return List<Pair<미션내용, 난이도>>
     */
    private fun generateMissionsFromClova(
        memberId: Long,
        goalText: String,
        conversationSummary: String,
        goalStartDate: LocalDate
    ): List<Pair<String, Int>> {
        // 현재 목표 시작일 이후의 미션만 중복 방지에 포함 (이전 목표 미션은 제외)
        val pastMissions = memberMissionRepository
            .findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(
                memberId, GOAL_BASED_INTEREST_ID, goalStartDate
            )
            .map { it.missionContent }
            .distinct()

        val userMessage = DailyMissionFromGoalPrompt.buildUserMessage(goalText, conversationSummary, pastMissions)

        val rawResponse = clovaApiClient.generateText(
            userMessage = userMessage,
            systemMessage = DailyMissionFromGoalPrompt.SYSTEM_PROMPT,
            model = ClovaApiClient.MODEL_HCX_003,
            temperature = 0.7
        ).trim()

        return parseMissions(rawResponse)
    }

    /**
     * Clova 응답 JSON을 파싱하여 미션 목록을 반환합니다.
     * 예: {"missions":[{"content":"미션1","difficulty":1}, ...]}
     * @return List<Pair<미션내용, 난이도>>
     */
    private fun parseMissions(rawResponse: String): List<Pair<String, Int>> {
        return try {
            val jsonNode = objectMapper.readTree(rawResponse)
            val missionsNode = jsonNode.get("missions")
                ?: throw IllegalArgumentException("missions 필드가 없습니다.")

            val missions = missionsNode.mapNotNull { node ->
                val content = node.get("content")?.asText()?.trim()
                val difficulty = node.get("difficulty")?.asInt() ?: 1
                if (!content.isNullOrBlank()) Pair(content, difficulty) else null
            }

            if (missions.isEmpty()) throw IllegalArgumentException("파싱된 미션이 없습니다.")
            missions
        } catch (e: Exception) {
            logger.warn("미션 JSON 파싱 실패, 응답 원문: $rawResponse, 오류: ${e.message}")
            throw IllegalArgumentException("Clova 응답 파싱 실패: ${e.message}", e)
        }
    }
}
