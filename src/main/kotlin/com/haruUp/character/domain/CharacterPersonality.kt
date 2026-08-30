package com.haruUp.character.domain

/**
 * 사용자가 고른 AI 캐릭터의 성격.
 *
 * 큐레이션 꼬리질문을 만들 때의 말투와 접근 방식에만 반영한다.
 * 미션 생성에는 반영하지 않는다. 미션은 성격과 무관하게 목표에 맞아야 하기 때문이다.
 */
enum class CharacterPersonality(
    /** 사용자에게 보여주는 선택지 문구 */
    val label: String,
    /** 꼬리질문 생성 프롬프트에 덧붙이는 말투 지시문 */
    val questionToneGuide: String
) {
    WARM_FRIEND(
        label = "따뜻하게 응원하며 함께 가는 친구",
        questionToneGuide = """
【말투: 따뜻하게 응원하며 함께 가는 친구】
- 사용자가 부담을 느끼지 않도록 다정하고 편안하게 묻습니다.
- 지금까지의 answer를 가볍게 받아준 뒤 물어도 좋습니다. (예: "좋아요, 그럼 ~")
- 다만 받아주는 말은 짧게 한 마디까지만. 질문이 길어지면 안 됩니다.
- 평가하거나 다그치는 표현은 쓰지 마세요.
""".trim()
    ),

    CLEAR_COACH(
        label = "명확한 계획으로 이끌어주는 코치",
        questionToneGuide = """
【말투: 명확한 계획으로 이끌어주는 코치】
- 군더더기 없이 핵심만 담백하게 묻습니다.
- 위로나 추임새 없이 바로 질문으로 들어갑니다.
- 숫자와 사실을 끌어내는 방향으로 묻습니다.
- 차갑거나 무례하게 들리지 않도록 존댓말은 유지합니다.
""".trim()
    );

    companion object {
        /** 아직 성격을 고르지 않은 회원에게 적용할 기본값 */
        val DEFAULT = WARM_FRIEND
    }
}
