package com.haruUp.mission.controller

import com.haruUp.global.common.ApiResponse
import com.haruUp.global.security.MemberPrincipal
import com.haruUp.global.ratelimit.RateLimit
import com.haruUp.mission.application.MemberMissionUseCase
import com.haruUp.mission.application.MissionRecommendUseCase
import com.haruUp.mission.domain.DailyCompletionStatus
import com.haruUp.mission.domain.DailyMissionCountDto
import com.haruUp.mission.domain.MemberMissionDto
import com.haruUp.mission.domain.MonthlyCompletedDaysResponseDto
import com.haruUp.mission.domain.MonthlyMissionDataDto
import com.haruUp.mission.domain.MissionRecommendResult
import com.haruUp.mission.domain.MissionStatus
import com.haruUp.mission.domain.MissionStatusChangeRequest
import com.haruUp.missionembedding.dto.TodayMissionRecommendationRequest
import com.haruUp.missionembedding.dto.TodayMissionRetryRequest
import com.haruUp.missionembedding.dto.MissionRecommendationResponse
import com.haruUp.mission.domain.MemberMissionSelectionRequest
import com.haruUp.mission.domain.MissionStatusChangeItem
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@Tag(name = "멤버 미션 API", description = "멤버 미션 관리 및 추천")
@RestController
@RequestMapping("/api/member/mission")
class MemberMissionController(
    private val memberMissionUseCase: MemberMissionUseCase,
    private val missionRecommendUseCase: MissionRecommendUseCase
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 멤버 미션 목록 조회
     */
    @Operation(
        summary = "멤버 미션 목록 조회",
        description = """
            해당 멤버의 삭제되지 않은 미션을 조회합니다.

            **호출 예시:**
            ```
            GET /api/member/mission
            GET /api/member/mission?missionStatus=ACTIVE
            GET /api/member/mission?missionStatus=ACTIVE,INACTIVE,COMPLETED
            GET /api/member/mission?targetDate=2025-01-15
            GET /api/member/mission?memberInterestId=1
            GET /api/member/mission?missionStatus=ACTIVE&targetDate=2025-01-15&memberInterestId=1
            ```
        """
    )
    @GetMapping
    fun getMemberMissions(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "미션 상태 필터 (콤마로 구분, 미입력시 전체 조회)",
            example = "ACTIVE,INACTIVE"
        )
        @RequestParam(required = false) missionStatus: String?,
        @Parameter(
            description = "조회할 날짜 (yyyy-MM-dd, 기본값: 오늘)",
            example = ""
        )
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        targetDate: LocalDate?,
        @Parameter(
            description = "멤버 관심사 ID (미입력시 전체 조회)",
            example = "1"
        )
        @RequestParam(required = false) memberInterestId: Long?
    ): ApiResponse<List<MemberMissionDto>> {
        val statuses = missionStatus?.split(",")
            ?.map { it.trim().uppercase() }
            ?.mapNotNull {
                try { MissionStatus.valueOf(it) }
                catch (e: IllegalArgumentException) { null }
            }

        return ApiResponse.success(
            memberMissionUseCase.getMemberMissions(principal.id, statuses, targetDate ?: LocalDate.now(), memberInterestId)
        )
    }

    /**
     * 연속 미션 달성 여부 조회
     */
    @Operation(
        summary = "연속 미션 달성 여부 조회",
        description = """
            시작 날짜와 종료 날짜 사이의 각 날짜별 미션 완료 여부를 조회합니다.
            해당 날짜에 COMPLETED 상태의 미션이 1개 이상 있으면 isCompleted: true

            **호출 예시:**
            ```
            GET /api/member/mission/completion-status?startDate=2025-01-01&endDate=2025-01-07
            ```

            **응답 예시:**
            ```json
            {
              "success": true,
              "data": [
                { "targetDate": "2025-01-01", "isCompleted": true },
                { "targetDate": "2025-01-02", "isCompleted": false },
                { "targetDate": "2025-01-03", "isCompleted": true }
              ]
            }
            ```
        """
    )
    @GetMapping("/completion-status")
    fun getCompletionStatus(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "시작 날짜 (yyyy-MM-dd)",
            required = true,
            example = "2025-01-01"
        )
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        startDate: LocalDate,
        @Parameter(
            description = "종료 날짜 (yyyy-MM-dd)",
            required = true,
            example = "2025-01-31"
        )
        @RequestParam
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        endDate: LocalDate
    ): ResponseEntity<ApiResponse<List<DailyCompletionStatus>>> {
        logger.info("연속 미션 달성 여부 조회 - memberId: ${principal.id}, startDate: $startDate, endDate: $endDate")

        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().body(
                ApiResponse(
                    success = false,
                    data = null,
                    errorMessage = "시작 날짜가 종료 날짜보다 이후일 수 없습니다."
                )
            )
        }

        return try {
            val result = memberMissionUseCase.getCompletionStatusByDateRange(
                memberId = principal.id,
                startDate = startDate,
                endDate = endDate
            )
            logger.info("연속 미션 달성 여부 조회 완료 - 조회된 날짜 수: ${result.size}")
            ResponseEntity.ok(ApiResponse.success(result))
        } catch (e: Exception) {
            logger.error("연속 미션 달성 여부 조회 실패: ${e.message}", e)
            ResponseEntity.internalServerError().body(
                ApiResponse(
                    success = false,
                    data = null,
                    errorMessage = "서버 오류가 발생했습니다."
                )
            )
        }
    }




    /**
     * 미션 상태 벌크 변경 (ACTIVE / COMPLETED / INACTIVE / POSTPONED) 및 미루기
     */
    @Operation(
        summary = "미션 상태 벌크 변경",
        description = """
            여러 미션의 상태를 한 번에 변경합니다.

            **상태 변경:**
            - missionStatus: ACTIVE (선택), COMPLETED (완료), INACTIVE (포기), POSTPONED (내일로 미루기)
            - deleted: true로 설정 시 상태 변경 없이 바로 soft delete (선택 사항)

            **호출 예시:**
            ```json
            {
              "missions": [
                { "memberMissionId": 1, "missionStatus": "COMPLETED" },
                { "memberMissionId": 2, "missionStatus": "ACTIVE" },
                { "memberMissionId": 3, "missionStatus": "POSTPONED" },
                { "memberMissionId": 4, "deleted": true }
              ]
            }
            ```
        """
    )

    @PutMapping("/status")
    fun changeMissionStatus(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @RequestBody request: MissionStatusChangeRequest
    ): ApiResponse<Any> {

        memberMissionUseCase.missionChangeStatus(request)

        return ApiResponse.success("OK")
    }

    /**
     * 미션 선택 API
     *
     * 사용자가 선택한 미션들을 ACTIVE 상태로 변경
     *
     * @param request 미션 선택 요청
     * @return 저장 결과
     */
    @Operation(
        summary = "미션 선택",
        description = """
            사용자가 선택한 미션들을 ACTIVE 상태로 변경합니다.

            **호출 예시:**
            ```json
            {
              "memberMissionIds": [1, 2, 3]
            }
            ```

            **필드 설명:**
            - memberMissionIds: member_mission 테이블의 ID 목록
        """
    )
    @PostMapping("/select")
    fun selectMissions(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "미션 선택 요청 정보",
            required = true,
            schema = Schema(implementation = MemberMissionSelectionRequest::class)
        )
        @RequestBody request: MemberMissionSelectionRequest
    ): ResponseEntity<ApiResponse<List<Long>>> {
        val savedMissionIds = missionRecommendUseCase.memberMissionSelection(principal.id, request.memberMissionIds)

        return ResponseEntity.ok(ApiResponse.success(savedMissionIds))
    }

    /**
     * 오늘의 미션 추천 조회 API
     *
     * @param request 오늘의 미션 추천 요청 (memberInterestId)
     * @return 추천된 미션 목록
     */
    @Operation(
        summary = "오늘의 미션 추천 조회",
        description = """
            오늘의 미션 추천 정보를 조회합니다.

            **호출 예시:**
            ```
            GET /api/member/mission/recommend?memberInterestId=1
            GET /api/member/mission/recommend?memberInterestId=1&targetDate=2025-01-15
            GET /api/member/mission/recommend?memberInterestId=1&missionStatus=READY
            GET /api/member/mission/recommend?memberInterestId=1&missionStatus=READY,ACTIVE
            ```
        """
    )
    @GetMapping("/recommend")
    fun recommendMissions(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "멤버 관심사 ID",
            required = true
        )
        @RequestParam memberInterestId: Long,
        @Parameter(
            description = "조회할 날짜 (기본값: 오늘)",
            required = false
        )
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        targetDate: LocalDate?,
        @Parameter(
            description = "미션 상태 필터 (콤마로 구분, 기본값: READY)",
            example = "READY,ACTIVE"
        )
        @RequestParam(required = false, defaultValue = "READY") missionStatus: String
    ): ResponseEntity<ApiResponse<MissionRecommendResult>> = runBlocking {
        val statuses = missionStatus.split(",")
            .map { it.trim().uppercase() }
            .mapNotNull {
                try { MissionStatus.valueOf(it) }
                catch (e: IllegalArgumentException) { null }
            }

        val response = missionRecommendUseCase.recommendToday(
            memberId = principal.id,
            memberInterestId = memberInterestId,
            targetDate = targetDate ?: LocalDate.now(),
            statuses = statuses
        )
        ResponseEntity.ok(ApiResponse.success(response))
    }

    /**
     * 오늘의 미션 재추천 API
     *
     * 사용자 프로필과 관심사 정보를 기반으로 미션 재추천
     * - ACTIVE 상태인 기존 미션 자동 제외
     * - Redis에 저장된 이전 추천 미션 자동 제외
     * - 추천된 미션 ID는 Redis에 캐싱 (24시간)
     * - reset_mission_count 카운트 증가
     *
     * @param request 오늘의 미션 재추천 요청 (memberInterestId)
     * @return 추천된 미션 목록
     */
    @Operation(
        summary = "오늘의 미션 재추천",
        description = """
            사용자 프로필과 선택한 관심사를 기반으로 오늘의 미션을 재추천합니다.

            **자동 제외 기능:**
            - ACTIVE 상태인 기존 미션 자동 제외
            - Redis에 캐싱된 이전 추천 미션 자동 제외 (24시간)

            **호출 예시:**
            ```json
            {
              "memberInterestId": 1,
              "excludeMemberMissionIds": [10, 11, 12]
            }
            ```

            챗봇 목표 기반 미션 재추천:
            ```json
            {
              "memberInterestId": 0
            }
            ```
        """
    )
    @RateLimit(key = "api:member:mission:retry", limit = 50)
    @PostMapping("/retry")
    fun retryMissions(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "오늘의 미션 재추천 요청 정보",
            required = true,
            schema = Schema(implementation = TodayMissionRetryRequest::class)
        )
        @RequestBody request: TodayMissionRetryRequest
    ): ResponseEntity<ApiResponse<MissionRecommendationResponse>> = runBlocking {
        val response = missionRecommendUseCase.retryRecommend(
            memberId = principal.id,
            memberInterestId = request.memberInterestId,
            excludeMemberMissionIds = request.excludeMemberMissionIds
        )
        ResponseEntity.ok(ApiResponse.success(response))
    }

    /**
     * 미션 리셋 API
     *
     * 특정 관심사에 해당하는 미션을 soft delete
     *
     * @param memberInterestId 멤버 관심사 ID
     * @return 삭제된 미션 개수
     */
    @Operation(
        summary = "미션 리셋",
        description = """
            특정 관심사에 해당하는 모든 미션을 soft delete 합니다.

            **호출 예시:**
            ```
            DELETE /api/member/mission/reset/1
            ```
        """
    )
    @DeleteMapping("/reset/{memberInterestId}")
    fun resetMissions(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "멤버 관심사 ID",
            required = true
        )
        @PathVariable memberInterestId: Long
    ): ResponseEntity<ApiResponse<Int>> {
        val deletedCount = memberMissionUseCase.resetMissionsByMemberInterestId(
            memberId = principal.id,
            memberInterestId = memberInterestId
        )

        return ResponseEntity.ok(ApiResponse.success(deletedCount))
    }

    /**
     * 미션 재추천 횟수 초기화 API
     */
    @Operation(
        summary = "미션 재추천 횟수 초기화",
        description = """
            오늘의 미션 재추천 횟수를 0으로 초기화합니다.

            **호출 예시:**
            ```
            POST /api/member/mission/retry-count/reset
            ```
        """
    )
    @PostMapping("/retry-count/reset")
    fun resetRetryCount(
        @AuthenticationPrincipal principal: MemberPrincipal
    ): ResponseEntity<ApiResponse<Boolean>> {
        val result = missionRecommendUseCase.resetRetryCount(principal.id)

        return ResponseEntity.ok(ApiResponse.success(result))
    }


    @Operation(
        summary = "월간 미션 수행 현황 조회",
        description = """
        지정한 월(YYYY-MM)에 대해
        사용자가 날짜별로 완료한 미션 개수를 조회합니다.

        - missionCounts: 미션 상태가 COMPLETED 인 항목만 집계됩니다. 완료 미션이 없는 날짜는 결과에 포함되지 않습니다.
        - totalMissionCount: 해당 월의 총 미션 완료 수
        - totalCompletedDays: 해당 월에 미션을 완료한 날 수
        - 결과는 날짜 오름차순으로 반환됩니다.
    """
    )
    @GetMapping("/continue/mission/month/{targetMonth}")
    fun continueMissionMonth(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "조회할 대상 월 (YYYY-MM 형식)",
            example = "2025-01",
            required = true
        )
        @PathVariable
        targetMonth: String
    ): ApiResponse<MonthlyMissionDataDto> {

        val result =
            memberMissionUseCase.continueMissionMonth(principal.id, targetMonth)

        return ApiResponse.success(result)
    }

    @Operation(
        summary = "월별 미션 완료한 날 수 조회",
        description = """
        시작월부터 종료월까지의 월별 미션 완료한 날 수를 조회합니다.

        - startTargetMonth: 조회 시작월 (YYYY-MM)
        - endTargetMonth: 조회 종료월 (YYYY-MM)
        - 미션 완료가 없는 월도 0으로 포함됩니다.
    """
    )
    @GetMapping("/continue/mission/month")
    fun getMonthlyCompletedDays(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "조회 시작월 (YYYY-MM 형식)",
            example = "2026-01",
            required = true
        )
        @RequestParam startTargetMonth: String,
        @Parameter(
            description = "조회 종료월 (YYYY-MM 형식)",
            example = "2026-12",
            required = true
        )
        @RequestParam endTargetMonth: String
    ): ApiResponse<MonthlyCompletedDaysResponseDto> {

        val result = memberMissionUseCase.getMonthlyCompletedDays(
            principal.id,
            startTargetMonth,
            endTargetMonth
        )

        return ApiResponse.success(result)
    }
}
