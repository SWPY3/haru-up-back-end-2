package com.haruUp.curation.controller

import com.haruUp.curation.application.CurationChatbotUseCase
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.global.common.ApiResponse
import com.haruUp.global.security.MemberPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "챗봇 큐레이션", description = "목표 설정을 위한 챗봇 대화 API")
@RestController
@RequestMapping("/api/member/curation/chatbot")
class CurationChatbotController(
    private val curationChatbotUseCase: CurationChatbotUseCase
) {

    @Operation(
        summary = "챗봇 대화 시작",
        description = "목표 설정 챗봇을 시작합니다. 세션 ID와 첫 번째 질문을 반환합니다."
    )
    @PostMapping("/start")
    fun startChatbot(
        @AuthenticationPrincipal principal: MemberPrincipal
    ): ApiResponse<Any> {
        val response = curationChatbotUseCase.startChatbot(memberId = principal.id)
        return ApiResponse.success(response)
    }

    @Operation(
        summary = "챗봇 답변 제출",
        description = """
            사용자의 답변을 제출합니다.
            - 1~5번 답변: 다음 꼬리질문 반환 (ChatbotAnswerResponse)
            - 6번 답변: 목표 저장 + 미션 생성 완료 응답 반환 (ChatbotCompleteResponse) — missions 필드에 15개 미션 포함
        """
    )
    @PostMapping("/answer")
    fun answer(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @RequestBody request: ChatbotAnswerRequest
    ): ApiResponse<Any> {
        val response = curationChatbotUseCase.answer(request)
        return ApiResponse.success(response)
    }


}
