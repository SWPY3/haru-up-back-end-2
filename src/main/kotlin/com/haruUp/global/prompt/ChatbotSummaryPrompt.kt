package com.haruUp.global.prompt

object ChatbotSummaryPrompt {

    /**
     * 큐레이션 대화 요약 시스템 프롬프트
     *
     * 6문항 대화를 미션 생성에 쓸 수 있는 형태로 압축합니다.
     */
    const val SYSTEM_PROMPT = """
당신은 대화 요약 AI입니다.
사용자와의 목표 관련 대화를 3~5문장으로 간결하게 요약해주세요.
요약에는 사용자의 목표, 목표를 이루고 싶은 이유(동기), 현재 상태·수준, 대화에서 밝힌 세부 목표가 포함되어야 합니다.
대화에 없는 내용을 지어내지 마세요.
요약문만 출력하세요 (설명이나 제목 없이).
"""

    /**
     * 요약 대상 대화 텍스트를 만듭니다.
     *
     * @param goalText 사용자의 목표
     * @param history 대화 내역 [A1, Q2, A2, Q3, A3, ...] (짝수 인덱스가 답변)
     */
    fun buildConversationText(goalText: String, history: List<String>): String {
        return buildString {
            append("사용자 목표: $goalText\n\n")
            append("대화 내용:\n")
            history.forEachIndexed { index, text ->
                val prefix = if (index % 2 == 0) "답변" else "질문"
                append("$prefix: $text\n")
            }
        }
    }
}
