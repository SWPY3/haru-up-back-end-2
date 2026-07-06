package com.haruUp.global.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

@Configuration
class OpenAiApiConfig(
    private val restClientBuilder: RestClient.Builder
) {

    @Value("\${openai.api.url:https://api.openai.com/v1}")
    private lateinit var openAiApiUrl: String

    @Value("\${openai.api.key}")
    private lateinit var openAiApiKey: String

    @Bean
    fun openAiRestClient(): RestClient {
        return restClientBuilder
            .baseUrl(openAiApiUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $openAiApiKey")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}
