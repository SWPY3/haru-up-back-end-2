package com.haruUp.missionembedding.controller

import com.haruUp.global.common.ApiResponse
import com.haruUp.global.security.MemberPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import com.haruUp.missionembedding.dto.MissionRecommendationRequest
import com.haruUp.missionembedding.dto.MissionRecommendationResponse
import com.haruUp.global.ratelimit.RateLimit
import com.haruUp.mission.application.MissionRecommendUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 미션 API Controller
 */
@Tag(name = "미션 API", description = "AI 기반 미션 추천 및 선택 시스템")
@RestController
@RequestMapping("/api/missions")
class MissionembeddingController(
    private val missionRecommendUseCase: MissionRecommendUseCase
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 미션 추천 API
     *
     * 등록된 관심사(member_interest)를 기반으로 각 관심사당 미션 5개씩 추천
     * 챗봇 목표 기반 미션은 memberInterestIds=[0]으로 이미 생성된 오늘의 미션 조회
     * - 사전에 관심사가 등록되어 있어야 함 (/api/interests/member)
     * - 기존 READY 상태 미션은 soft delete 처리
     * - 추천된 미션은 READY 상태로 member_mission에 저장
     *
     * @param request 미션 추천 요청 (memberInterestIds)
     * @return 추천된 미션 목록
     */
    @Operation(
        summary = "미션 추천",
        description = """
            등록된 관심사를 기반으로 AI가 미션을 추천합니다.

            **사전 조건:** 관심사가 먼저 등록되어 있어야 합니다 (/api/interests/member)

            각 관심사당 난이도 1~5 각각 1개씩, 총 5개의 미션이 추천됩니다.

            **호출 예시:**
            ```json
            {
              "memberInterestIds": [1, 2, 3]
            }
            ```

            챗봇 완료 후 이미 생성된 오늘의 목표 기반 미션은 다음과 같이 조회합니다.
            이 요청은 미션을 새로 생성하지 않습니다.
            ```json
            {
              "memberInterestIds": [0]
            }
            ```

            `0`과 실제 관심사 ID를 함께 요청할 수 없습니다.

            **응답 예시 (각 관심사당 난이도 1~5):**
            - difficulty 1: 초등학생 수준
            - difficulty 2: 중학생 수준
            - difficulty 3: 고등학생 수준
            - difficulty 4: 대학생 수준
            - difficulty 5: 직장인 수준
        """
    )
    @RateLimit(key = "api:missions:recommend", limit = 50)
    @PostMapping("/recommend")
    fun recommendMissions(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @Parameter(
            description = "미션 추천 요청 정보",
            required = true,
            schema = Schema(implementation = MissionRecommendationRequest::class)
        )
        @RequestBody request: MissionRecommendationRequest
    ): ResponseEntity<ApiResponse<MissionRecommendationResponse>> {
        require(request.memberInterestIds.isNotEmpty()) { "관심사 ID 목록은 필수입니다." }

        val response = missionRecommendUseCase.recommendByMemberInterestIds(
            memberId = principal.id,
            memberInterestIds = request.memberInterestIds
        )

        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
