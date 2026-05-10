package com.haruUp.curation.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "챗봇 시작 응답")
data class ChatbotStartResponse(
    @Schema(description = "세션 ID (이후 요청에 사용)", example = "550e8400-e29b-41d4-a716-446655440000")
    val sessionId: String,

    @Schema(description = "첫 번째 질문", example = "어떤 목표를 이루고 싶으신가요?")
    val question: String,

    @Schema(description = "예시 답변 목록")
    val examples: List<String>,

    @Schema(description = "현재 질문 번호 (1부터 시작)", example = "1")
    val questionNumber: Int
)

@Schema(description = "챗봇 답변 요청")
data class ChatbotAnswerRequest(
    @Schema(description = "세션 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    val sessionId: String,

    @Schema(description = "사용자 답변", example = "매일 30분씩 운동하고 싶어요")
    val answer: String
)

@Schema(description = "챗봇 답변 응답 (대화 진행 중)")
data class ChatbotAnswerResponse(
    @Schema(description = "세션 ID")
    val sessionId: String,

    @Schema(description = "다음 질문")
    val question: String,

    @Schema(description = "현재 질문 번호", example = "2")
    val questionNumber: Int,

    @Schema(description = "마지막 질문 여부", example = "false")
    val isLast: Boolean
)

@Schema(description = "챗봇 완료 응답 (5번째 질문 답변 후)")
data class ChatbotCompleteResponse(
    @Schema(description = "완료 여부", example = "true")
    val isCompleted: Boolean,

    @Schema(description = "사용자의 첫 번째 목표 답변")
    val goalText: String
)
