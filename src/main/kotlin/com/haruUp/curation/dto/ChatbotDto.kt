package com.haruUp.curation.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "챗봇 시작 응답")
data class ChatbotStartResponse(
    @Schema(description = "세션 ID (이후 요청에 사용)", example = "550e8400-e29b-41d4-a716-446655440000")
    val sessionId: String,

    @Schema(description = "첫 번째 질문", example = "이루고 싶은 목표 한 개를 직접 입력해주세요.")
    val question: String,

    @Schema(
        description = """
            선택형 예시 답변 목록.
            첫 질문에서는 사용자가 예시를 그대로 선택해 목표가 구체화되지 않는 문제가 있어 항상 비어 있다.
            대신 placeholder로 예시를 보여준다.
        """
    )
    val examples: List<String>,

    @Schema(
        description = "입력란에 표시할 placeholder (고를 수 없는 예시)",
        example = "예시) 근육 향상 및 체력 증진 / 월 주식 투자 수익 30만원 / 금연하기"
    )
    val placeholder: String,

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

@Schema(description = "질문 유형")
enum class ChatbotQuestionType {
    @Schema(description = "AI가 대화 맥락에 맞춰 생성한 꼬리질문")
    AI_FOLLOW_UP,

    @Schema(description = "투자 가능 시간을 묻는 고정 질문 (시간 투자가 필요한 목표에서만 등장)")
    FIXED_TIME
}

@Schema(description = "챗봇 답변 응답 (대화 진행 중)")
data class ChatbotAnswerResponse(
    @Schema(description = "세션 ID")
    val sessionId: String,

    @Schema(description = "다음 질문")
    val question: String,

    @Schema(
        description = "예시 답변 목록 (사용자가 직접 입력하지 않고 골라서 답할 수 있도록 제공, 보통 3개)",
        example = "[\"10분 이내\", \"30분 정도\", \"1시간 이상\"]"
    )
    val examples: List<String>,

    @Schema(description = "현재 질문 번호", example = "2")
    val questionNumber: Int,

    @Schema(description = "마지막 질문 여부", example = "false")
    val isLast: Boolean,

    @Schema(
        description = """
            질문 유형. FIXED_TIME이면 examples를 고정 선택지로 표시한다.
            어느 유형이든 사용자는 예시를 고르거나 직접 입력할 수 있다.
        """,
        example = "AI_FOLLOW_UP"
    )
    val questionType: ChatbotQuestionType = ChatbotQuestionType.AI_FOLLOW_UP
)

@Schema(
    description = """
        목표 입력 검증 실패 응답 (첫 번째 답변에서 목표를 2개 이상 입력한 경우)
        세션은 그대로 유지되며 질문 번호도 1에 머문다. 같은 sessionId로 목표를 다시 제출하면 된다.
    """
)
data class ChatbotGoalRejectedResponse(
    @Schema(description = "세션 ID")
    val sessionId: String,

    @Schema(description = "목표 검증 통과 여부 (항상 false)", example = "false")
    val isValidGoal: Boolean = false,

    @Schema(description = "사용자에게 보여줄 안내 문구 (입력란 아래 강조 텍스트)", example = "목표를 하나만 입력해주세요!")
    val message: String,

    @Schema(description = "AI가 찾아낸 목표 목록", example = "[\"다이어트\", \"토익 900점\"]")
    val detectedGoals: List<String>,

    @Schema(description = "현재 질문 번호 (재입력이므로 1 유지)", example = "1")
    val questionNumber: Int = 1
)

@Schema(
    description = """
        마무리 확인 응답.
        AI가 미션을 만들기에 충분한 정보를 모았다고 판단했을 때, 요약을 보여주고 마무리할지 묻는다.
        사용자가 "예"를 고르면 마무리, "아니오"를 고르면 질문을 이어간다.
        응답은 다음 /answer 요청의 answer 필드에 examples 중 하나를 그대로 담아 보내면 된다.
    """
)
data class ChatbotFinishConfirmResponse(
    @Schema(description = "세션 ID")
    val sessionId: String,

    @Schema(description = "지금까지의 대화를 사용자에게 보여주는 짧은 요약")
    val summary: String,

    @Schema(description = "마무리 여부를 묻는 질문", example = "이대로 마무리할까요?")
    val question: String,

    @Schema(
        description = "선택지. 첫 번째가 마무리, 두 번째가 대화 계속",
        example = "[\"네, 미션 만들어주세요\", \"아니요, 더 이야기할게요\"]"
    )
    val examples: List<String>,

    @Schema(description = "지금까지 답변한 꼬리질문 수", example = "3")
    val answeredCount: Int,

    @Schema(description = "마무리 확인 단계 여부 (항상 true)", example = "true")
    val awaitingFinishConfirmation: Boolean = true
)

@Schema(description = "챗봇 완료 응답 - 미션 목록 포함")
data class ChatbotCompleteResponse(
    @Schema(description = "완료 여부", example = "true")
    val isCompleted: Boolean,

    @Schema(description = "사용자의 목표 텍스트")
    val goalText: String,

    @Schema(
        description = "대화 내용을 사용자에게 보여주기 위한 짧은 요약 (1~2문장)",
        example = "현재 68kg에서 5kg 감량이 목표이고, 하루 30분 정도 운동할 수 있어요."
    )
    val summary: String,

    @Schema(description = "생성된 미션 목록 (하5 + 중5 + 상5 = 15개)")
    val missions: List<ChatbotMissionDto>
)

@Schema(description = "챗봇 완료 후 반환되는 미션 정보")
data class ChatbotMissionDto(
    @Schema(description = "미션 ID", example = "1")
    val id: Long,

    @Schema(description = "미션 제목 (짧은 텍스트)", example = "LC 파트3,4 핵심 패턴 메모")
    val missionContent: String,

    @Schema(description = "미션 구체적 실행 방법", example = "유튜브 '토익 독학 길잡이' 채널 영상 시청 후 핵심 패턴 5가지 메모하기")
    val missionDescription: String?,

    @Schema(description = "난이도 (1=하, 2=중, 3=상)", example = "1")
    val difficulty: Int,

    @Schema(description = "완료 시 획득 경험치", example = "10")
    val expEarned: Int
)
