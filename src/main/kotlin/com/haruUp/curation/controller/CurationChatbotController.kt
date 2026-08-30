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
            - 1번 답변(목표): AI가 목표를 분석해 목표가 2개 이상이면 질문을 진행하지 않고 재입력 응답 반환
              (ChatbotGoalRejectedResponse) — isValidGoal=false, message를 입력란 아래에 강조 표시하고
              같은 sessionId로 목표를 다시 제출하면 됩니다. 목표가 1개면 아래 꼬리질문 응답으로 진행합니다.
            - 1~5번 답변: 다음 꼬리질문 반환 (ChatbotAnswerResponse) — question과 함께 examples(예시 답변 3개) 포함, 사용자가 골라서 답할 수 있음
              questionType=AI_FOLLOW_UP
            - 6번 답변:
              · 시간 투자가 필요한 목표면 투자 가능 시간을 묻는 고정 질문 반환
                (ChatbotAnswerResponse, questionNumber=7, questionType=FIXED_TIME, isLast=true)
                examples는 "10분 이내 / 30분 / 1시간 이상" 고정 선택지이며 직접 입력도 가능
              · 시간 투자가 필요 없는 목표면 아래 완료 응답으로 바로 종료
            - 마지막 답변: 목표 저장 + 미션 생성 완료 응답 반환 (ChatbotCompleteResponse) — missions 필드에 15개 미션 포함
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
