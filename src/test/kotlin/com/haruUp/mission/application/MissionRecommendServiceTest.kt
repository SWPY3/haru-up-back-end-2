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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
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
    fun `오늘 목표 미션이 한 번도 생성되지 않았으면 첫 조회 때 생성해서 반환한다`() {
        val memberId = 10L
        val today = LocalDate.now()
        val activeGoal = activeGoal(memberId)
        val generated = listOf(
            goalMission(id = 301L, memberId = memberId, content = "스트레칭 5분", difficulty = 1, expEarned = 50)
        )
        stubNoReadyGoalMissions(memberId, today)
        whenever(
            memberMissionRepository.findByMemberIdAndMemberInterestIdAndTargetDate(
                memberId, GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID, today
            )
        ).thenReturn(emptyList())
        whenever(memberGoalRepository.findByMemberIdAndIsActiveTrue(memberId)).thenReturn(activeGoal)
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.setIfAbsent(any(), any(), any<java.time.Duration>())).thenReturn(true)
        whenever(
            goalBasedMissionGenerationService.generateAndSaveMissions(
                memberId = memberId,
                goalText = activeGoal.goalText,
                conversationSummary = activeGoal.conversationSummary,
                conversationRaw = activeGoal.conversationRaw,
                goalStartDate = today
            )
        ).thenReturn(generated)

        val result = service.recommendByMemberInterestIds(
            memberId = memberId,
            memberInterestIds = listOf(GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID)
        )

        assertEquals(listOf(301L), result.missions.single().data.map { it.member_mission_id })
        assertEquals(1, result.totalCount)
        verify(valueOps, never()).increment(any())
        verify(redisTemplate).delete(eq(MissionRecommendRedisKey.goalMissionGenerationLock(memberId, today)))
    }

    @Test
    fun `오늘 이미 생성된 목표 미션이 있으면 READY 가 없어도 다시 생성하지 않는다`() {
        val memberId = 10L
        val today = LocalDate.now()
        stubNoReadyGoalMissions(memberId, today)
        whenever(
            memberMissionRepository.findByMemberIdAndMemberInterestIdAndTargetDate(
                memberId, GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID, today
            )
        ).thenReturn(
            listOf(goalMission(id = 401L, memberId = memberId, content = "물 2L 마시기", difficulty = 1, expEarned = 50, status = MissionStatus.ACTIVE))
        )

        val result = service.recommendByMemberInterestIds(
            memberId = memberId,
            memberInterestIds = listOf(GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID)
        )

        assertEquals(0, result.totalCount)
        verifyNoInteractions(goalBasedMissionGenerationService, memberGoalRepository)
    }

    @Test
    fun `활성 목표가 없으면 생성하지 않고 빈 목록을 반환한다`() {
        val memberId = 10L
        val today = LocalDate.now()
        stubNoReadyGoalMissions(memberId, today)
        whenever(
            memberMissionRepository.findByMemberIdAndMemberInterestIdAndTargetDate(
                memberId, GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID, today
            )
        ).thenReturn(emptyList())
        whenever(memberGoalRepository.findByMemberIdAndIsActiveTrue(memberId)).thenReturn(null)

        val result = service.recommendByMemberInterestIds(
            memberId = memberId,
            memberInterestIds = listOf(GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID)
        )

        assertEquals(0, result.totalCount)
        verifyNoInteractions(goalBasedMissionGenerationService)
    }

    @Test
    fun `다른 요청이 생성 중이면 중복 생성하지 않는다`() {
        val memberId = 10L
        val today = LocalDate.now()
        stubNoReadyGoalMissions(memberId, today)
        whenever(
            memberMissionRepository.findByMemberIdAndMemberInterestIdAndTargetDate(
                memberId, GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID, today
            )
        ).thenReturn(emptyList())
        whenever(memberGoalRepository.findByMemberIdAndIsActiveTrue(memberId)).thenReturn(activeGoal(memberId))
        val valueOps = mock<ValueOperations<String, String>>()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        whenever(valueOps.setIfAbsent(any(), any(), any<java.time.Duration>())).thenReturn(false)

        val result = service.recommendByMemberInterestIds(
            memberId = memberId,
            memberInterestIds = listOf(GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID)
        )

        assertEquals(0, result.totalCount)
        verifyNoInteractions(goalBasedMissionGenerationService)
        verify(redisTemplate, never()).delete(anyOrNull<String>())
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

    private fun stubNoReadyGoalMissions(memberId: Long, today: LocalDate) {
        whenever(
            memberMissionRepository.findTodayMissions(
                memberId = memberId,
                memberInterestId = GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID,
                targetDate = today,
                statuses = listOf(MissionStatus.READY)
            )
        ).thenReturn(emptyList())
    }

    private fun activeGoal(memberId: Long) = MemberGoal(
        id = 1L,
        memberId = memberId,
        goalText = "매일 운동하기",
        conversationSummary = "운동 습관을 만들고 싶어 합니다.",
        conversationRaw = "Q1: 목표\nA1: 매일 운동하기"
    )

    private fun goalMission(
        id: Long,
        memberId: Long,
        content: String,
        difficulty: Int,
        expEarned: Int,
        status: MissionStatus = MissionStatus.READY
    ) = MemberMissionEntity(
        id = id,
        memberId = memberId,
        memberInterestId = GoalBasedMissionGenerationService.GOAL_BASED_INTEREST_ID,
        missionContent = content,
        difficulty = difficulty,
        missionStatus = status,
        expEarned = expEarned,
        targetDate = LocalDate.now()
    )
}
