package com.haruUp.mission.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.infrastructure.MemberMissionRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class GoalBasedMissionRetryFeedbackTest {
    private val mapper = jacksonObjectMapper()
    private val valid = (1..3).flatMap { difficulty -> (1..5).map { index ->
        ParsedMission("영어 문장 ${difficulty * 5 + index}번 따라 읽기", "교재에서 영어 문장 ${index}번을 골라 소리 내어 읽기", difficulty)
    } }

    @Test
    fun `first request is unchanged and retry explains difficulty counts`() {
        val run = run(listOf(response(valid.take(1)), response(valid)))
        assertEquals(15, run.saved.size)
        assertFalse(run.inputs[0].contains("직전 응답의 검증 실패"))
        assertTrue(run.inputs[1].startsWith(run.inputs[0]))
        assertTrue(run.inputs[1].contains("총 1개, difficulty 1=1개, difficulty 2=0개, difficulty 3=0개"))
        assertEquals(run.requests[0]["instructions"], run.requests[1]["instructions"])
        assertTrue(run.requests.all { it["max_output_tokens"].asInt() == 4096 })
    }

    @Test
    fun `length grammar and past duplication errors reach the next request`() {
        val failed = valid.toMutableList().also {
            it[0] = it[0].copy(content = "읽기", description = "교재를")
            it[1] = it[1].copy(description = "교재에서 영어 문장을 할수있는 만큼 읽기")
            it[2] = it[2].copy(description = "아".repeat(1000))
        }
        val run = run(listOf(response(failed), response(valid)), past = listOf(valid[3].content),
            finalMissions = valid.map { if (it == valid[3]) it.copy(content = "영어 문장 90번 따라 읽기") else it })
        val feedback = run.inputs[1].removePrefix(run.inputs[0])
        assertTrue(feedback.contains("content 길이 오류"))
        assertTrue(feedback.contains("description 길이 오류"))
        assertTrue(feedback.contains("20~30자"))
        assertTrue(feedback.contains("띄어쓰기 오류"))
        assertTrue(feedback.contains("과거 미션과 중복"))
        assertTrue(feedback.length < 1500)
        assertFalse(feedback.contains("아".repeat(100)))
    }

    @Test
    fun `latest failure replaces previous feedback instead of accumulating`() {
        val badLength = valid.mapIndexed { i, m -> if (i == 0) m.copy(description = "짧게 읽기") else m }
        val badGrammar = valid.mapIndexed { i, m -> if (i == 0) m.copy(description = "교재에서 영어 문장을 할수있는 만큼 읽기") else m }
        val run = run(listOf(response(badLength), response(badGrammar), response(valid)))
        assertTrue(run.inputs[1].contains("description 길이 오류"))
        val thirdFeedback = run.inputs[2].removePrefix(run.inputs[0])
        assertFalse(thirdFeedback.contains("description 길이 오류"))
        assertTrue(thirdFeedback.contains("한국어 문법 오류"))
        assertEquals(1, Regex("직전 응답의 검증 실패").findAll(thirdFeedback).count())
    }

    @Test
    fun `parse failure sends fixed format guidance and not raw model response`() {
        val run = run(listOf(responseText("untrusted-output-marker"), response(valid)))
        assertTrue(run.inputs[1].contains("JSON 파싱 오류"))
        assertFalse(run.inputs[1].contains("untrusted-output-marker"))
    }

    @Test
    fun `HTTP failure clears stale feedback and never sends error body`() {
        val run = run(listOf(response(valid.take(1)),
            withStatus(HttpStatus.TOO_MANY_REQUESTS).body("upstream-error-marker"), response(valid)))
        assertTrue(run.inputs[1].contains("난이도 분포 오류"))
        assertEquals(run.inputs[0], run.inputs[2])
        assertFalse(run.inputs.any { it.contains("upstream-error-marker") })
    }

    @Test
    fun `disabled feedback preserves identical baseline requests`() {
        val run = run(listOf(response(valid.take(1)), response(valid)), enabled = false)
        assertEquals(run.inputs[0], run.inputs[1])
    }

    @Test
    fun `three invalid distributions still fail without saving`() {
        val fixture = fixture(listOf(response(valid.take(1)), response(valid.take(1)), response(valid.take(1))))
        assertThrows(IllegalStateException::class.java) { fixture.service.generateAndSaveMissions(1, "영어 공부") }
        fixture.server.verify()
        assertEquals(3, fixture.requests.size)
        verify(fixture.repository, never()).saveAll(any<List<MemberMissionEntity>>())
    }

    @Test
    fun `fallback after three attempts still uses the least length violations`() {
        val failed = (3 downTo 1).map { count -> valid.mapIndexed { i, m ->
            if (i < count) m.copy(description = "짧게 읽기") else m
        } }
        val run = run(failed.map(::response))
        assertEquals(3, run.inputs.size)
        assertEquals(15, run.saved.size)
        assertEquals(1, run.saved.count { it.missionDescription == "짧게 읽기" })
    }

    private data class Fixture(
        val service: GoalBasedMissionGenerationService, val server: MockRestServiceServer,
        val requests: MutableList<com.fasterxml.jackson.databind.JsonNode>, val repository: MemberMissionRepository
    )
    private data class Run(val saved: List<MemberMissionEntity>, val requests: List<com.fasterxml.jackson.databind.JsonNode>) {
        val inputs get() = requests.map { it["input"].asText() }
    }

    private fun fixture(responses: List<ResponseCreator>, enabled: Boolean = true, past: List<String> = emptyList()): Fixture {
        val repository = mock<MemberMissionRepository>()
        val pastEntities = past.map { content -> mock<MemberMissionEntity>().also { entity ->
            whenever(entity.missionContent).thenReturn(content)
        } }
        whenever(repository.findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(any(), any(), any()))
            .thenReturn(pastEntities)
        whenever(repository.saveAll(any<List<MemberMissionEntity>>())).thenAnswer { it.getArgument(0) }
        val builder = RestClient.builder().baseUrl("https://api.openai.com/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val requests = mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        responses.forEach { response ->
            server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect { requests.add(mapper.readTree((it as MockClientHttpRequest).bodyAsString)) }
                .andRespond(response)
        }
        return Fixture(GoalBasedMissionGenerationService(repository,
            OpenAiApiClient(builder.build(), usageEnvironment = "test"), mapper, enabled), server, requests, repository)
    }

    private fun run(responses: List<ResponseCreator>, enabled: Boolean = true, past: List<String> = emptyList(),
                    finalMissions: List<ParsedMission>? = null): Run {
        val actual = if (finalMissions == null) responses else responses.dropLast(1) + response(finalMissions)
        val fixture = fixture(actual, enabled, past)
        val saved = fixture.service.generateAndSaveMissions(1, "영어 공부")
        fixture.server.verify()
        return Run(saved, fixture.requests)
    }

    private fun response(missions: List<ParsedMission>) = responseText(mapper.writeValueAsString(mapOf("missions" to missions)))
    private fun responseText(output: String) = withSuccess(mapper.writeValueAsString(mapOf("output_text" to output)), MediaType.APPLICATION_JSON)
}
