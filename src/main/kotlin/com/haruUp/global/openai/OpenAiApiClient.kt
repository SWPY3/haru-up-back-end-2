package com.haruUp.global.openai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class OpenAiApiClient(
    private val openAiRestClient: RestClient,
    @Value("\${openai.api.model:gpt-5.5}")
    private val defaultModel: String = MODEL_DEFAULT
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MODEL_DEFAULT = "gpt-5.5"
        const val MODEL_FAST = "gpt-4.1-mini"
    }

    fun chatCompletion(
        messages: List<ChatMessage>,
        model: String? = null,
        maxTokens: Int = 2048,
        temperature: Double = 0.5,
        topK: Int = 0,
        topP: Double = 1.0,
        repeatPenalty: Double = 1.0,
        seed: Int? = null
    ): OpenAiApiResponse {
        val instructions = messages
            .filter { it.role == "system" || it.role == "developer" }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }

        val input = messages
            .filterNot { it.role == "system" || it.role == "developer" }
            .map {
                OpenAiInputMessage(
                    role = when (it.role) {
                        "assistant" -> "assistant"
                        else -> "user"
                    },
                    content = it.content
                )
            }
            .ifEmpty {
                listOf(OpenAiInputMessage(role = "user", content = instructions ?: ""))
            }

        val outputText = createResponse(
            model = model ?: defaultModel,
            input = input,
            instructions = instructions,
            maxOutputTokens = maxTokens,
            temperature = temperature
        )

        logger.debug(
            "OpenAI response: messages={}, model={}, temp={}, topK={}, topP={}, repeatPenalty={}, seed={}, contentLength={}",
            messages.size,
            model ?: defaultModel,
            temperature,
            topK,
            topP,
            repeatPenalty,
            seed,
            outputText.length
        )

        return OpenAiApiResponse(
            result = OpenAiResult(
                message = OpenAiMessage(
                    role = "assistant",
                    content = outputText
                )
            )
        )
    }

    fun generateText(
        userMessage: String,
        systemMessage: String? = null,
        model: String? = null,
        temperature: Double = 0.5,
        seed: Int? = null
    ): String {
        val outputText = createResponse(
            model = model ?: defaultModel,
            input = userMessage,
            instructions = systemMessage,
            maxOutputTokens = 2048,
            temperature = temperature
        )

        logger.debug(
            "OpenAI text response: model={}, temp={}, seed={}, contentLength={}",
            model ?: defaultModel,
            temperature,
            seed,
            outputText.length
        )

        return outputText
    }

    private fun createResponse(
        model: String,
        input: Any,
        instructions: String?,
        maxOutputTokens: Int,
        temperature: Double
    ): String {
        val request = OpenAiResponsesRequest(
            model = model,
            instructions = instructions,
            input = input,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            text = OpenAiTextConfig()
        )

        val response = openAiRestClient.post()
            .uri("/responses")
            .body(request)
            .retrieve()
            .body(OpenAiResponsesResponse::class.java)
            ?: throw OpenAiApiException("OpenAI API response is null.")

        return extractOutputText(response)
    }

    private fun extractOutputText(response: OpenAiResponsesResponse): String {
        response.outputText?.takeIf { it.isNotBlank() }?.let { return it }

        val nestedText = response.output
            .asSequence()
            .flatMap { it.content.orEmpty().asSequence() }
            .filter { it.type == "output_text" }
            .mapNotNull { it.text?.takeIf(String::isNotBlank) }
            .joinToString("\n")

        if (nestedText.isNotBlank()) return nestedText

        throw OpenAiApiException(response.error?.message ?: "OpenAI API response has no output text.")
    }
}

data class ChatMessage(
    val role: String,
    val content: String
)

data class OpenAiResponsesRequest(
    val model: String,
    val instructions: String? = null,
    val input: Any,
    @JsonProperty("max_output_tokens")
    val maxOutputTokens: Int,
    val temperature: Double,
    val text: OpenAiTextConfig
)

data class OpenAiTextConfig(
    val format: OpenAiTextFormat = OpenAiTextFormat()
)

data class OpenAiTextFormat(
    val type: String = "text"
)

data class OpenAiInputMessage(
    val role: String,
    val content: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiResponsesResponse(
    @JsonProperty("output_text")
    val outputText: String? = null,
    val output: List<OpenAiOutputItem> = emptyList(),
    val error: OpenAiError? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiOutputItem(
    val type: String? = null,
    val role: String? = null,
    val content: List<OpenAiOutputContent>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiOutputContent(
    val type: String? = null,
    val text: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiError(
    val code: String? = null,
    val message: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiApiResponse(
    val status: OpenAiStatus? = null,
    val result: OpenAiResult? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiStatus(
    val code: String? = null,
    val message: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiResult(
    val message: OpenAiMessage,
    val stopReason: String? = null,
    val inputLength: Int? = null,
    val outputLength: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenAiMessage(
    val role: String,
    val content: String
)

class OpenAiApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
