package com.haruUp.global.openai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class OpenAiEmbeddingClient(
    private val openAiRestClient: RestClient,
    @Value("\${openai.api.embedding-model:text-embedding-3-small}")
    private val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
    @Value("\${openai.api.embedding-dimensions:1024}")
    private val embeddingDimensions: Int = VECTOR_SIZE
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"
        const val VECTOR_SIZE = 1024
    }

    suspend fun createEmbedding(text: String): List<Float> = withContext(Dispatchers.IO) {
        val request = OpenAiEmbeddingRequest(
            model = embeddingModel,
            input = text,
            dimensions = embeddingDimensions,
            encodingFormat = "float"
        )

        val response = openAiRestClient.post()
            .uri("/embeddings")
            .body(request)
            .retrieve()
            .body<OpenAiEmbeddingResponse>()
            ?: throw OpenAiApiException("OpenAI embedding response is null.")

        val embedding = response.data.firstOrNull()?.embedding
            ?: throw OpenAiApiException("OpenAI embedding response has no embedding data.")

        logger.debug("OpenAI embedding created: model={}, dimensions={}", embeddingModel, embedding.size)
        return@withContext embedding
    }
}

data class OpenAiEmbeddingRequest(
    val model: String,
    val input: String,
    val dimensions: Int,
    @JsonProperty("encoding_format")
    val encodingFormat: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiEmbeddingResponse(
    val data: List<OpenAiEmbeddingData> = emptyList(),
    val model: String? = null,
    val objectType: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiEmbeddingData(
    val embedding: List<Float>,
    val index: Int? = null,
    val objectType: String? = null
)
