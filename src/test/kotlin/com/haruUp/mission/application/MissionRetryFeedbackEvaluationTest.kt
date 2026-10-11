package com.haruUp.mission.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.openai.OpenAiUsageContext
import com.haruUp.global.openai.OpenAiUsageTracking
import com.haruUp.global.prompt.DailyMissionFromGoalPrompt
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.infrastructure.MemberMissionRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** A bounded pilot, not a statistically significant savings or goal-quality evaluation. */
@Tag("mission-retry-eval")
class MissionRetryFeedbackEvaluationTest {
    private val mapper = jacksonObjectMapper()
    private data class Case(val id: String, val goal: String, val context: String)
    private val cases = listOf(
        Case("english", "해외여행에서 영어로 대화하기", "영어 읽기는 가능하지만 말하기가 어렵다. 공항·호텔 체크인과 식당 주문을 연습하고 싶다. 지하철 통근 중 책을 볼 수 있고, 집에서는 녹음할 수 있다. 하루 20분 가능하다."),
        Case("exercise", "체중 감량 5kg", "운동 초보 직장인이다. 무릎에 부담이 커 뛰기는 어렵고, 집에 매트와 가벼운 덤벨이 있다. 퇴근 후 하루 20분 가능하다."),
        Case("stock", "주식 투자 수익률 월 1%", "주식 5종목을 보유한 직장인이다. 숫자를 계산하고 재무제표를 읽는 연습이 필요하다. 증권 앱과 계산기를 사용하며 하루 20분 가능하다. 실제 주문을 미션으로 지시하지 않는다.")
    )

    @Test
    fun `compare baseline and retry feedback on three synthetic goals`() {
        assumeTrue(System.getProperty("mission.retry.eval.mode", "natural") == "natural")
        val client = createClient()
        compareNatural(client)
    }

    private fun createClient(): OpenAiApiClient {
        val key = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: javaClass.classLoader.getResourceAsStream("env.properties")?.use {
                Properties().apply { load(it) }.getProperty("OPENAI_API_KEY")?.takeIf(String::isNotBlank)
            } ?: error("기존 API 키가 설정되어 있지 않습니다.")
        val factory = SimpleClientHttpRequestFactory().apply { setConnectTimeout(15_000); setReadTimeout(90_000) }
        return OpenAiApiClient(RestClient.builder().baseUrl("https://api.openai.com/v1")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $key")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .requestFactory(factory).build(), usageEnvironment = "evaluation")
    }

    private fun compareNatural(client: OpenAiApiClient) {
        val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java) as Logger
        val serviceLogger = LoggerFactory.getLogger(GoalBasedMissionGenerationService::class.java) as Logger
        val previousLevel = serviceLogger.level
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        val folder = File("build/mission-retry-eval/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        folder.mkdirs()
        val rows = mutableListOf<Map<String, Any?>>()
        try {
            // Keep synthetic conversations and generated text in the local report, not command output.
            serviceLogger.level = Level.OFF
            for ((caseIndex, case) in cases.withIndex()) {
                // Alternate order to avoid always giving the new arm a warmed cache.
                for (enabled in if (caseIndex % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val repository = mock<MemberMissionRepository>()
                    whenever(repository.findByMemberIdAndMemberInterestIdAndTargetDateGreaterThanEqual(any(), any(), any()))
                        .thenReturn(emptyList())
                    whenever(repository.saveAll(any<List<MemberMissionEntity>>())).thenAnswer { it.getArgument(0) }
                    val start = appender.list.size
                    val started = System.nanoTime()
                    var saved = emptyList<MemberMissionEntity>()
                    var failure: String? = null
                    try {
                        saved = OpenAiUsageTracking.withContext(OpenAiUsageContext("mission_initial", "eval-${case.id}-$enabled")) {
                            GoalBasedMissionGenerationService(repository, client, mapper, enabled)
                                .generateAndSaveMissions(1, case.goal, conversationRaw = case.context)
                        }
                    } catch (e: Exception) { failure = e.javaClass.simpleName }
                    val events = appender.list.drop(start).map { mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY ")) }
                    val calls = events.filter { it["event"].asText() == "api_call" }
                    val validations = events.filter { it["event"].asText() == "validation" }
                    val unknownCost = calls.count { it["estimatedCostUsd"].isNull }
                    val cost = calls.filterNot { it["estimatedCostUsd"].isNull }
                        .fold(BigDecimal.ZERO) { total, event -> total + event["estimatedCostUsd"].decimalValue() }
                    val parsed = saved.map { ParsedMission(it.missionContent, it.missionDescription.orEmpty(), it.difficulty ?: 0) }
                    val passed = GeneratedMissionValidator.hasValidDifficultyDistribution(parsed) &&
                        GeneratedMissionValidator.validate(parsed, emptySet()).passed
                    rows.add(mapOf("case" to case.id, "feedbackEnabled" to enabled,
                        "promptVersion" to if (enabled) MissionRetryFeedback.PROMPT_VERSION else "baseline-v1",
                        "apiCalls" to calls.size, "retryCalls" to calls.count { it["attempt"].asInt() > 1 },
                        "inputTokensReported" to calls.sumOf { it["inputTokens"].asLong() },
                        "cachedInputTokensReported" to calls.sumOf { it["cachedInputTokens"].asLong() },
                        "outputTokensReported" to calls.sumOf { it["outputTokens"].asLong() },
                        "unknownUsageCalls" to calls.count { it["usageStatus"].asText() != "reported" },
                        "unknownCachedInputCalls" to calls.count { it["cachedInputTokens"].isNull },
                        "unknownCostCalls" to unknownCost, "knownEstimatedCostUsd" to cost.toPlainString(),
                        "costUsd" to if (unknownCost == 0) cost.toPlainString() else null,
                        "passed" to passed, "failure" to failure,
                        "firstAttemptPassed" to validations.any { it["outcome"].asText() == "passed" && it["attempt"].asInt() == 1 },
                        "fallback" to validations.any { it["outcome"].asText() == "fallback" },
                        "validationReasons" to validations.filter { it["outcome"].asText() == "rejected" }.flatMap { it["reasons"].map { r -> r.asText() } },
                        "elapsedMs" to (System.nanoTime() - started) / 1_000_000, "missions" to parsed))
                    mapper.writerWithDefaultPrettyPrinter().writeValue(File(folder, "results.json"), rows)
                    println("missionRetryEval ${case.id} feedback=$enabled calls=${calls.size} passed=$passed cost=${if (unknownCost == 0) cost else "unknown"}")
                    // Stop before spending more after transport/API/credentials failure.
                    if (calls.any { it["outcome"].asText() == "failed" }) error("API 호출 실패: 평가를 중단했습니다. 결과 파일을 확인하세요.")
                }
            }
        } finally {
            File(folder, "telemetry.log").writeText(appender.list.joinToString("\n") { it.formattedMessage })
            logger.detachAppender(appender)
            appender.stop()
            serviceLogger.level = previousLevel
            println("missionRetryEval report: ${folder.path}")
        }
    }

    @Test
    fun `compare one controlled retry on the same seeded length failure`() {
        assumeTrue(System.getProperty("mission.retry.eval.mode", "natural") == "controlled")
        val seedFolder = File("build/mission-retry-eval").listFiles().orEmpty()
            .filter { !it.name.startsWith("controlled-") && File(it, "results.json").isFile }
            .maxByOrNull { it.name } ?: error("먼저 natural 평가의 결과를 확보하세요.")
        val seedRows = mapper.readTree(File(seedFolder, "results.json"))
        val client = createClient()
        val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        val folder = File("build/mission-retry-eval/controlled-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        folder.mkdirs()
        val rows = mutableListOf<Map<String, Any?>>()
        try {
            for ((index, case) in cases.withIndex()) {
                val seed = seedRows.first { it["case"].asText() == case.id && !it["feedbackEnabled"].asBoolean() }
                val missions = seed["missions"].map { ParsedMission(it["content"].asText(), it["description"].asText(), it["difficulty"].asInt()) }
                    .mapIndexed { i, m -> if (i == 0) m.copy(description = m.description + " 추가 설명".repeat(10)) else m }
                val failure = GeneratedMissionValidator.validate(missions, emptySet())
                check(!failure.passed && failure.longDescriptions.isNotEmpty())
                val base = DailyMissionFromGoalPrompt.buildUserMessage(case.goal, case.context)
                for (enabled in if (index % 2 == 0) listOf(true, false) else listOf(false, true)) {
                    val start = appender.list.size
                    val raw = try {
                        OpenAiUsageTracking.withContext(OpenAiUsageContext("evaluation_controlled_retry", "controlled-${case.id}-$enabled",
                            promptVersion = if (enabled) MissionRetryFeedback.PROMPT_VERSION else "baseline-v1")) {
                            OpenAiUsageTracking.attempt(2) {
                                client.generateText(base + if (enabled) MissionRetryFeedback.validation(failure) else "",
                                    DailyMissionFromGoalPrompt.SYSTEM_PROMPT, maxTokens = 4096)
                            }
                        }
                    } catch (e: Exception) { error("API 평가 실패: ${e.javaClass.simpleName}") }
                    val cleaned = raw.trim().replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("```\\s*$"), "").trim()
                    val generated = runCatching { mapper.readTree(cleaned)["missions"].mapNotNull {
                        val content = it["content"]?.asText()?.trim()
                        if (content.isNullOrBlank()) null else ParsedMission(content, it["description"]?.asText()?.trim().orEmpty(), it["difficulty"]?.asInt() ?: 1)
                    } }.getOrDefault(emptyList())
                    val passed = GeneratedMissionValidator.hasValidDifficultyDistribution(generated) && GeneratedMissionValidator.validate(generated, emptySet()).passed
                    val event = appender.list.drop(start).map { mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY ")) }
                        .single { it["event"].asText() == "api_call" }
                    rows.add(mapOf("case" to case.id, "feedbackEnabled" to enabled, "passed" to passed,
                        "evaluationKind" to "single_controlled_retry", "syntheticFailure" to "description_length",
                        "costUsd" to event["estimatedCostUsd"].takeUnless { it.isNull }?.decimalValue()?.toPlainString(),
                        "model" to event["model"].asText(), "missions" to generated))
                    mapper.writerWithDefaultPrettyPrinter().writeValue(File(folder, "results.json"), rows)
                    println("controlledRetryEval ${case.id} feedback=$enabled passed=$passed cost=${event["estimatedCostUsd"]}")
                }
            }
        } finally {
            File(folder, "telemetry.log").writeText(appender.list.joinToString("\n") { it.formattedMessage })
            logger.detachAppender(appender); appender.stop()
            println("controlledRetryEval report: ${folder.path}")
        }
    }
}
