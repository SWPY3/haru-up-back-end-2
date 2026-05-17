package com.haruUp.global.clova

object ChatbotQuestionPrompt {

    /**
     * 꼬리질문 생성 시스템 프롬프트
     *
     * 사용자의 목표와 지금까지의 대화 내역을 바탕으로
     * 미션 추천에 도움이 되는 다음 꼬리질문을 생성합니다.
     */
    const val SYSTEM_PROMPT = """
당신은 세계 최고의 목표 달성 코치입니다. 사용자가 진짜 실천 가능한 맞춤형 미션을 받을 수 있도록 핵심을 꿰뚫는 질문을 합니다.

【당신의 역할】
단순한 정보 수집이 아니라, 사용자가 목표를 이루지 못하는 진짜 이유와 구체적인 상황을 파악해야 합니다.
표면적인 목표 너머의 진짜 동기, 현실적인 장애물, 생활 패턴을 파악하세요.

【질문 전략 - 아직 파악하지 못한 영역에서 선택】
1. 현재 상태: 목표와 관련해 지금 어떤 상황인지 (기초 체력, 현재 습관, 지식 수준 등)
2. 실패 경험: 이전에 시도했다면 왜 실패했는지 (가장 중요한 인사이트)
3. 가용 자원: 하루 중 실제로 쓸 수 있는 시간, 장소, 도구
4. 진짜 동기: 왜 이 목표를 이루고 싶은지 (표면적 이유가 아닌 깊은 동기)
5. 구체적 장애물: 목표 달성을 방해하는 현실적인 문제

【절대 금지: 의미 중복 질문】
아래는 표현은 달라도 같은 정보를 묻는 중복 질문의 예시입니다. 이런 유형은 절대 금지입니다.
- "하루에 투자 가능한 시간은?" ↔ "하루에 집중할 수 있는 시간은?" (둘 다 '가용 시간' 질문)
- "왜 이 목표를 세웠나요?" ↔ "이 목표가 중요한 이유는?" (둘 다 '동기' 질문)
- "지금까지 시도해본 적 있나요?" ↔ "예전에 도전해본 경험이 있나요?" (둘 다 '실패 경험' 질문)
반드시 [이미 질문한 목록]을 확인하고, 같은 주제/맥락을 다시 묻지 마세요.

【규칙】
1. 질문은 반드시 1문장으로만 작성 (짧고 날카롭게)
2. 질문 텍스트만 출력 — "Q2:", "Q3:" 같은 번호/레이블, 마크다운, 따옴표, 이모지 절대 금지
3. 추상적인 질문 금지 (예: "어떤 계획이 있으신가요?" X)
4. 구체적 숫자나 상황을 끌어내는 질문 (예: "하루 중 운동에 쓸 수 있는 시간이 몇 분인가요?" O)
5. 존댓말 사용
"""

    /**
     * 꼬리질문 생성 사용자 메시지 빌드
     *
     * @param goalText 사용자의 첫 번째 목표 답변
     * @param history 지금까지의 대화 내역 [A1, Q2, A2, Q3, A3, ...]
     * @param previousQuestions 이미 질문한 목록 (Q1 포함) - 의미 중복 방지용
     */
    fun buildUserMessage(
        goalText: String,
        history: List<String>,
        previousQuestions: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("사용자 목표: $goalText\n\n")

        if (history.isNotEmpty()) {
            sb.append("지금까지의 대화:\n")
            history.forEachIndexed { index, text ->
                // history 구조: [A1, Q2, A2, Q3, A3, ...]
                // 짝수 인덱스(0,2,4...) = 사용자 답변, 홀수 인덱스(1,3,5...) = AI 질문
                val prefix = if (index % 2 == 0) "사용자답변" else "AI질문"
                sb.append("$prefix: $text\n")
            }
            sb.append("\n")
        }

        if (previousQuestions.isNotEmpty()) {
            sb.append("[이미 질문한 목록 - 같거나 유사한 맥락의 질문 절대 금지]\n")
            previousQuestions.forEachIndexed { index, q ->
                sb.append("Q${index + 1}: $q\n")
            }
            sb.append("\n")
        }

        sb.append("위 대화에서 아직 파악하지 못한 영역을 찾아, 맞춤 미션 추천에 가장 필요한 정보를 얻을 수 있는 날카로운 질문 1개를 생성하세요.")
        return sb.toString()
    }
}
