package com.haruUp.curation.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class GoalValidatorTest {

    @Test
    @DisplayName("목표 1개 응답을 파싱하면 검증을 통과한다")
    fun `단일 목표 파싱`() {
        val raw = """{"goalCount":1,"goals":["체중 5kg 감량"],"requiresTime":true}"""

        val result = GoalValidator.parse(raw)

        assertEquals(1, result.goalCount)
        assertEquals(listOf("체중 5kg 감량"), result.goals)
        assertTrue(result.requiresTime)
        assertTrue(result.isSingleGoal)
    }

    @Test
    @DisplayName("목표가 2개면 검증에 실패한다")
    fun `복수 목표는 탈락`() {
        val raw = """{"goalCount":2,"goals":["다이어트","토익 900점"],"requiresTime":true}"""

        val result = GoalValidator.parse(raw)

        assertEquals(2, result.goalCount)
        assertFalse(result.isSingleGoal)
    }

    @Test
    @DisplayName("시간 투자가 필요 없는 목표는 requiresTime이 false다")
    fun `시간 불필요 목표`() {
        val raw = """{"goalCount":1,"goals":["금연하기"],"requiresTime":false}"""

        val result = GoalValidator.parse(raw)

        assertTrue(result.isSingleGoal)
        assertFalse(result.requiresTime)
    }

    @Test
    @DisplayName("코드블록으로 감싸진 JSON도 파싱한다")
    fun `코드블록 제거 후 파싱`() {
        val raw = "```json\n{\"goalCount\":1,\"goals\":[\"금연하기\"],\"requiresTime\":false}\n```"

        val result = GoalValidator.parse(raw)

        assertEquals(1, result.goalCount)
        assertFalse(result.requiresTime)
    }

    @Test
    @DisplayName("파싱할 수 없는 응답은 사용자를 막지 않도록 단일 목표로 간주한다")
    fun `파싱 실패 시 통과 처리`() {
        val result = GoalValidator.parse("목표가 하나인 것 같습니다")

        assertTrue(result.isSingleGoal)
        assertTrue(result.requiresTime)
    }

    @Test
    @DisplayName("goalCount가 0이어도 사용자를 막지 않는다")
    fun `goalCount 0은 통과 처리`() {
        val result = GoalValidator.parse("""{"goalCount":0,"goals":[],"requiresTime":true}""")

        assertTrue(result.isSingleGoal)
    }
}
