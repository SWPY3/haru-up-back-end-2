package com.haruUp.mission.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PartialMissionRepairEvaluationSupportTest {
    private val mapper = jacksonObjectMapper()
    private val valid = (1..3).flatMap { difficulty -> (1..5).map { index ->
        ParsedMission("영어 문장 ${difficulty * 5 + index}번 따라 읽기", "교재에서 영어 문장 ${index}번을 골라 소리 내어 읽기", difficulty)
    } }
    private fun patch(id: Int, m: ParsedMission) = mapOf("id" to id, "content" to m.content,
        "description" to m.description, "difficulty" to m.difficulty)
    private fun response(patches: List<Map<String, Any>>) = mapper.writeValueAsString(mapOf("patches" to patches))

    @Test
    fun `repair retains every normal candidate and revalidates the whole set`() {
        val failed = valid.mapIndexed { i, m -> if (i in listOf(0, 5)) m.copy(description = "짧은 설명") else m }
        val indices = PartialMissionRepairEvaluationSupport.invalidIndices(failed)
        assertEquals(listOf(0, 5), indices)
        val merged = PartialMissionRepairEvaluationSupport.merge(response(indices.map { patch(it, valid[it]) }), failed, indices)
        assertEquals(valid, merged)
        assertTrue(PartialMissionRepairEvaluationSupport.passed(merged))
        val stillInvalid = PartialMissionRepairEvaluationSupport.merge(response(indices.map { patch(it, failed[it]) }), failed, indices)
        assertFalse(PartialMissionRepairEvaluationSupport.passed(stillInvalid))
    }

    @Test
    fun `patch cannot overwrite normal candidates omit ids duplicate ids or change difficulty`() {
        val variants = listOf(
            listOf(patch(1, valid[1])), emptyList(), listOf(patch(0, valid[0]), patch(0, valid[0])),
            listOf(patch(0, valid[0].copy(difficulty = 3)))
        )
        variants.forEach { patches -> assertThrows(IllegalArgumentException::class.java) {
            PartialMissionRepairEvaluationSupport.merge(response(patches), valid, listOf(0))
        } }
        assertEquals(15, valid.size)
    }
}
