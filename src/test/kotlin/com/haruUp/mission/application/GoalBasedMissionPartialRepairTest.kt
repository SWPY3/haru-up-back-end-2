package com.haruUp.mission.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.openai.OpenAiUsageContext
import com.haruUp.global.openai.OpenAiUsageTracking
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.infrastructure.MemberMissionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class GoalBasedMissionPartialRepairTest {
    private val mapper = jacksonObjectMapper()
    private val valid = (1..3).flatMap { difficulty -> (1..5).map { index ->
        ParsedMission("영어 문장 ${difficulty * 5 + index}번 따라 읽기", "교재에서 영어 문장 ${index}번을 골라 소리 내어 읽기", difficulty)
    } }
    private fun failed(ids: List<Int>) = valid.mapIndexed { i, m -> if (i in ids) m.copy(description = "짧은 설명") else m }
    private fun full(missions: List<ParsedMission>) = response(mapper.writeValueAsString(mapOf("missions" to missions)))
    private fun patch(id: Int, mission: ParsedMission = valid[id]) = mapOf("id" to id, "content" to mission.content,
        "description" to mission.description, "difficulty" to mission.difficulty)
    private fun patches(vararg values: Map<String, Any>) = response(mapper.writeValueAsString(mapOf("patches" to values.toList())))
    private fun response(text: String) = withSuccess(mapper.writeValueAsString(mapOf("output_text" to text,
        "model" to "gpt-5.4-mini", "service_tier" to "default", "usage" to mapOf("input_tokens" to 100,
            "input_tokens_details" to mapOf("cached_tokens" to 0), "output_tokens" to 50))), MediaType.APPLICATION_JSON)

    @Test
    fun `first request stays unchanged and only failed candidates are replaced`() {
        val run = run(listOf(full(failed(listOf(0, 5))), patches(patch(0), patch(5))))
        assertEquals(valid, run.saved.map { ParsedMission(it.missionContent, it.missionDescription.orEmpty(), it.difficulty ?: 0) })
        assertEquals(listOf(0, 5), targets(run.requests[1]))
        assertFalse(run.inputs[0].contains("부분 수정 데이터"))
        assertTrue(run.inputs[1].startsWith(run.inputs[0]))
        assertTrue(run.requests[1]["instructions"].asText().contains("부분 수정"))
        assertTrue(run.requests.all { it["max_output_tokens"].asInt() == 4096 })
        assertEquals(run.requests[0]["model"], run.requests[1]["model"])
    }

    @Test
    fun `third attempt repairs only the remaining invalid candidate`() {
        val stillBad = valid[5].copy(description = "짧은 설명")
        val run = run(listOf(full(failed(listOf(0, 5))), patches(patch(0), patch(5, stillBad)), patches(patch(5))))
        assertEquals(listOf(0, 5), targets(run.requests[1]))
        assertEquals(listOf(5), targets(run.requests[2]))
        assertEquals(1, Regex("부분 수정 데이터").findAll(run.inputs[2]).count())
        assertEquals(15, run.saved.size)
        assertEquals(valid[0].description, run.saved[0].missionDescription)
    }

    @Test
    fun `invalid patches cannot overwrite normal candidates and next attempt regenerates`() {
        val invalid = listOf(
            mapOf("patches" to emptyList<Map<String, Any>>()),
            mapOf("patches" to listOf(patch(1))),
            mapOf("patches" to listOf(patch(0), patch(0))),
            mapOf("patches" to listOf(patch(0, valid[0].copy(difficulty = 3)))),
            mapOf("patches" to listOf(patch(0, valid[1]))),
            mapOf("patches" to listOf(patch(0)), "missions" to valid),
            mapOf("patches" to listOf(patch(0).toMutableMap().apply { this["id"] = 4294967296L })),
            mapOf("patches" to listOf(patch(0).toMutableMap().apply { this["difficulty"] = 4294967297L }))
        )
        invalid.forEach {
            val run = run(listOf(full(failed(listOf(0))), response(mapper.writeValueAsString(it)), full(valid)))
            assertEquals(run.inputs[0], run.inputs[2])
            assertEquals(run.requests[0]["instructions"], run.requests[2]["instructions"])
            assertEquals(valid[1].content, run.saved[1].missionContent)
            assertEquals(15, run.saved.size)
        }
    }

    @Test
    fun `malformed patch JSON returns to whole regeneration within existing budget`() {
        val run = run(listOf(full(failed(listOf(0))), response("not-json-output-marker"), full(valid)))
        assertEquals(3, run.requests.size)
        assertEquals(run.inputs[0], run.inputs[2])
        assertFalse(run.inputs[2].contains("not-json-output-marker"))
    }

    @Test
    fun `initial malformed JSON and invalid distribution use whole regeneration`() {
        for (first in listOf(response("not-json"), full(valid.take(1)), full(valid + valid[0].copy(difficulty = 4)))) {
            val run = run(listOf(first, full(valid)))
            assertEquals(run.inputs[0], run.inputs[1])
            assertEquals(run.requests[0]["instructions"], run.requests[1]["instructions"])
            assertEquals(15, run.saved.size)
        }
        assertFalse(GeneratedMissionValidator.hasValidDifficultyDistribution(valid + valid[0].copy(difficulty = 4)))
    }

    @Test
    fun `more than five invalid candidates use whole regeneration`() {
        val run = run(listOf(full(failed((0..5).toList())), full(valid)))
        assertEquals(run.inputs[0], run.inputs[1])
    }

    @Test
    fun `API error during repair clears repair state and does not resend its body`() {
        val run = run(listOf(full(failed(listOf(0))), withStatus(HttpStatus.TOO_MANY_REQUESTS).body("upstream-error-marker"), full(valid)))
        assertEquals(run.inputs[0], run.inputs[2])
        assertFalse(run.inputs.any { it.contains("upstream-error-marker") })
        assertEquals(3, run.requests.size)
    }

    @Test
    fun `past duplicate and grammar violations identify the right repair targets`() {
        val duplicate = valid[0].content
        val initial = valid.mapIndexed { i, m -> if (i == 1) m.copy(description = "교재에서 영어 문장을 할수있는 만큼 읽기") else m }
        val corrected = valid[0].copy(content = "영어 문장 90번 따라 읽기")
        val run = run(listOf(full(initial), patches(patch(0, corrected), patch(1))), past = listOf(duplicate))
        assertEquals(listOf(0, 1), targets(run.requests[1]))
        assertTrue(run.inputs[1].contains("과거 미션과 같은 제목"))
        assertTrue(run.inputs[1].contains("띄어쓰기 오류"))
        assertEquals(corrected.content, run.saved[0].missionContent)
    }

    @Test
    fun `after three calls existing least violation fallback is preserved`() {
        val run = run(listOf(full(failed(listOf(0, 5))), patches(patch(0), patch(5, valid[5].copy(description = "짧은 설명"))),
            patches(patch(5, valid[5].copy(description = "짧은 설명")))))
        assertEquals(3, run.requests.size)
        assertEquals(1, run.saved.count { it.missionDescription == "짧은 설명" })
        assertEquals(15, run.saved.size)
    }

    @Test
    fun `three invalid whole distributions fail without saving`() {
        val fixture = fixture(List(3) { full(valid.take(1)) })
        assertThrows(IllegalStateException::class.java) { fixture.service.generateAndSaveMissions(1, "영어 공부") }
        fixture.server.verify()
        verify(fixture.repository, never()).saveAll(any<List<MemberMissionEntity>>())
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun `disabled feature preserves baseline full regeneration`() {
        val run = run(listOf(full(failed(listOf(0))), full(valid)), enabled = false)
        assertEquals(run.inputs[0], run.inputs[1])
        assertEquals(run.requests[0]["instructions"], run.requests[1]["instructions"])
    }

    @Test
    fun `partial retry usage remains in the same daily operation and counts both calls`() {
        val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            OpenAiUsageTracking.withContext(OpenAiUsageContext("mission_daily", "session-1")) {
                run(listOf(full(failed(listOf(0))), patches(patch(0))))
            }
            val events = appender.list.map { mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY ")) }
            val calls = events.filter { it["event"].asText() == "api_call" }
            assertEquals(2, calls.size)
            assertEquals(listOf(1, 2), calls.map { it["attempt"].asInt() })
            assertEquals(1, calls.map { it["operationId"].asText() }.distinct().size)
            assertTrue(calls.all { it["feature"].asText() == "mission_daily" && it["promptVersion"].asText() == PartialMissionRepair.VERSION })
            assertTrue(calls.all { it["estimatedCostUsd"].decimalValue().signum() > 0 })
            assertEquals(listOf("rejected", "passed"), events.filter { it["event"].asText() == "validation" }.map { it["outcome"].asText() })
            assertNull(OpenAiUsageTracking.current())
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    private fun targets(request: com.fasterxml.jackson.databind.JsonNode): List<Int> =
        mapper.readTree(request["input"].asText().substringAfter("【부분 수정 데이터】\n").substringBefore("\n위 JSON"))["repair"].map { it["id"].asInt() }

    private data class Fixture(val service: GoalBasedMissionGenerationService, val server: MockRestServiceServer,
        val requests: MutableList<com.fasterxml.jackson.databind.JsonNode>, val repository: MemberMissionRepository)
    private data class Run(val saved: List<MemberMissionEntity>, val requests: List<com.fasterxml.jackson.databind.JsonNode>) {
        val inputs get() = requests.map { it["input"].asText() }
    }
    private fun run(responses: List<ResponseCreator>, enabled: Boolean = true, past: List<String> = emptyList()): Run {
        val fixture = fixture(responses, enabled, past)
        val saved = fixture.service.generateAndSaveMissions(1, "영어 공부")
        fixture.server.verify()
        return Run(saved, fixture.requests)
    }
    private fun fixture(responses: List<ResponseCreator>, enabled: Boolean = true, past: List<String> = emptyList()): Fixture {
        val repository = mock<MemberMissionRepository>()
        val pastEntities = past.map { MemberMissionEntity(memberId = 1, memberInterestId = 0, missionContent = it, expEarned = 50) }
        whenever(repository.findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(any(), any(), any())).thenReturn(pastEntities)
        whenever(repository.saveAll(any<List<MemberMissionEntity>>())).thenAnswer { it.getArgument(0) }
        val builder = RestClient.builder().baseUrl("https://api.openai.com/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val requests = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        responses.forEach { result -> server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond { request -> requests.add(mapper.readTree((request as MockClientHttpRequest).bodyAsString)); result.createResponse(request) }
        }
        return Fixture(GoalBasedMissionGenerationService(repository, OpenAiApiClient(builder.build(), usageEnvironment = "test"), mapper, enabled), server, requests, repository)
    }
}
