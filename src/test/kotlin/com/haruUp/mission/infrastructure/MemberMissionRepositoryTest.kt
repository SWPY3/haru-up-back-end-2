package com.haruUp.mission.infrastructure

import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MissionStatus
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalDateTime

// DB·Redis 등 외부 인프라가 필요하다. CI의 unitTest 태스크에서는 제외된다.
@Tag("integration")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
/**
 * MemberMissionRepository의 QueryDSL 커스텀 쿼리(집계/soft delete)가
 * 비즈니스 규칙에 맞게 동작하는지 검증한다.
 */
class MemberMissionRepositoryTest(
    @Autowired private val memberMissionRepository: MemberMissionRepository,
    @Autowired private val entityManager: EntityManager
) {

    @BeforeEach
    fun setUp() {
        entityManager
            .createNativeQuery("TRUNCATE TABLE member_mission RESTART IDENTITY CASCADE")
            .executeUpdate()
    }

    // 목적: 완료 미션 집계 쿼리가 날짜별 카운트와 정렬을 정확히 반환하는지 확인
    @Test
    fun `한 달 동안 날짜별 완료 미션 개수를 조회한다`() {
        // given
        val memberId = 99901L

        memberMissionRepository.saveAll(
            listOf(
                completed(memberId, "미션1", 2025, 1, 1),
                completed(memberId, "미션2", 2025, 1, 1),
                completed(memberId, "미션3", 2025, 1, 1),
                completed(memberId, "미션4", 2025, 1, 2),
                active(memberId, "미션5", 2025, 1, 3),
                completed(memberId, "미션6", 2025, 1, 5),
                completed(memberId, "미션7", 2025, 1, 5),
                completed(99999L, "다른유저미션", 2025, 1, 1)
            )
        )

        // when
        val result = memberMissionRepository.findDailyCompletedMissionCount(
            memberId = memberId,
            targetStartDate = LocalDate.of(2025, 1, 1),
            targetEndDate = LocalDate.of(2025, 1, 31),
        )

        // then
        assertEquals(3, result.size)
        assertEquals(LocalDate.of(2025, 1, 1), result[0].targetDate)
        assertEquals(3L, result[0].completedCount)
        assertEquals(LocalDate.of(2025, 1, 2), result[1].targetDate)
        assertEquals(1L, result[1].completedCount)
        assertEquals(LocalDate.of(2025, 1, 5), result[2].targetDate)
        assertEquals(2L, result[2].completedCount)
    }

    // 목적: soft delete 쿼리가 대상(memberId + memberInterestId + status=READY)만 삭제하는지 확인
    @Test
    fun `관심사별 READY 미션만 soft delete 된다`() {
        // given
        val memberId = 99902L
        val memberInterestId = 1001L

        memberMissionRepository.saveAll(
            listOf(
                ready(memberId, memberInterestId, "ready-1"),
                ready(memberId, memberInterestId, "ready-2"),
                active(memberId, "active-1", 2025, 1, 10).copyWith(memberInterestId = memberInterestId),
                ready(memberId, 2002L, "other-interest")
            )
        )

        // when
        val deletedCount = memberMissionRepository.softDeleteByMemberIdAndInterestIdAndStatus(
            memberId = memberId,
            memberInterestId = memberInterestId,
            status = MissionStatus.READY,
            deletedAt = LocalDateTime.now()
        )

        // then
        assertEquals(2, deletedCount)

        val remaining = memberMissionRepository.findByMemberIdAndDeletedFalse(memberId)
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.missionStatus == MissionStatus.ACTIVE && it.memberInterestId == memberInterestId })
        assertTrue(remaining.any { it.missionStatus == MissionStatus.READY && it.memberInterestId == 2002L })
    }

    private fun completed(
        memberId: Long,
        content: String,
        y: Int,
        m: Int,
        d: Int
    ) = MemberMissionEntity(
        memberId = memberId,
        memberInterestId = 1L,
        missionContent = content,
        expEarned = 10,
        missionStatus = MissionStatus.COMPLETED,
        targetDate = LocalDate.of(y, m, d),
        isSelected = true
    )

    private fun active(
        memberId: Long,
        content: String,
        y: Int,
        m: Int,
        d: Int
    ) = MemberMissionEntity(
        memberId = memberId,
        memberInterestId = 1L,
        missionContent = content,
        expEarned = 10,
        missionStatus = MissionStatus.ACTIVE,
        targetDate = LocalDate.of(y, m, d),
        isSelected = true
    )

    private fun ready(
        memberId: Long,
        memberInterestId: Long,
        content: String
    ) = MemberMissionEntity(
        memberId = memberId,
        memberInterestId = memberInterestId,
        missionContent = content,
        expEarned = 5,
        missionStatus = MissionStatus.READY,
        targetDate = LocalDate.now(),
        isSelected = false
    )

    private fun MemberMissionEntity.copyWith(memberInterestId: Long) = MemberMissionEntity(
        memberId = this.memberId,
        memberInterestId = memberInterestId,
        missionContent = this.missionContent,
        difficulty = this.difficulty,
        labelName = this.labelName,
        missionStatus = this.missionStatus,
        expEarned = this.expEarned,
        targetDate = this.targetDate,
        isSelected = this.isSelected
    )
}
