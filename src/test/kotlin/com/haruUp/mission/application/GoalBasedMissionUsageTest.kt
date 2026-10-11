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
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class GoalBasedMissionUsageTest {
    @Test
    fun `mission retry keeps one operation and logs validation without changing generation`() {
        val mapper = jacksonObjectMapper()
        val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val repository = mock<MemberMissionRepository>()
            whenever(repository.findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(any(), any(), any()))
                .thenReturn(emptyList())
            whenever(repository.saveAll(any<List<MemberMissionEntity>>())).thenAnswer { it.getArgument(0) }
            val builder = RestClient.builder().baseUrl("https://api.openai.com/v1")
            val server = MockRestServiceServer.bindTo(builder).build()
            val missions = (1..3).flatMap { difficulty ->
                (1..5).map { index ->
                    ParsedMission("영어 문장 ${difficulty * 5 + index}번 따라 읽기", "교재에서 영어 문장 ${index}번을 골라 소리 내어 읽기", difficulty)
                }
            }
            assertTrue(GeneratedMissionValidator.validate(missions, emptySet()).passed)
            for (output in listOf(missions.take(1), missions).map { mapper.writeValueAsString(mapOf("missions" to it)) }) {
                val response = mapper.writeValueAsString(mapOf(
                    "output_text" to output, "model" to "gpt-5.4-mini", "service_tier" to "default",
                    "usage" to mapOf("input_tokens" to 100, "input_tokens_details" to mapOf("cached_tokens" to 0), "output_tokens" to 50)
                ))
                server.expect(requestTo("https://api.openai.com/v1/responses"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
            }
            val service = GoalBasedMissionGenerationService(repository, OpenAiApiClient(builder.build(), usageEnvironment = "test"), mapper)
            val saved = OpenAiUsageTracking.withContext(OpenAiUsageContext("mission_initial", "session-1")) {
                service.generateAndSaveMissions(1, "영어 공부")
            }
            assertEquals(15, saved.size)
            server.verify()
            val events = appender.list.map { mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY ")) }
            val calls = events.filter { it["event"].asText() == "api_call" }
            assertEquals(2, calls.size)
            assertEquals(listOf(1, 2), calls.map { it["attempt"].asInt() })
            assertEquals(1, calls.map { it["operationId"].asText() }.distinct().size)
            assertTrue(calls.all { it["sessionId"].asText() == "session-1" && it["feature"].asText() == "mission_initial" })
            val validation = events.filter { it["event"].asText() == "validation" }
            assertEquals(listOf("rejected", "passed"), validation.map { it["outcome"].asText() })
            assertEquals("difficulty_distribution", validation.first()["reasons"][0].asText())
            assertEquals(calls.first()["operationId"], validation.first()["operationId"])
            assertNull(OpenAiUsageTracking.current())
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
