package com.haruUp.global.util

/**
 * AI가 생성한 한국어 문장에서 명백한 문법 오류만 코드 레벨로 걸러내는 검사기.
 *
 * 프롬프트의 자체 검수만으로는 문법 오류가 새어 나오기 때문에,
 * 사용자에게 그대로 노출되는 문장(챗봇 질문, 미션 제목/설명)에 대해
 * 확실히 잘못된 패턴만 탐지해 재생성 트리거로 사용한다.
 * 오탐(정상 문장을 오류로 판단)을 막기 위해 애매한 규칙은 의도적으로 넣지 않는다.
 */
object KoreanGrammarChecker {

    /** 단독 자음/모음 (예: ㅋㅋㅋ, ㄱㄱ) */
    private val LONE_JAMO_REGEX = Regex("[ㄱ-ㅎㅏ-ㅣ]")

    /** 같은 한글 음절 3회 이상 반복 (예: 하하하). 숫자 반복("10000보")은 정상이므로 제외한다. */
    private val REPEATED_CHAR_REGEX = Regex("([가-힣])\\1{2,}")

    /**
     * 조사로 끝나 서술이 잘린 경우 (예: "핵심 패턴 5개를")
     * '요가', '회의', '바로'처럼 단어의 마지막 글자가 조사와 겹치는 오탐을 피하기 위해
     * 조사로만 쓰이는 형태만 검사하고, '을'은 '가을/마을'을 제외하고 판단한다.
     */
    private val TRAILING_PARTICLE_REGEX = Regex("(으로|에서|에게|부터|까지|를|은|는|(?<![가마])을)$")

    /** 의존명사 '수' 붙여쓰기 오류 (단, '할수록' 같은 정상 표기는 제외) */
    private val SPACING_ERROR_REGEX = Regex("(할|볼|될|쓸|갈|올|만들)수(?!록)")

    /** 문장 끝의 문장부호 (조사 종결 판단 전에 제거) */
    private val TRAILING_PUNCTUATION_REGEX = Regex("[?!.…\\s]+$")

    /**
     * 사용자에게 노출되는 한국어 문장의 문법 위반 사유를 반환한다.
     *
     * @return 위반 사유 문자열, 문제가 없으면 null
     */
    fun findViolation(text: String): String? {
        val trimmed = text.trim()

        if (trimmed.isBlank()) {
            return "빈 문장입니다."
        }

        if (LONE_JAMO_REGEX.containsMatchIn(trimmed)) {
            return "단독 자음/모음이 포함되어 있습니다."
        }

        if (REPEATED_CHAR_REGEX.containsMatchIn(trimmed)) {
            return "같은 문자가 3회 이상 반복됩니다."
        }

        SPACING_ERROR_REGEX.find(trimmed)?.let {
            return "띄어쓰기 오류가 있습니다. (\"${it.value}\")"
        }

        val withoutPunctuation = trimmed.replace(TRAILING_PUNCTUATION_REGEX, "")
        TRAILING_PARTICLE_REGEX.find(withoutPunctuation)?.let {
            return "조사 \"${it.value}\"로 문장이 끝나 서술이 잘렸습니다."
        }

        return null
    }
}
