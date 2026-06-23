package com.haruUp.mission.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.category.repository.JobDetailRepository
import com.haruUp.category.repository.JobJpaRepository
import com.haruUp.goal.domain.MemberGoal
import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.interest.repository.InterestEmbeddingJpaRepository
import com.haruUp.interest.repository.MemberInterestJpaRepository
import com.haruUp.member.infrastructure.MemberProfileRepository
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MissionStatus
import com.haruUp.mission.infrastructure.MemberMissionRepository
import com.haruUp.missionembedding.service.MissionRecommendationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.LocalDate

@ExtendWith(MockitoExtension::class)
class MissionRecommendServiceTest {

    @Mock lateinit var redisTemplate: StringRedisTemplate
    @Mock lateinit var objectMapper: ObjectMapper
    @Mock lateinit var missionRecommendationService: MissionRecommendationService
    @Mock lateinit var memberProfileRepository: MemberProfileRepository
    @Mock lateinit var memberMissionRepository: MemberMissionRepository
    @Mock lateinit var jobRepository: JobJpaRepository
    @Mock lateinit var jobDetailRepository: JobDetailRepository
    @Mock lateinit var memberInterestRepository: MemberInterestJpaRepository
    @Mock lateinit var interestEmbeddingRepository: InterestEmbeddingJpaRepository
    @Mock lateinit var memberGoalRepository: MemberGoalRepository
    @Mock lateinit var goalBasedMissionGenerationService: GoalBasedMissionGenerationService

    @InjectMocks lateinit var service: MissionRecommendService

    @Test
    fun `챗봇 목표 ID만 요청하면 오늘 생성된 READY 미션을 반환한다`() {
        val memberId = 10L
        val today = LocalDate.now()
        val missions = listOf(
            goalMission(id = 101L, memberId = memberId, content = "30분 걷기", difficulty = 1, expEarned = 50),
            goalMission(id = 102L, memberId = memberId, content = "운동 계획 세우기", difficulty = 2, expEarned = 100)
        )
        whenever(
            memberMissionRepository.findTodayMissions(
                memberId = memberId,
                memberInterestId = GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID,
                targetDate = today,
                statuses = listOf(MissionStatus.READY)
            )
        ).thenReturn(missions)

        val result = service.recommendByMemberInterestIds(
            memberId = memberId,
            memberInterestIds = listOf(GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID)
        )

        assertEquals(1, result.missions.size)
        assertEquals(0, result.missions.single().memberInterestId)
        assertEquals(listOf(101L, 102L), result.missions.single().data.map { it.member_mission_id })
        assertEquals(2, result.totalCount)
        verify(memberInterestRepository, never()).findById(any())
    }

    @Test
    fun `챗봇 목표 ID와 실제 관심사 ID를 함께 요청하면 거절한다`() {
        val exception = assertThrows<IllegalArgumentException> {
            service.recommendByMemberInterestIds(
                memberId = 10L,
                memberInterestIds = listOf(GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID, 1L)
            )
        }

        assertEquals("챗봇 목표 ID와 관심사 ID를 함께 요청할 수 없습니다.", exception.message)
        verifyNoInteractions(memberInterestRepository, memberMissionRepository)
    }

    @Test
    fun `챗봇 목표 ID로 재추천하면 활성 목표를 기반으로 미션을 다시 생성한다`() = runBlocking {
        val memberId = 10L
        val activeGoal = MemberGoal(
            id = 1L,
            memberId = memberId,
            goalText = "매일 운동하기",
            conversationSummary = "운동 습관을 만들고 싶어 합니다.",
            conversationRaw = "Q1: 목표\nA1: 매일 운동하기"
        )
        val regenerated = listOf(
            goalMission(id = 201L, memberId = memberId, content = "스쿼트 10회", difficulty = 1, expEarned = 50)
        )
        whenever(memberGoalRepository.findByMemberIdAndIsActiveTrue(memberId)).thenReturn(activeGoal)
        whenever(
            goalBasedMissionGenerationService.generateAndSaveMissions(
                memberId = memberId,
                goalText = activeGoal.goalText,
                conversationSummary = activeGoal.conversationSummary,
                conversationRaw = activeGoal.conversationRaw,
                goalStartDate = LocalDate.now()
            )
        ).thenReturn(regenerated)

        val result = service.retryWithInterest(
            memberId = memberId,
            memberInterestId = GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID
        )

        assertEquals(0, result.missions.single().memberInterestId)
        assertEquals(listOf(201L), result.missions.single().data.map { it.member_mission_id })
        assertEquals(1, result.totalCount)
        verify(memberInterestRepository, never()).findById(any())
    }

    private fun goalMission(
        id: Long,
        memberId: Long,
        content: String,
        difficulty: Int,
        expEarned: Int
    ) = MemberMissionEntity(
        id = id,
        memberId = memberId,
        memberInterestId = GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID,
        missionContent = content,
        difficulty = difficulty,
        missionStatus = MissionStatus.READY,
        expEarned = expEarned,
        targetDate = LocalDate.now()
    )
}
