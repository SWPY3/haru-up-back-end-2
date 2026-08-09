package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.global.util.KoreanGrammarChecker

/**
 * 큐레이션 챗봇이 생성한 꼬리질문과 예시 답변을 정제하고 검증한다.
 *
 * 검증 기준
 * 1. 한 질문에서는 한 가지 정보만 묻는다. (물음표 1개, 나열·병렬 표현 금지)
 * 2. 사용자가 30자 이내로 답할 수 있어야 한다. (서술형 요구 금지 + 짧은 예시 답변 3개 제공)
 * 3. 한국어 문법이 올바라야 한다. ([KoreanGrammarChecker])
 */
object ChatbotQuestionValidator {

    /** 질문 최대 길이. 프롬프트(ChatbotQuestionPrompt)의 기준(45자)에 여유를 둔 값 */
    const val MAX_QUESTION_LENGTH = 60

    /** 사용자가 그대로 골라 답할 수 있도록 제시하는 예시 답변 수 */
    const val REQUIRED_EXAMPLE_COUNT = 3

    /** 예시 답변 최대 길이. 프롬프트 기준(15자)에 여유를 둔 값 */
    const val MAX_EXAMPLE_LENGTH = 20

    private val objectMapper = ObjectMapper()

    /** 한 질문에서 두 가지 이상을 묻는 나열·병렬 표현 */
    private val MULTI_INFO_PATTERNS = listOf(
        Regex("그리고"),
        Regex("또한"),
        Regex("아울러"),
        Regex("[가-힣] 및 "),
        Regex("[가-힣]고,\\s"),          // "몇 분이고, 언제~"
        Regex("[가-힣]며,\\s"),          // "얼마이며, 언제~"
        Regex("인지\\s.*인지"),          // "A인지 B인지"
        Regex("각각")
    )

    /** 답변이 30자를 넘기게 만드는 서술형 요구 표현 */
    private val LONG_ANSWER_PATTERNS = listOf(
        "설명해", "이야기해", "얘기해", "알려주세요", "말씀해",
        "어떤 점들", "어떤 것들", "무엇무엇", "어떻게 생각", "자세히", "구체적으로 적어"
    )

    /** 파싱된 꼬리질문 */
    data class ParsedQuestion(
        val question: String,
        val examples: List<String>
    )

    /**
     * 모델 응답을 파싱한다. JSON 형식이 아니면 전체를 질문으로 간주하고 예시 답변은 비운다.
     * (검증 단계에서 예시 답변 누락으로 걸러져 재생성된다.)
     */
    fun parse(raw: String): ParsedQuestion {
        val cleaned = stripArtifacts(raw)

        return try {
            val node = objectMapper.readTree(cleaned)
            val question = node.get("question")?.asText()?.let { cleanQuestionText(it) }
            if (question.isNullOrBlank()) {
                ParsedQuestion(cleanQuestionText(cleaned), emptyList())
            } else {
                val examples = node.get("examples")
                    ?.mapNotNull { it.asText()?.trim()?.trim('"')?.takeIf { text -> text.isNotBlank() } }
                    ?: emptyList()
                ParsedQuestion(question, examples)
            }
        } catch (e: Exception) {
            ParsedQuestion(cleanQuestionText(cleaned), emptyList())
        }
    }

    /**
     * 질문이 여러 개 붙어 나온 경우 첫 번째 질문까지만 남긴다.
     */
    fun takeFirstQuestion(text: String): String {
        val firstMark = text.indexOf('?')
        return if (firstMark >= 0) text.substring(0, firstMark + 1).trim() else text
    }

    /**
     * 질문과 예시 답변의 위반 사유를 반환한다. 문제가 없으면 null을 반환한다.
     */
    fun findViolation(parsed: ParsedQuestion): String? {
        findQuestionViolation(parsed.question)?.let { return it }
        return findExamplesViolation(parsed.examples)
    }

    private fun findQuestionViolation(question: String): String? {
        KoreanGrammarChecker.findViolation(question)?.let { return it }

        if (!question.endsWith("?")) {
            return "물음표로 끝나는 질문 형태가 아닙니다."
        }

        val questionMarkCount = question.count { it == '?' }
        if (questionMarkCount > 1) {
            return "한 번에 ${questionMarkCount}개를 묻고 있습니다. 질문은 1개(물음표 1개)여야 합니다."
        }

        MULTI_INFO_PATTERNS.find { it.containsMatchIn(question) }?.let {
            return "두 가지 이상을 함께 묻는 나열 표현이 포함되어 있습니다."
        }

        LONG_ANSWER_PATTERNS.find { question.contains(it) }?.let {
            return "\"$it\"는 사용자가 30자 이내로 답하기 어려운 서술형 요구입니다."
        }

        if (question.length > MAX_QUESTION_LENGTH) {
            return "질문이 ${question.length}자로 너무 깁니다. ${MAX_QUESTION_LENGTH}자 이내로 줄이세요."
        }

        return null
    }

    private fun findExamplesViolation(examples: List<String>): String? {
        if (examples.size != REQUIRED_EXAMPLE_COUNT) {
            return "예시 답변이 ${examples.size}개입니다. 정확히 ${REQUIRED_EXAMPLE_COUNT}개여야 합니다."
        }

        if (examples.distinct().size != examples.size) {
            return "예시 답변에 같은 내용이 중복되어 있습니다."
        }

        examples.find { it.length > MAX_EXAMPLE_LENGTH }?.let {
            return "예시 답변 \"$it\"이(가) ${it.length}자로 깁니다. ${MAX_EXAMPLE_LENGTH}자 이내여야 합니다."
        }

        examples.find { it.endsWith("?") }?.let {
            return "예시 답변 \"$it\"이(가) 질문 형태입니다. 답변 형태여야 합니다."
        }

        examples.forEach { example ->
            KoreanGrammarChecker.findViolation(example)?.let {
                return "예시 답변 \"$example\" - $it"
            }
        }

        return null
    }

    /**
     * 모델이 덧붙이는 코드블록, 번호 접두사("Q2:"), 마크다운을 제거한다.
     */
    private fun stripArtifacts(raw: String): String {
        return raw
            .replace(Regex("^```[a-zA-Z]*\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .replace("**", "")
            .trim()
    }

    /**
     * JSON이 아닌 평문으로 나온 경우를 대비해 질문 텍스트를 정리한다.
     */
    private fun cleanQuestionText(text: String): String {
        return text
            .replace(Regex("^Q\\d+[:.\\s]+"), "")
            .replace(Regex("^[-*]\\s+"), "")
            .trim()
            .trim('"', '\'', '「', '」', '“', '”')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
