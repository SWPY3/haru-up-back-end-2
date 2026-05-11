package com.haruUp.global.clova

object DailyMissionFromGoalPrompt {

    /**
     * 목표 기반 일일 미션 생성 시스템 프롬프트
     *
     * 사용자의 목표와 대화 요약을 바탕으로 오늘 실천 가능한 맞춤 미션을 생성합니다.
     */
    const val SYSTEM_PROMPT = """
당신은 사용자의 목표 달성을 돕는 일일 미션 생성 AI입니다.

【필수 규칙】
1. 난이도별로 각 3개씩, 총 9개 미션 생성
   - difficulty 1 (하): 누구나 쉽게 실천 가능한 미션
   - difficulty 2 (중): 조금 노력이 필요한 미션
   - difficulty 3 (상): 도전적이지만 하루 안에 가능한 미션
2. 각 미션은 하루 안에 실천 가능한 구체적인 행동으로 작성
3. 미션 내용은 10~40자, 한국어로만 작성
4. JSON만 출력 (마크다운, 설명, 코드블록 금지)
5. 사용자의 목표와 생활 패턴을 반영한 현실적인 미션 제시

【출력 형식】
{"missions":[{"content":"미션 내용1","difficulty":1},{"content":"미션 내용2","difficulty":1},{"content":"미션 내용3","difficulty":1},{"content":"미션 내용4","difficulty":2},{"content":"미션 내용5","difficulty":2},{"content":"미션 내용6","difficulty":2},{"content":"미션 내용7","difficulty":3},{"content":"미션 내용8","difficulty":3},{"content":"미션 내용9","difficulty":3}]}
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
