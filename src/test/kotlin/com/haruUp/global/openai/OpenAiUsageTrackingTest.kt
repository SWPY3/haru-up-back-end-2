package com.haruUp.global.openai

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import java.math.BigDecimal

class OpenAiUsageTrackingTest {
    private val mapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java) as Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var server: MockRestServiceServer
    private lateinit var client: OpenAiApiClient

    @BeforeEach
    fun setUp() {
        appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        val builder = RestClient.builder().baseUrl("https://api.openai.com/v1")
        server = MockRestServiceServer.bindTo(builder).build()
        client = OpenAiApiClient(builder.build(), usageEnvironment = "test")
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        assertNull(OpenAiUsageTracking.current(), "Scope must not leak across requests")
    }

    private fun events(): List<JsonNode> = appender.list.map {
        mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY "))
    }

    private fun respond(body: String) {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private fun response(text: Boolean = true): String = """
        {"id":"resp_test", "model":"gpt-5.4-mini-2026-03-17", "status":"completed",
         "service_tier":"default", "output_text":${if (text) "\"result\"" else "null"},
         "usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":800},
                  "output_tokens":100,"output_tokens_details":{"reasoning_tokens":40},
                  "total_tokens":1100}}
    """.trimIndent()

    @Test
    fun `records actual model subsets cost and request context without content`() {
        respond(response())
        val scope = OpenAiUsageContext("mission_initial", "session-1", "operation-1", attempt = 2)
        val text = OpenAiUsageTracking.withContext(scope) {
            client.generateText("private user input", systemMessage = "private instructions")
        }
        assertEquals("result", text)
        val event = events().single()
        assertEquals("api_call", event["event"].asText())
        assertEquals("test", event["environment"].asText())
        assertEquals("mission_initial", event["feature"].asText())
        assertEquals("operation-1", event["operationId"].asText())
        assertEquals(2, event["attempt"].asInt())
        assertEquals("gpt-5.4-mini-2026-03-17", event["model"].asText())
        assertEquals(1000, event["inputTokens"].asInt())
        assertEquals(800, event["cachedInputTokens"].asInt())
        assertEquals(40, event["reasoningTokens"].asInt())
        assertEquals(0, event["estimatedCostUsd"].decimalValue().compareTo(BigDecimal("0.00066")))
        assertEquals("reported", event["usageStatus"].asText())
        assertTrue(event["elapsedMs"].asLong() >= 0)
        assertFalse(event.toString().contains("private"))
        assertFalse(event.toString().contains("instructions"))
        server.verify()
    }

    @Test
    fun `chatCompletion records nested output with usage`() {
        respond(response().replace("\"output_text\":\"result\"", "\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"result\"}]}]"))
        assertEquals("result", client.chatCompletion(listOf(ChatMessage("user", "hello"))).result?.message?.content)
        assertEquals(100, events().single()["outputTokens"].asInt())
        server.verify()
    }

    @Test
    fun `missing usage remains unknown rather than zero`() {
        respond("""{"output_text":"result"}""")
        assertEquals("result", client.generateText("hello"))
        val event = events().single()
        assertEquals("unknown", event["usageStatus"].asText())
        assertTrue(event["inputTokens"].isNull)
        assertTrue(event["estimatedCostUsd"].isNull)
        server.verify()
    }

    @Test
    fun `charged response without text is still recorded once before failure`() {
        respond(response(text = false))
        assertThrows(OpenAiApiException::class.java) { client.generateText("hello") }
        val event = events().single()
        assertEquals("failed", event["outcome"].asText())
        assertEquals("missing_output_text", event["failureReason"].asText())
        assertEquals(100, event["outputTokens"].asInt())
        assertFalse(event["estimatedCostUsd"].isNull)
        server.verify()
    }

    @Test
    fun `HTTP failure is counted without exposing body or assuming free usage`() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private failure details"))
        assertThrows(Exception::class.java) { client.generateText("hello") }
        val event = events().single()
        assertEquals("http_429", event["failureReason"].asText())
        assertTrue(event["estimatedCostUsd"].isNull)
        assertFalse(event.toString().contains("private failure"))
        server.verify()
    }

    @Test
    fun `unknown models tiers and missing cache details have no price estimate`() {
        val usage = mapper.readValue(response(), OpenAiResponsesResponse::class.java).usage!!
        assertNull(OpenAiCostEstimator.estimate("gpt-test", "default", usage))
        assertNull(OpenAiCostEstimator.estimate("gpt-5.4-mini", "flex", usage))
        assertNull(OpenAiCostEstimator.estimate("gpt-5.4-mini", null, usage))
        assertNull(OpenAiCostEstimator.estimate("gpt-5.4-mini", "default", usage.copy(inputTokensDetails = null)))
        assertNull(OpenAiCostEstimator.estimate("gpt-5.4-mini", "default", usage.copy(inputTokensDetails = OpenAiInputTokenDetails(1001))))
    }

    @Test
    fun `explicit zero usage has known zero cost`() {
        val usage = OpenAiUsage(0, 0, 0, OpenAiInputTokenDetails(0), OpenAiOutputTokenDetails(0))
        assertEquals(0, OpenAiCostEstimator.estimate("gpt-5.4-mini", "default", usage)!!.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `nested operations keep session and attempts and restore after exceptions`() {
        val outer = OpenAiUsageContext(sessionId = "session-1")
        OpenAiUsageTracking.withContext(outer) {
            assertThrows(IllegalStateException::class.java) {
                OpenAiUsageTracking.operation("curation_question") {
                    val id = OpenAiUsageTracking.current()!!.operationId
                    repeat(2) { index ->
                        OpenAiUsageTracking.attempt(index + 1) {
                            val scope = OpenAiUsageTracking.current()!!
                            assertEquals("session-1", scope.sessionId)
                            assertEquals(id, scope.operationId)
                            assertEquals(index + 1, scope.attempt)
                            OpenAiUsageTracking.validation(if (index == 0) "rejected" else "passed")
                        }
                    }
                    throw IllegalStateException("test")
                }
            }
            assertEquals(outer, OpenAiUsageTracking.current())
        }
        assertEquals(listOf(1, 2), events().map { it["attempt"].asInt() })
    }
}
