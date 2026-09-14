package com.haruUp.interest.repository

import com.haruUp.interest.entity.MemberInterestEntity
import com.haruUp.member.domain.Member
import com.haruUp.member.domain.type.LoginType
import com.haruUp.member.domain.type.MemberStatus
import com.haruUp.member.infrastructure.MemberRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles

// DB·Redis 등 외부 인프라가 필요하다. CI의 unitTest 태스크에서는 제외된다.
@Tag("integration")
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
/**
 * MemberInterest 커스텀 리포지토리의 soft delete 쿼리가
 * 사용자 범위/대상 row 기준으로 정확히 동작하는지 검증한다.
 */
class MemberInterestJpaRepositoryImplTest(
    @Autowired private val memberInterestRepository: MemberInterestJpaRepository,
    @Autowired private val memberRepository: MemberRepository,
    @Autowired private val entityManager: EntityManager
) {

    @BeforeEach
    fun setUp() {
        entityManager
            .createNativeQuery("TRUNCATE TABLE member_interest RESTART IDENTITY CASCADE")
            .executeUpdate()
    }

    // 목적: 특정 memberId의 관심사 전체 soft delete가 정확히 해당 멤버 데이터에만 적용되는지 확인
    @Test
    fun `softDeleteAllByMemberId는 대상 멤버의 관심사를 모두 soft delete 한다`() {
        // given
        val memberId = createMember("member-interest-1@test.com")
        val otherMemberId = createMember("member-interest-2@test.com")

        val interestId = 1L
        val otherInterestId = 2L

        memberInterestRepository.save(
            MemberInterestEntity(memberId = memberId, interestId = interestId, directFullPath = listOf("A", "B", "C"))
        )
        memberInterestRepository.save(
            MemberInterestEntity(memberId = memberId, interestId = otherInterestId, directFullPath = listOf("A", "B", "D"))
        )
        memberInterestRepository.save(
            MemberInterestEntity(memberId = otherMemberId, interestId = interestId, directFullPath = listOf("X", "Y", "Z"))
        )

        // when
        val deletedCount = memberInterestRepository.softDeleteAllByMemberId(memberId)

        // then
        assertEquals(2, deletedCount)
        assertTrue(memberInterestRepository.findByMemberIdAndDeletedFalse(memberId).isEmpty())
        assertEquals(1, memberInterestRepository.findByMemberIdAndDeletedFalse(otherMemberId).size)
    }

    // 목적: id + memberId 기준 단건 soft delete가 정확히 하나의 row에만 반영되는지 확인
    @Test
    fun `softDeleteByIdAndMemberId는 지정한 관심사만 soft delete 한다`() {
        // given
        val memberId = createMember("member-interest-3@test.com")
        val interestId = 1L

        val target = memberInterestRepository.save(
            MemberInterestEntity(memberId = memberId, interestId = interestId, directFullPath = listOf("M", "N", "O"))
        )
        val untouched = memberInterestRepository.save(
            MemberInterestEntity(memberId = memberId, interestId = interestId, directFullPath = listOf("M", "N", "P"))
        )

        // when
        val deletedCount = memberInterestRepository.softDeleteByIdAndMemberId(target.id!!, memberId)

        // then
        assertEquals(1, deletedCount)
        assertNull(memberInterestRepository.findByIdAndMemberIdAndDeletedFalse(target.id!!, memberId))
        assertTrue(memberInterestRepository.findByIdAndMemberIdAndDeletedFalse(untouched.id!!, memberId) != null)
    }

    private fun createMember(email: String): Long {
        return memberRepository.save(
            Member(
                name = "tester",
                email = email,
                password = "Pass1234!",
                snsId = email,
                loginType = LoginType.COMMON,
                status = MemberStatus.ACTIVE
            )
        ).id!!
    }
}
