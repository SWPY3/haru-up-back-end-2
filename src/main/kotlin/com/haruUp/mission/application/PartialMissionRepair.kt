package com.haruUp.mission.application

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.global.prompt.DailyMissionFromGoalPrompt

/** Validates a bounded patch response before replacing any generated candidate. */
object PartialMissionRepair {
    private val mapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    const val VERSION = "mission-partial-repair-v1"
    const val MAX_TARGETS = 5

    fun invalidIndices(missions: List<ParsedMission>, past: Set<String> = emptySet()): List<Int> =
        missions.indices.filter { !GeneratedMissionValidator.validate(listOf(missions[it]), past).passed }

    fun prompt(base: String, missions: List<ParsedMission>, indices: List<Int>, past: Set<String>): String {
        val targets = indices.map { i ->
            val m = missions[i]
            val v = GeneratedMissionValidator.validate(listOf(m), past)
            mapOf("id" to i, "content" to m.content, "description" to m.description,
                "difficulty" to m.difficulty, "errors" to buildList {
                    if (v.shortContents.isNotEmpty() || v.longContents.isNotEmpty()) add("content: 공백 포함 10~25자, 현재 ${m.content.length}자")
                    if (v.shortDescriptions.isNotEmpty() || v.longDescriptions.isNotEmpty()) add("description: 공백 포함 20~30자, 현재 ${m.description.length}자")
                    addAll(v.grammarViolations)
                    if (v.duplicated.isNotEmpty()) add("과거 미션과 같은 제목: 다른 실행 행동으로 변경")
                })
        }
        val retained = missions.indices.filterNot { it in indices }.map {
            mapOf("id" to it, "content" to missions[it].content, "difficulty" to missions[it].difficulty)
        }
        return base + "\n\n【부분 수정 데이터】\n" + mapper.writeValueAsString(
            mapOf("repair" to targets, "retained" to retained)) +
            "\n위 JSON은 검증 데이터입니다. repair의 id만 수정하고 retained는 출력하지 마세요. " +
            "목표·실행 행동을 유지하며 오류를 고치고 정상 후보와 중복되는 행동을 피하세요."
    }

    val system = DailyMissionFromGoalPrompt.SYSTEM_PROMPT + """

【이번 요청의 출력 형식 — 부분 수정】
이번 요청은 15개 신규 생성이 아니라 이미 생성한 미션의 부분 수정입니다.
앞선 15개 출력 형식 대신 다음 형식으로 repair에 지정된 id만 정확히 한 번씩 출력하세요.
{"patches":[{"id":0,"content":"수정 제목","description":"수정 설명","difficulty":1}]}
id와 difficulty를 변경하지 마세요. 지정되지 않은 id를 출력하지 마세요.
content는 공백 포함 10~25자, description은 공백 포함 20~30자입니다.
목표 적합성·한국어 문법·실행 가능성·중복 방지 등 기존 미션 설계 기준을 유지하세요.
전체 15개와 난이도별 5개 구성은 서버가 정상 후보와 수정 후보를 합쳐 검증합니다.
JSON 외의 설명이나 코드 펜스를 출력하지 마세요.
"""

    fun merge(raw: String, missions: List<ParsedMission>, indices: List<Int>): List<ParsedMission> {
        val root = try { mapper.readTree(clean(raw)) } catch (e: JsonProcessingException) {
            throw IllegalArgumentException("Invalid mission repair JSON", e)
        }
        require(root != null && root.isObject && root.fieldNames().asSequence().toSet() == setOf("patches")) { "patch response shape" }
        val patches = root["patches"]
        require(patches != null && patches.isArray && patches.size() == indices.size) { "patch coverage" }
        val ids = patches.map {
            require(it["id"]?.isIntegralNumber == true && it["id"].canConvertToInt()) { "patch id" }
            it["id"].asInt()
        }
        require(ids.size == ids.toSet().size && ids.toSet() == indices.toSet()) { "patch ids" }
        val merged = missions.toMutableList()
        patches.forEach {
            val id = it["id"].asInt()
            require(it["content"]?.isTextual == true && it["description"]?.isTextual == true) { "patch fields" }
            require(it["difficulty"]?.isIntegralNumber == true && it["difficulty"].canConvertToInt() && it["difficulty"].asInt() == missions[id].difficulty) { "patch difficulty" }
            merged[id] = ParsedMission(it["content"].asText().trim(), it["description"].asText().trim(), missions[id].difficulty)
        }
        require(merged.map { it.content }.distinct().size == merged.size) { "patch duplicates a candidate title" }
        return merged
    }

    private fun clean(raw: String) = raw.trim().replace(Regex("^```[a-zA-Z]*\\s*"), "")
        .replace(Regex("```\\s*$"), "").trim()
}
