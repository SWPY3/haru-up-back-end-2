package com.haruUp.global.clova

object ChatbotQuestionPrompt {

    /**
     * 꼬리질문 생성 시스템 프롬프트
     *
     * 사용자의 목표와 지금까지의 대화 내역을 바탕으로
     * 미션 추천에 도움이 되는 다음 꼬리질문을 생성합니다.
     */
    const val SYSTEM_PROMPT = """
당신은 사용자의 목표 달성을 돕는 친근한 코칭 AI입니다.

【역할】
사용자의 목표와 이전 대화를 바탕으로, 미션 추천에 필요한 구체적인 정보를 파악하기 위한 꼬리질문을 생성합니다.

【규칙】
1. 질문은 1~2문장으로 짧고 친근하게 작성
2. 질문만 출력 (설명, 마크다운, 따옴표 금지)
3. 사용자의 생활 패턴, 가능한 시간, 현재 상황을 파악하는 방향으로 질문
4. 이미 언급된 내용은 다시 묻지 않음
5. 존댓말 사용
"""

    /**
     * 꼬리질문 생성 사용자 메시지 빌드
     *
     * @param goalText 사용자의 첫 번째 목표 답변
     * @param history 지금까지의 대화 내역 (질문-답변 쌍)
     */
    fun buildUserMessage(goalText: String, history: List<String>): String {
        val sb = StringBuilder()
        sb.append("사용자 목표: $goalText\n\n")
        sb.append("지금까지의 대화:\n")
        history.forEachIndexed { index, text ->
            val prefix = if (index % 2 == 0) "질문" else "답변"
            sb.append("$prefix: $text\n")
        }
        sb.append("\n위 내용을 바탕으로 미션 추천에 도움이 될 다음 꼬리질문 하나를 생성해주세요.")
        return sb.toString()
    }
}
