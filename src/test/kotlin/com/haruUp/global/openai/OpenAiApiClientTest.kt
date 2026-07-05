package com.haruUp.global.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.http.HttpMethod

class OpenAiApiClientTest {

    private lateinit var server: MockRestServiceServer
    private lateinit var client: OpenAiApiClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        val restClient = builder
            .baseUrl("https://api.openai.com/v1")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
        client = OpenAiApiClient(
            openAiRestClient = restClient,
            defaultModel = "gpt-test"
        )
    }

    @Test
    fun `generateText calls Responses API and returns output text`() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
            .andExpect(
                content().json(
                    """
                    {
                      "model": "gpt-test",
                      "instructions": "system rules",
                      "input": "hello",
                      "max_output_tokens": 2048,
                      "temperature": 0.2,
                      "text": { "format": { "type": "text" } }
                    }
                    """.trimIndent()
                )
            )
            .andRespond(
                withSuccess(
                    """
                    {
                      "output_text": "world",
                      "output": []
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        val result = client.generateText(
            userMessage = "hello",
            systemMessage = "system rules",
            temperature = 0.2
        )

        assertEquals("world", result)
        server.verify()
    }

    @Test
    fun `chatCompletion maps messages and parses nested output text`() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """
                    {
                      "model": "custom-model",
                      "instructions": "be terse",
                      "input": [
                        { "role": "user", "content": "say hi" }
                      ],
                      "max_output_tokens": 64,
                      "temperature": 0.5,
                      "text": { "format": { "type": "text" } }
                    }
                    """.trimIndent()
                )
            )
            .andRespond(
                withSuccess(
                    """
                    {
                      "output": [
                        {
                          "type": "message",
                          "role": "assistant",
                          "content": [
                            { "type": "output_text", "text": "hi" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        val response = client.chatCompletion(
            messages = listOf(
                ChatMessage(role = "system", content = "be terse"),
                ChatMessage(role = "user", content = "say hi")
            ),
            model = "custom-model",
            maxTokens = 64
        )

        assertEquals("hi", response.result?.message?.content)
        server.verify()
    }
}
