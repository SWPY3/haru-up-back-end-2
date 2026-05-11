package com.haruUp.global.clova

object DailyMissionFromGoalPrompt {

    /**
     * 목표 기반 일일 미션 생성 시스템 프롬프트
     *
     * 사용자의 목표와 대화 요약을 바탕으로 오늘 실천 가능한 맞춤 미션을 생성합니다.
     */
    const val SYSTEM_PROMPT = """
당신은 사용자의 목표 달성을 돕는 일일 미션 생성 AI입니다.

【절대 규칙 - 반드시 지켜야 합니다】
1. 반드시 정확히 9개의 미션을 생성해야 합니다. 9개 미만은 절대 안 됩니다.
2. difficulty 1 (하) 정확히 3개, difficulty 2 (중) 정확히 3개, difficulty 3 (상) 정확히 3개
3. difficulty 1: 5분~10분이면 할 수 있는 아주 쉬운 미션
4. difficulty 2: 30분~1시간 정도 필요한 보통 미션
5. difficulty 3: 1시간 이상 노력이 필요한 도전적 미션
6. 미션 내용은 10~40자, 한국어로만 작성
7. 반드시 JSON만 출력. 다른 텍스트, 마크다운, 코드블록 절대 금지
8. 사용자 목표와 직접 관련된 미션만 생성

【출력 형식 - 이 형식 그대로 출력】
{"missions":[{"content":"미션1","difficulty":1},{"content":"미션2","difficulty":1},{"content":"미션3","difficulty":1},{"content":"미션4","difficulty":2},{"content":"미션5","difficulty":2},{"content":"미션6","difficulty":2},{"content":"미션7","difficulty":3},{"content":"미션8","difficulty":3},{"content":"미션9","difficulty":3}]}
"""

    /**
     * 일일 미션 생성 사용자 메시지 빌드
     *
     * @param goalText 사용자의 목표 텍스트
     * @param conversationSummary 챗봇 대화 요약
     * @param pastMissions 과거에 이미 제공된 미션 목록 (중복 방지)
     */
    fun buildUserMessage(
        goalText: String,
        conversationSummary: String,
        pastMissions: List<String> = emptyList()
    ): String {
        return buildString {
            append("사용자 목표: $goalText\n\n")
            append("대화 요약:\n$conversationSummary\n\n")
            if (pastMissions.isNotEmpty()) {
                append("【절대 반복 금지 미션 목록】\n")
                append("아래 미션들은 이미 제공된 미션이므로 절대 다시 생성하지 마세요.\n")
                append("내용이 동일하거나 매우 유사한 미션도 금지입니다.\n")
                pastMissions.forEachIndexed { index, mission ->
                    append("${index + 1}. $mission\n")
                }
                append("\n")
            }
            append("위 정보를 바탕으로 오늘 실천 가능한 새로운 맞춤 미션 3~5개를 생성해주세요.")
        }
    }
}
