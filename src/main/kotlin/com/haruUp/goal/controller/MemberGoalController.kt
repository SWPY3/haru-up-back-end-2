package com.haruUp.goal.controller

import com.haruUp.global.common.ApiResponse
import com.haruUp.global.security.MemberPrincipal
import com.haruUp.goal.domain.MemberGoal
import com.haruUp.goal.repository.MemberGoalRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "회원 목표", description = "회원 목표 조회 API")
@RestController
@RequestMapping("/api/member/goal")
class MemberGoalController(
    private val memberGoalRepository: MemberGoalRepository
) {

    @Operation(
        summary = "현재 활성 목표 조회",
        description = "로그인한 사용자의 현재 활성화된 목표를 조회합니다. 목표가 없으면 data는 null입니다."
    )
    @GetMapping
    fun getCurrentGoal(
        @AuthenticationPrincipal principal: MemberPrincipal
    ): ApiResponse<MemberGoalResponse?> {
        val goal = memberGoalRepository.findByMemberIdAndIsActiveTrue(principal.id)
        val response = goal?.let { MemberGoalResponse.from(it) }
        return ApiResponse.success(response)
    }

    data class MemberGoalResponse(
        val id: Long,
        val goalText: String,
        val conversationSummary: String
    ) {
        companion object {
            fun from(goal: MemberGoal): MemberGoalResponse = MemberGoalResponse(
                id = goal.id!!,
                goalText = goal.goalText,
                conversationSummary = goal.conversationSummary
            )
        }
    }
}
