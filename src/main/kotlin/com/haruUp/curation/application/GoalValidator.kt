package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 사용자가 입력한 목표에 대한 AI 분석 결과를 파싱한다.
 *
 * 판정 기준
 * 1. 목표가 하나인지 - 두 개 이상이면 하나만 입력하도록 되돌린다.
 * 2. 시간 투자가 필요한 목표인지 - 필요하면 이후 투자 가능 시간을 따로 묻는다.
 *
 * 분석에 실패하면 사용자를 막지 않는 쪽(단일 목표 + 시간 필요)으로 처리한다.
 * 잘못된 차단은 사용자를 큐레이션에서 이탈시키지만, 잘못된 통과는 질문이 한 번 더 나가는 정도의 비용이다.
 */
object GoalValidator {

    /** 목표가 여러 개일 때 사용자에게 보여줄 안내 문구 */
    const val MULTIPLE_GOAL_MESSAGE = "목표를 하나만 입력해주세요!"

    private val objectMapper = ObjectMapper()

    /**
     * 목표 분석 결과
     *
     * @param goalCount 발견한 목표 수
     * @param goals 발견한 목표를 짧은 명사구로 나열한 목록
     * @param requiresTime 하루 중 일정 시간을 떼어 써야 이룰 수 있는 목표인지
     */
    data class GoalAnalysis(
        val goalCount: Int,
        val goals: List<String>,
        val requiresTime: Boolean
    ) {
        /** 목표가 하나인지. 0개로 판정된 경우도 사용자를 막지 않고 통과시킨다. */
        val isSingleGoal: Boolean
            get() = goalCount <= 1
    }

    /**
     * 모델 응답을 파싱한다. JSON이 아니거나 필드가 없으면 통과 처리한다.
     */
    fun parse(raw: String): GoalAnalysis {
        val cleaned = stripArtifacts(raw)

        return try {
            val node = objectMapper.readTree(cleaned)
            val goalCount = node.get("goalCount")?.takeIf { it.isNumber }?.asInt()
                ?: return PASS_THROUGH
            val goals = node.get("goals")
                ?.mapNotNull { it.asText()?.trim()?.takeIf { text -> text.isNotBlank() } }
                ?: emptyList()
            val requiresTime = node.get("requiresTime")?.takeIf { it.isBoolean }?.asBoolean() ?: true

            GoalAnalysis(goalCount = goalCount, goals = goals, requiresTime = requiresTime)
        } catch (e: Exception) {
            PASS_THROUGH
        }
    }

    /** 분석 실패 시 사용할 기본값 - 사용자를 막지 않고 시간 질문은 유지한다. */
    private val PASS_THROUGH = GoalAnalysis(goalCount = 1, goals = emptyList(), requiresTime = true)

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
