package com.haruUp.global.openai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

data class OpenAiUsageContext(
    val feature: String = "unclassified",
    val sessionId: String? = null,
    val operationId: String = UUID.randomUUID().toString(),
    val promptVersion: String = "baseline-v1",
    val attempt: Int = 1
)

/** Synchronous RestClient scopes only. Always restore scope before a pooled thread is reused. */
object OpenAiUsageTracking {
    @PublishedApi internal val context = ThreadLocal<OpenAiUsageContext>()
    private val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java)
    private val mapper = jacksonObjectMapper()

    fun current(): OpenAiUsageContext? = context.get()

    inline fun <T> withContext(value: OpenAiUsageContext, block: () -> T): T {
        val previous = context.get()
        context.set(value)
        try {
            return block()
        } finally {
            if (previous == null) context.remove() else context.set(previous)
        }
    }

    inline fun <T> operation(feature: String, promptVersion: String = "baseline-v1", block: () -> T): T =
        withContext(OpenAiUsageContext(feature, current()?.sessionId, promptVersion = promptVersion), block)

    inline fun <T> attempt(number: Int, block: () -> T): T =
        withContext((current() ?: OpenAiUsageContext()).copy(attempt = number), block)

    fun validation(outcome: String, reasons: List<String> = emptyList()) {
        val scope = current() ?: return
        emit(mapOf(
            "event" to "validation", "feature" to scope.feature,
            "sessionId" to scope.sessionId, "operationId" to scope.operationId,
            "promptVersion" to scope.promptVersion, "attempt" to scope.attempt,
            "outcome" to outcome, "reasons" to reasons
        ))
    }

    fun record(
        scope: OpenAiUsageContext, environment: String, requestedModel: String,
        response: OpenAiResponsesResponse?, elapsedMs: Long, outcome: String, failureReason: String?
    ) {
        val usage = response?.usage
        val model = response?.model ?: requestedModel
        val estimate = OpenAiCostEstimator.estimate(model, response?.serviceTier, usage)
        emit(mapOf(
            "event" to "api_call", "environment" to environment, "feature" to scope.feature,
            "sessionId" to scope.sessionId, "operationId" to scope.operationId,
            "promptVersion" to scope.promptVersion, "attempt" to scope.attempt,
            "responseId" to response?.id, "requestedModel" to requestedModel, "model" to model,
            "serviceTier" to response?.serviceTier, "responseStatus" to response?.status,
            "outcome" to outcome, "failureReason" to failureReason, "elapsedMs" to elapsedMs,
            "usageStatus" to if (usage?.inputTokens != null && usage.outputTokens != null) "reported" else "unknown",
            "inputTokens" to usage?.inputTokens, "cachedInputTokens" to usage?.inputTokensDetails?.cachedTokens,
            "outputTokens" to usage?.outputTokens, "reasoningTokens" to usage?.outputTokensDetails?.reasoningTokens,
            "totalTokens" to usage?.totalTokens, "estimatedCostUsd" to estimate,
            "costStatus" to if (estimate == null) "unknown" else "estimated_standard",
            "pricingVersion" to if (estimate == null) null else "gpt-5.4-mini-standard-2026-10-10"
        ))
    }

    private fun emit(fields: Map<String, Any?>) {
        // Accounting must not change generation behavior or log prompt/response/exception bodies.
        runCatching { logger.info("OPENAI_TELEMETRY {}", mapper.writeValueAsString(fields)) }
    }
}

object OpenAiCostEstimator {
    /** Cached input and reasoning are subsets, not additional tokens. Unknown prices are never zero. */
    fun estimate(model: String, serviceTier: String?, usage: OpenAiUsage?): BigDecimal? {
        if (!Regex("gpt-5[._]4-mini(?:-\\d{4}-\\d{2}-\\d{2})?").matches(model)) return null
        if (serviceTier != "default") return null
        val input = usage?.inputTokens ?: return null
        val output = usage.outputTokens ?: return null
        val cached = usage.inputTokensDetails?.cachedTokens ?: return null
        if (input < 0 || output < 0 || cached !in 0..input) return null
        return BigDecimal.valueOf(input - cached).multiply(BigDecimal("0.75"))
            .add(BigDecimal.valueOf(cached).multiply(BigDecimal("0.075")))
            .add(BigDecimal.valueOf(output).multiply(BigDecimal("4.50")))
            .movePointLeft(6)
    }
}
