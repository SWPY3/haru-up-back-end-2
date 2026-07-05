package com.haruUp.global.openai

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class OpenAiEmbeddingClientTest {

    private lateinit var server: MockRestServiceServer
    private lateinit var client: OpenAiEmbeddingClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        val restClient = builder
            .baseUrl("https://api.openai.com/v1")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
        client = OpenAiEmbeddingClient(
            openAiRestClient = restClient,
            embeddingModel = "text-embedding-3-small",
            embeddingDimensions = 1024
        )
    }

    @Test
    fun `createEmbedding sends dimensions and returns first embedding vector`() = runBlocking {
        server.expect(requestTo("https://api.openai.com/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    """
                    {
                      "model": "text-embedding-3-small",
                      "input": "관심사",
                      "dimensions": 1024,
                      "encoding_format": "float"
                    }
                    """.trimIndent()
                )
            )
            .andRespond(
                withSuccess(
                    """
                    {
                      "data": [
                        {
                          "embedding": [0.1, 0.2, 0.3],
                          "index": 0,
                          "object": "embedding"
                        }
                      ],
                      "model": "text-embedding-3-small",
                      "object": "list"
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON
                )
            )

        val result = client.createEmbedding("관심사")

        assertEquals(listOf(0.1f, 0.2f, 0.3f), result)
        server.verify()
    }
}
