package com.haruUp.mission.application

import com.haruUp.character.application.service.LevelService
import com.haruUp.character.application.service.MemberCharacterService
import com.haruUp.character.domain.dto.MemberCharacterDto
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.domain.MemberMissionDto
import com.haruUp.mission.domain.MissionStatus
import com.haruUp.mission.domain.MissionStatusChangeItem
import com.haruUp.mission.domain.MissionStatusChangeRequest
import com.haruUp.mission.domain.DailyCompletionStatus
import com.haruUp.mission.domain.MonthlyCompletedDaysDto
import com.haruUp.mission.domain.MonthlyCompletedDaysResponseDto
import com.haruUp.mission.domain.MonthlyMissionDataDto
import com.haruUp.member.application.service.MemberService
import java.time.YearMonth
import com.haruUp.notification.domain.MissionPushTarget


import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Component
class MemberMissionUseCase(
    private val memberMissionService: MemberMissionService,
    private val memberCharacterService: MemberCharacterService,
    private val levelService: LevelService,
    private val memberService: MemberService
) {

    /** 조건(상태/날짜/관심사) 기준으로 미션 목록을 조회한다. */
    fun getMemberMissions(
        memberId: Long,
        statuses: List<MissionStatus>? = null,
        targetDate: LocalDate,
        memberInterestId: Long? = null
    ): List<MemberMissionDto> {
        return memberMissionService.getAllMissions(memberId, statuses, targetDate, memberInterestId)
    }

    /** 오늘 날짜의 미션 목록을 조회한다. */
    fun missionTodayList(memberId: Long): List<MemberMissionDto> {
        val memberMissions: List<MemberMissionEntity> = memberMissionService.getTodayMissionsByMemberId(memberId)
        return memberMissions.map { it.toDto() }
    }

    /**
     * 미션 상태 벌크 변경 (선택 / 완료 / 미루기 / 실패)
     */
    @Transactional
    fun missionChangeStatus(request: MissionStatusChangeRequest): MemberCharacterDto? {
        var lastCharacterDto: MemberCharacterDto? = null

        for (item in request.missions) {
            val result = processStatusChange(item)
            if (result != null) {
                lastCharacterDto = result
            }
        }

        return lastCharacterDto
    }

    /**
     * 개별 미션 상태 변경 처리
     */
    fun processStatusChange(item: MissionStatusChangeItem): MemberCharacterDto? {

        // missionStatus와 deleted 둘 중 하나는 필수
        require(item.missionStatus != null || item.deleted == true) {
            "missionStatus 또는 deleted 중 하나는 필수입니다. (memberMissionId: ${item.memberMissionId})"
        }

        // deleted가 true인 경우 상태 변경 없이 바로 soft delete
        if (item.deleted == true) {
            memberMissionService.softDeleteMission(item.memberMissionId)
            return null
        }

        // COMPLETED 상태인 경우 경험치 처리 필요
        if (item.missionStatus == MissionStatus.COMPLETED) {
            return handleMissionCompleted(item.memberMissionId)
        }

        if (item.missionStatus == MissionStatus.POSTPONED) {
            memberMissionService.handleMissionPostponed(item.memberMissionId)
            return null
        }

        // 그 외의 경우 (status 변경)
        memberMissionService.updateMission(item.memberMissionId, item.missionStatus)
        return null
    }

    /**
     * 미션 완료 → 경험치 반영 → 레벨업 처리
     */
    private fun handleMissionCompleted(memberMissionId: Long): MemberCharacterDto? {

        // ----------------------------------------------------------------------
        // 1) 미션 완료 처리
        // ----------------------------------------------------------------------
        val missionCompleted = memberMissionService.updateMission(memberMissionId, MissionStatus.COMPLETED)

        // ----------------------------------------------------------------------
        // 2) 선택된 캐릭터 조회 (없으면 경험치 지급 없이 완료만 처리)
        // ----------------------------------------------------------------------
        val mc = memberCharacterService.getSelectedCharacter(missionCompleted.memberId)
            ?: run {
                logger.warn("캐릭터 없음 - 미션 완료만 처리 (경험치 미지급) memberId: ${missionCompleted.memberId}")
                return null
            }

        // ----------------------------------------------------------------------
        // 3) 현재 레벨 정보 조회
        var currentLevel = levelService.getById(mc.levelId)

        // 4) 경험치 누적
        val newTotalExp = mc.totalExp + missionCompleted.expEarned
        var newCurrentExp = mc.currentExp + missionCompleted.expEarned

        var currentMaxExp = requireNotNull(currentLevel.maxExp) {
            "Level ${currentLevel.levelNumber} 의 maxExp 가 null 입니다. DB 데이터 확인 필요"
        }
        while (newCurrentExp >= currentMaxExp) {
            newCurrentExp -= currentMaxExp
            currentLevel = levelService.getOrCreateLevel(
                currentLevel.levelNumber + 1
            )
            currentMaxExp = requireNotNull(currentLevel.maxExp) {
                "Level ${currentLevel.levelNumber} 의 maxExp 가 null 입니다. DB 데이터 확인 필요"
            }
        }

        // 6) 결과 반영
        val updatedMc = memberCharacterService.applyExpWithResolvedValues(
            mc = mc,
            newLevelId = currentLevel.id!!,
            totalExp = newTotalExp,
            currentExp = newCurrentExp
        )

        return updatedMc.toDto()
    }


    /**
     * 특정 관심사에 해당하는 미션 리셋 (soft delete)
     *
     * @param memberId 사용자 ID
     * @param memberInterestId 멤버 관심사 ID
     * @return 삭제된 미션 개수
     */
    fun resetMissionsByMemberInterestId(memberId: Long, memberInterestId: Long): Int {
        return memberMissionService.deleteMissionsByMemberInterestId(memberId, memberInterestId)
    }

    /**
     * 연속 미션 달성 여부 조회
     *
     * @param memberId 사용자 ID
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 날짜별 미션 완료 상태 목록
     */
    fun getCompletionStatusByDateRange(
        memberId: Long,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<DailyCompletionStatus> {
        return memberMissionService.getCompletionStatusByDateRange(memberId, startDate, endDate)
    }

    /** 특정 월의 일별 완료 미션 수와 요약값을 반환한다. */
    fun continueMissionMonth(
        memberId: Long,
        targetMonth: String   // "YYYY-MM"
    ): MonthlyMissionDataDto {
        val yearMonth = parseYearMonth(targetMonth)

        val targetStartDate: LocalDate = yearMonth.atDay(1)
        val targetEndDate: LocalDate = yearMonth.atEndOfMonth()

        val missionCounts = memberMissionService.findDailyCompletedMissionCount(
            memberId,
            targetStartDate,
            targetEndDate
        )

        // 총 미션 완료 수
        val totalMissionCount = missionCounts.sumOf { it.completedCount }

        // 미션 완료한 날 수
        val totalCompletedDays = missionCounts.size

        return MonthlyMissionDataDto(
            missionCounts = missionCounts,
            totalMissionCount = totalMissionCount,
            totalCompletedDays = totalCompletedDays
        )
    }

    /**
     * 월별 미션 완료한 날 수 조회
     * - 회원가입일 이후의 월만 반환
     */
    fun getMonthlyCompletedDays(
        memberId: Long,
        startTargetMonth: String,
        endTargetMonth: String
    ): MonthlyCompletedDaysResponseDto {
        val startYearMonth = parseYearMonth(startTargetMonth)
        val endYearMonth = parseYearMonth(endTargetMonth)

        // 회원 정보 조회하여 가입일 확인
        val member = memberService.getFindMemberId(memberId)
            .orElseThrow { IllegalArgumentException("회원 정보를 찾을 수 없습니다.") }

        val memberCreatedAt = member.createdAt?.toLocalDate()
            ?: throw IllegalStateException("회원 가입일 정보가 없습니다.")

        val memberJoinYearMonth = YearMonth.from(memberCreatedAt)

        // 시작월이 회원가입월보다 이전이면 회원가입월부터 시작
        val effectiveStartYearMonth = if (startYearMonth.isBefore(memberJoinYearMonth)) {
            memberJoinYearMonth
        } else {
            startYearMonth
        }

        // 회원가입월이 종료월보다 이후이면 빈 결과 반환
        if (effectiveStartYearMonth.isAfter(endYearMonth)) {
            return MonthlyCompletedDaysResponseDto(monthlyData = emptyList())
        }

        val startDate = effectiveStartYearMonth.atDay(1)
        val endDate = endYearMonth.atEndOfMonth()

        // 미션 완료한 날짜 목록 조회
        val completedDates = memberMissionService.findCompletedDatesByDateRange(
            memberId, startDate, endDate
        )

        // 월별로 그룹핑하여 카운트
        val monthlyCountsMap = completedDates
            .groupBy { "${it.year}-${it.monthValue.toString().padStart(2, '0')}" }
            .mapValues { it.value.size }

        // 회원가입월부터 종료월까지 모든 월 생성
        val monthlyData = mutableListOf<MonthlyCompletedDaysDto>()
        var current = effectiveStartYearMonth
        while (!current.isAfter(endYearMonth)) {
            val monthKey = "${current.year}-${current.monthValue.toString().padStart(2, '0')}"
            monthlyData.add(
                MonthlyCompletedDaysDto(
                    targetMonth = monthKey,
                    completedDays = monthlyCountsMap[monthKey] ?: 0
                )
            )
            current = current.plusMonths(1)
        }

        return MonthlyCompletedDaysResponseDto(monthlyData = monthlyData)
    }

    /** 오늘 미션을 완료하지 않은 회원 목록(푸시 대상)을 조회한다. */
    fun getMembersWithTodayFalseMission() : List<MissionPushTarget> {
        val atStartOfDay = LocalDate.now().atStartOfDay()
        val atEndDate = atStartOfDay.plusDays(1)
        return memberMissionService.getMembersWithTodayFalseMission(atStartOfDay, atEndDate)
    }

    /** YYYY-MM 형식 문자열을 YearMonth로 파싱한다. */
    private fun parseYearMonth(value: String): YearMonth =
        try {
            YearMonth.parse(value)
        } catch (e: Exception) {
            throw IllegalArgumentException("잘못된 날짜 형식입니다. YYYY-MM 형식으로 입력해주세요.")
        }

}
