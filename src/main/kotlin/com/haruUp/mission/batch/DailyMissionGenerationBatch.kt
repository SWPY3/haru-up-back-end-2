package com.haruUp.mission.batch

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.global.clova.ClovaApiClient
import com.haruUp.global.clova.DailyMissionFromGoalPrompt
import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MissionStatus
import com.haruUp.mission.infrastructure.MemberMissionRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 매일 자정 목표 기반 미션 자동 생성 배치
 *
 * isActive=true인 모든 MemberGoal을 조회하여
 * 각 사용자별 오늘의 맞춤 미션 3~5개를 생성하고 저장합니다.
 *
 * memberInterestId = 0L 은 관심사 기반이 아닌 "목표 기반 미션"임을 나타내는 sentinel 값입니다.
 */
@Component
class DailyMissionGenerationBatch(
    private val memberGoalRepository: MemberGoalRepository,
    private val memberMissionRepository: MemberMissionRepository,
    private val clovaApiClient: ClovaApiClient,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 목표 기반 미션임을 나타내는 sentinel memberInterestId */
        const val GOAL_BASED_INTEREST_ID = 0L
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    fun generateDailyMissionsFromGoals() {
        val today = LocalDate.now()
        logger.info("목표 기반 일일 미션 생성 시작 - 날짜: $today")

        val activeGoals = memberGoalRepository.findAllByIsActiveTrue()
        logger.info("활성 목표 수: ${activeGoals.size}개")

        var successCount = 0
        var failCount = 0

        for (goal in activeGoals) {
            try {
                val missionContents = generateMissionsFromGoal(
                    goalText = goal.goalText,
                    conversationSummary = goal.conversationSummary
                )

                val missions = missionContents.map { content ->
                    MemberMissionEntity(
                        memberId = goal.memberId,
                        memberInterestId = GOAL_BASED_INTEREST_ID,
                        missionContent = content,
                        missionStatus = MissionStatus.READY,
                        expEarned = 10,
                        targetDate = today
                    )
                }

                memberMissionRepository.saveAll(missions)
                successCount++
                logger.info("미션 생성 완료 - memberId: ${goal.memberId}, 미션 수: ${missions.size}개")
            } catch (e: Exception) {
                failCount++
                logger.error("미션 생성 실패 - memberId: ${goal.memberId}, 오류: ${e.message}", e)
            }
        }

        logger.info("목표 기반 일일 미션 생성 완료 - 성공: ${successCount}명, 실패: ${failCount}명")
    }

    /**
     * Clova AI를 통해 목표와 대화 요약 기반 미션 목록을 생성합니다.
     *
     * @param goalText 사용자 목표 텍스트
     * @param conversationSummary 챗봇 대화 요약
     * @return 미션 내용 목록 (3~5개)
     */
    private fun generateMissionsFromGoal(goalText: String, conversationSummary: String): List<String> {
        val userMessage = DailyMissionFromGoalPrompt.buildUserMessage(goalText, conversationSummary)

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
     *
     * 예: {"missions":["미션1","미션2","미션3"]}
     */
    private fun parseMissions(rawResponse: String): List<String> {
        return try {
            val jsonNode = objectMapper.readTree(rawResponse)
            val missionsNode = jsonNode.get("missions")
                ?: throw IllegalArgumentException("missions 필드가 없습니다.")

            val missions = missionsNode.map { it.asText() }.filter { it.isNotBlank() }
            if (missions.isEmpty()) {
                throw IllegalArgumentException("파싱된 미션이 없습니다.")
            }
            missions
        } catch (e: Exception) {
            logger.warn("미션 JSON 파싱 실패, 응답 원문: $rawResponse, 오류: ${e.message}")
            throw IllegalArgumentException("Clova 응답 파싱 실패: ${e.message}", e)
        }
    }
}
