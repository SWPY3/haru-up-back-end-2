package com.haruUp.mission.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.openai.OpenAiUsageContext
import com.haruUp.global.openai.OpenAiUsageTracking
import com.haruUp.global.prompt.DailyMissionFromGoalPrompt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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

/** Paired synthetic failure pilot; production service and database are never used. */
@Tag("mission-retry-eval")
class PartialMissionRepairCostEvaluationTest {
    private val mapper = jacksonObjectMapper()
    private data class Case(val id: String, val goal: String, val context: String)
    private val cases = listOf(
        Case("english", "해외여행에서 영어로 대화하기", "영어 읽기는 가능하지만 말하기가 어렵다. 공항·호텔 체크인과 식당 주문을 연습하고 싶다. 지하철 통근 중 책을 볼 수 있고, 집에서는 녹음할 수 있다. 하루 20분 가능하다."),
        Case("exercise", "체중 감량 5kg", "운동 초보 직장인이다. 무릎에 부담이 커 뛰기는 어렵고, 집에 매트와 가벼운 덤벨이 있다. 퇴근 후 하루 20분 가능하다."),
        Case("stock", "주식 투자 수익률 월 1%", "주식 5종목을 보유한 직장인이다. 숫자를 계산하고 재무제표를 읽는 연습이 필요하다. 증권 앱과 계산기를 사용하며 하루 20분 가능하다. 실제 주문을 미션으로 지시하지 않는다.")
    )

    @Test
    fun `measure full regeneration versus partial repair on matched failures`() {
        assumeTrue(System.getProperty("mission.retry.eval.mode", "natural") == "partial")
        val seedResource = "evaluation/mission-partial-repair-seeds.json"
        val seeds = javaClass.classLoader.getResourceAsStream(seedResource)?.use { mapper.readTree(it) }
            ?: error("고정 평가 시드 파일이 필요합니다.")
        val client = createClient()
        val logger = LoggerFactory.getLogger(OpenAiUsageTracking::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        val folder = File("build/mission-retry-eval/partial-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        folder.mkdirs()
        val rows = mutableListOf<Map<String, Any?>>()
        var totalCalls = 0
        var evaluationCost = BigDecimal.ZERO
        mapper.writerWithDefaultPrettyPrinter().writeValue(File(folder, "conditions.json"), mapOf(
            "seedFile" to seedResource, "failedCounts" to listOf(1, 3, 5), "repetitionsPerGoalAndCount" to 2,
            "maxRetryCallsPerArm" to 2, "maxTotalApiCalls" to 72, "softStopCostUsd" to "0.50",
            "history" to "empty", "failureKind" to "synthetic_long_description",
            "initialCostTreatment" to "reuse measured single-call seed cost in both arms; not a new initial API call",
            "scope" to "evaluation prototype only, no production or database changes"))
        try {
            var pairIndex = 0
            for (case in cases) {
                val seed = seeds.first { it["case"].asText() == case.id && it["feedbackEnabled"].asBoolean() }
                check(seed["apiCalls"].asInt() == 1 && seed["passed"].asBoolean() && !seed["costUsd"].isNull)
                val initial = seed["missions"].map {
                    ParsedMission(it["content"].asText(), it["description"].asText(), it["difficulty"].asInt())
                }
                check(PartialMissionRepairEvaluationSupport.passed(initial))
                val initialCost = BigDecimal(seed["costUsd"].asText())
                val base = DailyMissionFromGoalPrompt.buildUserMessage(case.goal, case.context)
                for (failedCount in listOf(1, 3, 5)) {
                    val failedIds = listOf(0, 5, 10, 1, 6).take(failedCount)
                    val failed = initial.mapIndexed { i, m ->
                        if (i in failedIds) m.copy(description = m.description + " 추가 설명".repeat(10)) else m
                    }
                    check(PartialMissionRepairEvaluationSupport.invalidIndices(failed).toSet() == failedIds.toSet())
                    for (repetition in 1..2) {
                        val pairId = "${case.id}-$failedCount-$repetition"
                        val order = if (pairIndex++ % 2 == 0) listOf(false, true) else listOf(true, false)
                        for (partial in order) {
                            val started = System.nanoTime()
                            val start = appender.list.size
                            var current = failed
                            val attempts = mutableListOf<Map<String, Any?>>()
                            for (retry in 1..2) {
                                check(totalCalls < 72 && evaluationCost < BigDecimal("0.50")) { "평가 호출/비용 한도에 도달했습니다." }
                                val targets = if (partial) PartialMissionRepairEvaluationSupport.invalidIndices(current) else emptyList()
                                val raw = try {
                                    OpenAiUsageTracking.withContext(OpenAiUsageContext("evaluation_partial_repair", "$pairId-$partial",
                                        promptVersion = if (partial) PartialMissionRepairEvaluationSupport.VERSION else "baseline-v1")) {
                                        OpenAiUsageTracking.attempt(retry + 1) {
                                            client.generateText(
                                                if (partial) PartialMissionRepairEvaluationSupport.prompt(base, current, targets) else base,
                                                if (partial) PartialMissionRepairEvaluationSupport.system else DailyMissionFromGoalPrompt.SYSTEM_PROMPT,
                                                model = OpenAiApiClient.MODEL_DEFAULT, maxTokens = 4096)
                                        }
                                    }
                                } catch (e: Exception) { error("API 평가 중단: ${e.javaClass.simpleName}") }
                                totalCalls++
                                val call = appender.list.last { it.formattedMessage.contains("\"event\":\"api_call\"") }
                                    .let { mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY ")) }
                                check(!call["estimatedCostUsd"].isNull) { "평가 비용 미확인: 추가 호출을 중단합니다." }
                                evaluationCost += call["estimatedCostUsd"].decimalValue()
                                var parseFailure: String? = null
                                try {
                                    current = if (partial) PartialMissionRepairEvaluationSupport.merge(raw, current, targets)
                                        else PartialMissionRepairEvaluationSupport.parseFull(raw)
                                } catch (e: Exception) { parseFailure = e.javaClass.simpleName }
                                val passed = parseFailure == null && PartialMissionRepairEvaluationSupport.passed(current)
                                attempts.add(mapOf("retry" to retry, "targets" to targets, "passed" to passed,
                                    "parseFailure" to parseFailure, "remainingInvalid" to PartialMissionRepairEvaluationSupport.invalidIndices(current).size,
                                    "difficultyValid" to GeneratedMissionValidator.hasValidDifficultyDistribution(current), "rawResponse" to raw))
                                if (passed) break
                            }
                            val calls = appender.list.drop(start).map { mapper.readTree(it.formattedMessage.substringAfter("OPENAI_TELEMETRY ")) }
                                .filter { it["event"].asText() == "api_call" }
                            val retryCost = calls.fold(BigDecimal.ZERO) { sum, call -> sum + call["estimatedCostUsd"].decimalValue() }
                            val preserved = initial.indices.filterNot { it in failedIds }.count { current.getOrNull(it) == initial[it] }
                            if (partial) check(preserved == 15 - failedCount)
                            val passed = attempts.last()["passed"] == true
                            rows.add(mapOf("pairId" to pairId, "case" to case.id, "failedCount" to failedCount,
                                "repetition" to repetition, "mode" to if (partial) "partial" else "full",
                                "passed" to passed, "firstRetryPassed" to (attempts.first()["passed"] == true),
                                "apiCalls" to calls.size, "retryCostUsd" to retryCost.toPlainString(),
                                "sharedInitialCostUsd" to initialCost.toPlainString(),
                                "modeledTotalCostUsd" to (initialCost + retryCost).toPlainString(),
                                "inputTokens" to calls.sumOf { it["inputTokens"].asLong() },
                                "cachedInputTokens" to calls.sumOf { it["cachedInputTokens"].asLong() },
                                "outputTokens" to calls.sumOf { it["outputTokens"].asLong() },
                                "unknownUsageCalls" to calls.count { it["usageStatus"].asText() != "reported" },
                                "unknownCachedInputCalls" to calls.count { it["cachedInputTokens"].isNull },
                                "models" to calls.map { it["model"].asText() }.distinct(),
                                "normalCandidatesPreserved" to preserved,
                                "exactTitleDuplicateCount" to current.size - current.map { it.content }.distinct().size,
                                "elapsedMs" to (System.nanoTime() - started) / 1_000_000,
                                "attempts" to attempts, "missions" to current))
                            mapper.writerWithDefaultPrettyPrinter().writeValue(File(folder, "results.json"), rows)
                            println("partialRepairEval $pairId mode=${if (partial) "partial" else "full"} calls=${calls.size} passed=$passed retryCost=$retryCost")
                        }
                    }
                }
            }
        } finally {
            File(folder, "telemetry.log").writeText(appender.list.joinToString("\n") { it.formattedMessage })
            logger.detachAppender(appender); appender.stop()
            println("partialRepairEval report: ${folder.path}; calls=$totalCalls; estimatedCost=$evaluationCost")
        }
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
}
