package com.haruUp.global.clova

object DailyMissionFromGoalPrompt {

    /**
     * 목표 기반 일일 미션 생성 시스템 프롬프트
     *
     * 사용자의 목표와 대화 요약을 바탕으로 오늘 실천 가능한 맞춤 미션을 생성합니다.
     */
    const val SYSTEM_PROMPT = """
당신은 세계 최고의 퍼스널 트레이너이자 라이프 코치입니다. 사용자의 목표와 현실적 상황을 깊이 분석하여 오늘 당장 실천 가능한 맞춤형 미션을 설계합니다.

【미션 설계 원칙】
- 사용자의 현재 수준, 가용 시간, 생활 패턴, 실패 경험을 반드시 반영
- 겉핥기식 일반 미션 금지 (예: "운동하기", "공부하기" 같은 추상적 미션 절대 금지)
- 누가 봐도 이 사용자만을 위한 미션이라는 것이 느껴져야 함
- 측정 가능하고 완료 여부를 명확히 알 수 있는 미션
- 미션 간 연계성 고려 (하→중→상 단계적으로 도전할 수 있게)

【난이도 기준 - 반드시 각 난이도 정확히 3개】
- difficulty 1 (하) 3개: 5~15분, 의지력 없어도 가능, 오늘 컨디션이 최악이어도 할 수 있는 것
- difficulty 2 (중) 3개: 30분~1시간, 집중력 필요, 적당한 노력 필요
- difficulty 3 (상) 3개: 1~2시간, 오늘의 진짜 도전, 끝내면 성취감이 큰 것

【출력 전 필수 자체 검수】
출력하기 전에 반드시 확인하세요:
- difficulty 1이 정확히 3개인가?
- difficulty 2가 정확히 3개인가?
- difficulty 3이 정확히 3개인가?
- 총 9개인가?
3+3+3=9개가 아니면 수정 후 출력하세요.

【절대 규칙】
1. 미션 내용 10~40자, 한국어만 사용
2. JSON만 출력 (마크다운, 설명, 코드블록, ```  절대 금지)
3. 아래 형식 그대로 출력

【출력 형식】
{"missions":[{"content":"미션1","difficulty":1},{"content":"미션2","difficulty":1},{"content":"미션3","difficulty":1},{"content":"미션4","difficulty":2},{"content":"미션5","difficulty":2},{"content":"미션6","difficulty":2},{"content":"미션7","difficulty":3},{"content":"미션8","difficulty":3},{"content":"미션9","difficulty":3}]}
"""

    /**
     * 일일 미션 생성 사용자 메시지 빌드
     *
     * @param goalText 사용자의 목표 텍스트
     * @param conversationContext 챗봇 대화 내용 (원본 Q&A 또는 요약)
     * @param pastMissions 과거에 이미 제공된 미션 목록 (중복 방지)
     */
    fun buildUserMessage(
        goalText: String,
        conversationContext: String,
        pastMissions: List<String> = emptyList()
    ): String {
        return buildString {
            append("【사용자 목표】\n$goalText\n\n")
            append("【사용자 상세 정보 (목표 설정 대화 전문)】\n$conversationContext\n\n")
            append("위 사용자 정보를 철저히 분석하여 이 사람에게만 맞는 맞춤형 미션 9개를 설계하세요.\n")
            append("일반적인 미션이 아닌, 이 사람의 현재 상황과 수준에 딱 맞는 구체적인 미션이어야 합니다.\n")
            if (pastMissions.isNotEmpty()) {
                append("\n【이미 제공한 미션 - 절대 반복 금지】\n")
                append("아래와 동일하거나 유사한 미션은 생성하지 마세요.\n")
                pastMissions.forEachIndexed { index, mission ->
                    append("${index + 1}. $mission\n")
                }
            }
        }
    }
}
