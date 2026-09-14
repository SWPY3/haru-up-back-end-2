package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 큐레이션 대화 요약 응답을 용도별 요약 2개로 파싱한다.
 *
 * - detailed: 미션 생성 프롬프트에 넣는 상세 요약 (사용자에게 보이지 않음)
 * - brief: 사용자 화면에 그대로 노출되는 짧은 요약
 *
 * 요약이 없다고 큐레이션을 실패시킬 수는 없으므로, 파싱이 어긋나도 항상 값을 채워 반환한다.
 */
object ConversationSummaryParser {

    /** 요약을 전혀 얻지 못했을 때 사용할 문구 */
    const val FALLBACK_SUMMARY = "목표 달성을 위한 대화를 완료했습니다."

    /**
     * brief 최대 길이. 프롬프트(ChatbotSummaryPrompt)의 기준(80자)에 여유를 둔 값.
     *
     * 검증에서 초과한 4건은 81·83·83·96자였다. 1~3자 초과는 화면에서 문제가 되지 않는데
     * 재생성(추가 API 호출)을 유발하므로, 꼬리질문(45⇒60자)과 같은 방식으로 여유를 둔다.
     * 다만 brief는 사용자에게 그대로 노출되므로 꼬리질문보다 여유를 좁게 잡았다.
     */
    const val MAX_BRIEF_LENGTH = 90

    private val objectMapper = ObjectMapper()

    /**
     * 용도별 요약 한 쌍
     *
     * @param detailed 미션 생성용 상세 요약
     * @param brief 사용자 노출용 짧은 요약
     */
    data class ConversationSummaries(
        val detailed: String,
        val brief: String
    )

    /**
     * 모델 응답을 파싱한다.
     * JSON이 아니면 전체를 두 요약 모두로 쓰고, brief만 없으면 detailed로 채운다.
     */
    fun parse(raw: String): ConversationSummaries {
        val cleaned = stripArtifacts(raw)
        if (cleaned.isBlank()) {
            return ConversationSummaries(FALLBACK_SUMMARY, FALLBACK_SUMMARY)
        }

        return try {
            val node = objectMapper.readTree(cleaned)
            val detailed = node.get("detailed")?.asText()?.trim()?.takeIf { it.isNotBlank() }
            val brief = node.get("brief")?.asText()?.trim()?.takeIf { it.isNotBlank() }

            when {
                detailed == null && brief == null -> plainText(cleaned)
                // 한쪽만 나온 경우 나머지를 같은 값으로 채운다. 요약이 비는 것보다 낫다.
                detailed == null -> ConversationSummaries(brief!!, brief)
                brief == null -> ConversationSummaries(detailed, detailed)
                else -> ConversationSummaries(detailed, brief)
            }
        } catch (e: Exception) {
            plainText(cleaned)
        }
    }

    /**
     * 사용자 노출용 brief 요약의 위반 사유를 반환한다. 문제가 없으면 null을 반환한다.
     *
     * detailed는 미션 생성 프롬프트에만 들어가고 사용자가 보지 않으므로 길이를 검사하지 않는다.
     * 파싱 실패로 채운 [FALLBACK_SUMMARY]는 재생성해도 나아지지 않으므로 검사 대상이 아니다.
     */
    fun findBriefViolation(summaries: ConversationSummaries): String? {
        val brief = summaries.brief
        if (brief == FALLBACK_SUMMARY) return null

        if (brief.length > MAX_BRIEF_LENGTH) {
            return "brief 요약이 ${brief.length}자입니다. 공백 포함 80자 이내로 줄이세요."
        }

        return null
    }

    /** JSON이 아닌 평문으로 나온 경우 전체를 두 요약 모두로 사용한다. */
    private fun plainText(text: String) = ConversationSummaries(text, text)

    /**
     * 모델이 덧붙이는 코드블록과 마크다운을 제거한다.
     */
    private fun stripArtifacts(raw: String): String {
        return raw
            .replace(Regex("^```[a-zA-Z]*\\s*"), "")
            .replace(Regex("```\\s*$"), "")
            .replace("**", "")
            .trim()
    }
}
