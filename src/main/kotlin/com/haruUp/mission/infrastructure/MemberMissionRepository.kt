package com.haruUp.mission.infrastructure

import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MissionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface MemberMissionRepository :
    JpaRepository<MemberMissionEntity, Long>,
    MemberMissionRepositoryCustom {

    /** 사용자의 모든 미션 조회 */
    fun findByMemberId(memberId: Long): List<MemberMissionEntity>

    /** 사용자의 삭제되지 않은 미션 조회 */
    fun findByMemberIdAndDeletedFalse(memberId: Long): List<MemberMissionEntity>

    /** ID로 삭제되지 않은 미션 조회 */
    fun findByIdAndDeletedFalse(id: Long): MemberMissionEntity?

    /**
     * memberId, memberInterestId로 삭제되지 않은 미션 조회
     * 제외할 미션 유효성 검증에 사용
     */
    fun findByMemberIdAndMemberInterestIdAndDeletedFalse(
        memberId: Long,
        memberInterestId: Long
    ): List<MemberMissionEntity>

    /**
     * memberId, memberInterestId로 삭제되지 않은 READY 상태 미션만 조회
     * 재추천 시 제외할 미션 유효성 검증에 사용
     */
    fun findByMemberIdAndMemberInterestIdAndMissionStatusAndDeletedFalse(
        memberId: Long,
        memberInterestId: Long,
        missionStatus: MissionStatus
    ): List<MemberMissionEntity>

    /**
     * 특정 날짜에 추천된 미션 조회 (제외할 미션 조회용)
     */
    fun findByMemberIdAndMemberInterestIdAndTargetDate(
        memberId: Long,
        memberInterestId: Long,
        targetDate: LocalDate
    ): List<MemberMissionEntity>

    /**
     * 목표 기반 미션 재생성 시 기존 오늘 미션 삭제
     */
    fun deleteByMemberIdAndTargetDateAndMemberInterestId(
        memberId: Long,
        targetDate: LocalDate,
        memberInterestId: Long
    )

    /**
     * 사용자의 목표 기반 미션 전체 조회 (과거 미션 중복 방지용)
     */
    fun findByMemberIdAndMemberInterestId(
        memberId: Long,
        memberInterestId: Long
    ): List<MemberMissionEntity>

    /**
     * 현재 목표 시작일 이후의 미션만 조회 (목표 변경 시 이전 목표 미션 제외)
     */
    fun findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(
        memberId: Long,
        memberInterestId: Long,
        targetDate: LocalDate
    ): List<MemberMissionEntity>
}
