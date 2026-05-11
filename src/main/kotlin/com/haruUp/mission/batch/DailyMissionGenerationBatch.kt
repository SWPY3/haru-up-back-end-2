package com.haruUp.mission.batch

import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.mission.application.GoalBasedMissionGenerationService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 매일 자정 목표 기반 미션 자동 생성 배치
 */
@Component
class DailyMissionGenerationBatch(
    private val memberGoalRepository: MemberGoalRepository,
    private val goalBasedMissionGenerationService: GoalBasedMissionGenerationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 * * *")
    fun generateDailyMissionsFromGoals() {
        val today = LocalDate.now()
        logger.info("목표 기반 일일 미션 생성 시작 - 날짜: $today")

        val activeGoals = memberGoalRepository.findAllByIsActiveTrue()
        logger.info("활성 목표 수: ${activeGoals.size}개")

        var successCount = 0
        var failCount = 0

        for (goal in activeGoals) {
            try {
                goalBasedMissionGenerationService.generateAndSaveMissions(
                    memberId = goal.memberId,
                    goalText = goal.goalText,
                    conversationSummary = goal.conversationSummary,
                    goalStartDate = goal.createdAt?.toLocalDate() ?: today
                )
                successCount++
            } catch (e: Exception) {
                failCount++
                logger.error("미션 생성 실패 - memberId: ${goal.memberId}, 오류: ${e.message}", e)
            }
        }

        logger.info("목표 기반 일일 미션 생성 완료 - 성공: ${successCount}명, 실패: ${failCount}명")
    }
}
