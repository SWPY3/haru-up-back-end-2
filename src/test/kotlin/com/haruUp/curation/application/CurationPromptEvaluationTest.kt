package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.global.openai.ChatMessage
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ChatbotQuestionPrompt
import com.haruUp.global.prompt.ChatbotSummaryPrompt
import com.haruUp.global.prompt.DailyMissionFromGoalPrompt
import com.haruUp.global.util.KoreanGrammarChecker
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * 큐레이션 프롬프트 자동 평가 하네스.
 *
 * 실제 OpenAI API를 호출해 운영과 동일한 순서로 전 과정을 재현한다.
 *   1) 큐레이션 진행  : 고정 질문 1개 + AI 꼬리질문 5개, 가상 사용자가 각 질문에 답변
 *   2) 큐레이션 요약  : ChatbotSummaryPrompt 로 대화 요약
 *   3) 미션 생성      : DailyMissionFromGoalPrompt 로 난이도별 5개씩 15개
 *   4) 미션 재추천    : MissionRecommendService.retryWithGoal 과 동일한 조건으로 재생성
 *
 * 실행 (일반 test 태스크에서는 curation-eval 태그로 제외된다):
 *   ./gradlew curationEval
 *   ./gradlew curationEval -Ppersonas=exercise,english -Psessions=2
 *
 * 결과: build/curation-eval/<timestamp>/ 아래에 요약/카테고리별 마크다운과 CSV가 생성된다.
 */
@Tag("curation-eval")
class CurationPromptEvaluationTest {

    private val objectMapper = ObjectMapper()

    /** 미션 생성 프롬프트/서비스와 동일한 기준값 */
    private val missionsPerDifficulty = 5
    private val minDescriptionLength = 20

    /** description 상한. GoalBasedMissionGenerationService 의 기준과 동일해야 한다. */
    private val maxDescriptionLength = 30

    private val maxMissionRetry = 3
    private val maxQuestionRetry = CurationChatbotUseCase.MAX_QUESTION_RETRY
    private val totalQuestions = 6

    /** 사용자가 부담 없이 답할 수 있다고 본 답변 길이 기준 */
    private val answerLengthTarget = 30

    private val personas = listOf(
        Persona(
            key = "exercise",
            category = "운동 · 체중 감량",
            goalText = "체중 감량 5kg",
            profile = """
                32세 직장인 여성. 평일 9시 출근 19시 30분 퇴근, 집 도착은 20시 30분쯤.
                운동은 3개월 전 헬스장을 등록했다가 2주 만에 그만뒀다. 야근이 주 2회 있다.
                저녁은 대부분 배달 음식으로 해결하고, 주말에는 늦잠을 잔다.
                무릎이 약해서 뛰는 운동은 부담스럽다.
            """.trimIndent()
        ),
        Persona(
            key = "english",
            category = "영어 공부",
            goalText = "토익 900점",
            profile = """
                24세 대학교 4학년 남학생. 현재 토익 720점(LC 380, RC 340).
                학교 수업은 주 3일이고, 나머지 요일은 카페 아르바이트를 오후 2시부터 8시까지 한다.
                단어 암기를 제일 싫어하고, 인강은 사놓고 3강까지만 들었다.
                시험은 두 달 뒤로 잡아뒀고 통학 시간은 편도 50분이다.
            """.trimIndent()
        ),
        Persona(
            key = "stock",
            category = "주식 투자",
            goalText = "주식 투자 수익률 월 1%",
            profile = """
                29세 중소기업 사무직 남성. 투자 경험 1년, 현재 투자금 800만원.
                작년에 테마주를 따라 샀다가 200만원을 잃고 6개월간 손을 놨다.
                재무제표를 볼 줄 모르고, 유튜브 리딩방 영상을 주로 본다.
                평일에는 근무 중이라 장중 매매가 어렵고, 확인은 점심시간과 퇴근 후에만 가능하다.
            """.trimIndent()
        ),
        Persona(
            key = "habit",
            category = "하루 생활 습관 개선",
            goalText = "매일 아침 7시 기상 습관 만들기",
            profile = """
                27세 프리랜서 디자이너. 재택근무라 출근 시간이 정해져 있지 않다.
                새벽 2~3시에 잠들고 오전 10~11시에 일어나는 생활을 1년째 하고 있다.
                자기 전에 침대에서 유튜브를 1~2시간 본다. 알람을 5개 맞춰두고 다 끈다.
                아침을 거의 먹지 않고 커피를 하루 4잔 마신다.
            """.trimIndent()
        ),
        Persona(
            key = "quit-smoking",
            category = "금연",
            goalText = "금연하기",
            profile = """
                45세 남성, 영업직. 15년째 하루 한 갑을 피운다.
                금연을 세 번 시도했고 가장 오래 버틴 기간은 2주였다. 매번 회식 자리에서 다시 피웠다.
                아침 출근길과 점심 식사 후 흡연이 가장 참기 힘들다.
                운동은 거의 하지 않고 저녁 약속이 주 3회 있다.
            """.trimIndent()
        )
    )

    @Test
    fun `큐레이션 전 과정을 자동 실행하고 결과 리포트를 생성한다`() {
        val client = createOpenAiClient()
        val targets = selectPersonas()
        val sessionsPerPersona = System.getProperty("curation.sessions", "1").toInt()

        println("\n===== 큐레이션 프롬프트 평가 시작 =====")
        println("카테고리: ${targets.joinToString { it.category }}")
        println("카테고리별 세션 수: ${sessionsPerPersona}회 / 총 ${targets.size * sessionsPerPersona}세션")

        val results = targets.flatMap { persona ->
            (1..sessionsPerPersona).map { round ->
                println("--- [${persona.key} #$round] 시작")
                runSession(client, persona, round).also {
                    println("--- [${persona.key} #$round] 완료 (${it.apiCalls}콜 / ${it.elapsedMillis / 1000}초)")
                }
            }
        }

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val reportDir = File("build/curation-eval/$stamp").apply { mkdirs() }

        File(reportDir, "00-summary.md").writeText(buildSummaryReport(results))
        results.groupBy { it.persona }.forEach { (persona, sessions) ->
            File(reportDir, "${persona.key}.md").writeText(buildCategoryReport(persona, sessions))
        }
        File(reportDir, "raw.csv").writeText(buildCsv(results))

        println(buildConsoleSummary(results))
        println("리포트 디렉터리: ${reportDir.absolutePath}")
    }

    /* ===================== 실행 ===================== */

    private fun runSession(client: OpenAiApiClient, persona: Persona, round: Int): SessionResult {
        val startedAt = System.currentTimeMillis()
        var apiCalls = 0

        // 1) 큐레이션 진행 - 운영과 동일한 history 구조: [A1, Q2, A2, Q3, A3, ...]
        val questions = mutableListOf<QuestionRecord>()
        val history = mutableListOf(persona.goalText)
        val previousQuestions = mutableListOf(CurationChatbotUseCase.FIRST_QUESTION)

        repeat(totalQuestions - 1) { index ->
            val baseUserMessage = ChatbotQuestionPrompt.buildUserMessage(
                goalText = persona.goalText,
                history = history,
                previousQuestions = previousQuestions
            )

            var retryHint = ""
            var attempts = 0
            val rejected = mutableListOf<String>()
            var accepted: ChatbotQuestionValidator.ParsedQuestion? = null
            var lastCandidate = ChatbotQuestionValidator.ParsedQuestion("", emptyList())
            var lastViolation: String? = null

            while (attempts < maxQuestionRetry) {
                attempts++
                val raw = client.generateText(
                    userMessage = baseUserMessage + retryHint,
                    systemMessage = ChatbotQuestionPrompt.SYSTEM_PROMPT,
                    model = OpenAiApiClient.MODEL_DEFAULT,
                    temperature = 0.7
                ).trim()
                apiCalls++

                val parsed = ChatbotQuestionValidator.parse(raw)
                val violation = ChatbotQuestionValidator.findViolation(parsed)
                lastCandidate = parsed.copy(question = ChatbotQuestionValidator.takeFirstQuestion(parsed.question))
                lastViolation = violation

                if (violation == null) {
                    accepted = lastCandidate
                    break
                }
                rejected += "${parsed.question} ${parsed.examples} → $violation"
                retryHint = ChatbotQuestionPrompt.buildRetryHint(raw, violation)
            }

            val result = accepted ?: lastCandidate
            val question = result.question
            val answer = simulateAnswer(client, persona, question).also { apiCalls++ }

            questions += QuestionRecord(
                number = index + 2,
                question = question,
                examples = result.examples,
                attempts = attempts,
                passed = accepted != null,
                violation = if (accepted != null) null else lastViolation,
                rejectedCandidates = rejected,
                answer = answer
            )

            history += question
            history += answer
            previousQuestions += question
        }

        // 2) 큐레이션 요약
        val summary = generateSummary(client, persona.goalText, history)
        apiCalls++

        val conversationRaw = buildConversationRaw(persona.goalText, questions)

        // 3) 미션 생성
        val first = generateMissions(client, persona, conversationRaw)
        apiCalls += first.attempts

        // 4) 미션 재추천 (retryWithGoal 과 동일 조건: 같은 날 + 삭제 전에 조회한 1차 미션을 중복 방지로 전달)
        val retry = generateMissions(client, persona, conversationRaw, first.missions.map { it.content })
        apiCalls += retry.attempts

        val firstContents = first.missions.map { it.content }.toSet()
        val duplicated = retry.missions.map { it.content }.filter { it in firstContents }

        return SessionResult(
            persona = persona,
            round = round,
            questions = questions,
            summary = summary,
            conversationRaw = conversationRaw,
            firstMissions = first,
            retryMissions = retry,
            duplicatedContents = duplicated,
            apiCalls = apiCalls,
            elapsedMillis = System.currentTimeMillis() - startedAt
        )
    }

    /**
     * 페르소나가 실제 사용자처럼 답하도록 시뮬레이션한다.
     * 답변 길이를 측정하는 것이 목적이므로 길이 제한을 의도적으로 주지 않는다.
     */
    private fun simulateAnswer(client: OpenAiApiClient, persona: Persona, question: String): String {
        val systemPrompt = """
당신은 목표 달성 앱을 쓰는 아래 인물입니다. 코치의 질문에 실제 사람처럼 답하세요.

【당신의 프로필】
${persona.profile}

【답변 규칙】
- 채팅으로 답하듯 자연스럽게 답합니다.
- 질문이 묻는 것에만 답합니다. 묻지 않은 정보를 스스로 덧붙이지 마세요.
- 답변 길이는 제한이 없습니다. 질문에 답하는 데 필요한 만큼만 쓰세요.
- 답변 텍스트만 출력합니다. (번호, 따옴표, 설명 금지)
"""
        return client.generateText(
            userMessage = "목표: ${persona.goalText}\n코치의 질문: $question",
            systemMessage = systemPrompt,
            model = OpenAiApiClient.MODEL_DEFAULT,
            temperature = 0.8
        ).trim().trim('"')
    }

    /** 운영 코드(CurationChatbotUseCase#generateConversationSummary)와 동일한 경로로 요약한다. */
    private fun generateSummary(client: OpenAiApiClient, goalText: String, history: List<String>): String {
        val messages = listOf(
            ChatMessage(role = "system", content = ChatbotSummaryPrompt.SYSTEM_PROMPT),
            ChatMessage(
                role = "user",
                content = ChatbotSummaryPrompt.buildConversationText(goalText, history)
            )
        )
        return client.chatCompletion(
            messages = messages,
            model = OpenAiApiClient.MODEL_DEFAULT,
            temperature = 0.5
        ).result?.message?.content?.trim() ?: "(요약 실패)"
    }

    /** 운영 코드(CurationChatbotUseCase#buildConversationRaw)와 동일한 형식으로 대화 전문을 만든다. */
    private fun buildConversationRaw(goalText: String, questions: List<QuestionRecord>): String {
        return buildString {
            append("Q1: ${CurationChatbotUseCase.FIRST_QUESTION}\n")
            append("A1: $goalText\n")
            questions.forEach {
                append("Q${it.number}: ${it.question}\n")
                append("A${it.number}: ${it.answer}\n")
            }
        }
    }

    /** 미션 15개를 생성한다. 운영 서비스와 동일하게 난이도 분포/글자수/문법 검증에 실패하면 재시도한다. */
    private fun generateMissions(
        client: OpenAiApiClient,
        persona: Persona,
        conversationRaw: String,
        pastMissions: List<String> = emptyList()
    ): MissionGenerationResult {
        val userMessage = DailyMissionFromGoalPrompt.buildUserMessage(
            goalText = persona.goalText,
            conversationContext = conversationRaw,
            pastMissions = pastMissions,
            dayNumber = 1
        )

        var attempts = 0
        var fallback: List<MissionRecord> = emptyList()
        var fallbackViolations = Int.MAX_VALUE
        val failures = mutableListOf<String>()

        repeat(maxMissionRetry) {
            attempts++
            val raw = client.generateText(
                userMessage = userMessage,
                systemMessage = DailyMissionFromGoalPrompt.SYSTEM_PROMPT,
                model = OpenAiApiClient.MODEL_DEFAULT,
                temperature = 0.5,
                maxTokens = 4096
            ).trim()

            val missions = parseMissions(raw)
            if (missions.isEmpty()) {
                failures += "${attempts}차: JSON 파싱 실패"
                return@repeat
            }

            val wrongDistribution = (1..3).filter { level ->
                missions.count { it.difficulty == level } != missionsPerDifficulty
            }
            val tooShort = missions.filter { it.description.length < minDescriptionLength }
            val tooLong = missions.filter { it.description.length > maxDescriptionLength }
            val grammarBroken = missions.filter { it.grammarViolation != null }
            val duplicated = missions.filter { it.content in pastMissions.toSet() }

            if (wrongDistribution.isEmpty() && tooShort.isEmpty() && tooLong.isEmpty() &&
                grammarBroken.isEmpty() && duplicated.isEmpty()
            ) {
                return MissionGenerationResult(missions, attempts, failures)
            }

            // 운영 코드와 동일하게, 전부 실패하면 위반이 가장 적은 결과를 fallback 으로 남긴다
            val violations = tooShort.size + tooLong.size + duplicated.size
            if (fallback.isEmpty() || violations < fallbackViolations) {
                fallback = missions
                fallbackViolations = violations
            }

            failures += buildString {
                append("${attempts}차: ")
                if (wrongDistribution.isNotEmpty()) {
                    append("난이도 분포 불일치(" + wrongDistribution.joinToString {
                        level -> "난이도 $level=${missions.count { m -> m.difficulty == level }}개"
                    } + ") ")
                }
                if (tooShort.isNotEmpty()) append("desc ${minDescriptionLength}자 미만 ${tooShort.size}개 ")
                if (tooLong.isNotEmpty()) append("desc ${maxDescriptionLength}자 초과 ${tooLong.size}개 ")
                if (grammarBroken.isNotEmpty()) append("문법 위반 ${grammarBroken.size}개 ")
                if (duplicated.isNotEmpty()) append("이미 제공한 미션과 중복 ${duplicated.size}개 ")
            }.trim()
        }

        return MissionGenerationResult(fallback, attempts, failures)
    }

    private fun parseMissions(rawResponse: String): List<MissionRecord> {
        return try {
            val cleaned = rawResponse
                .replace(Regex("^```[a-zA-Z]*\\s*"), "")
                .replace(Regex("```\\s*$"), "")
                .trim()
            val missionsNode = objectMapper.readTree(cleaned).get("missions") ?: return emptyList()
            missionsNode.mapNotNull { node ->
                val content = node.get("content")?.asText()?.trim() ?: return@mapNotNull null
                val description = node.get("description")?.asText()?.trim() ?: ""
                MissionRecord(
                    content = content,
                    description = description,
                    difficulty = node.get("difficulty")?.asInt() ?: 1,
                    grammarViolation = KoreanGrammarChecker.findViolation(content)
                        ?: KoreanGrammarChecker.findViolation(description)
                )
            }
        } catch (e: Exception) {
            println("미션 JSON 파싱 실패: ${e.message}\n응답 원문: $rawResponse")
            emptyList()
        }
    }

    /* ===================== 리포트 ===================== */

    private fun buildSummaryReport(results: List<SessionResult>): String = buildString {
        val allQuestions = results.flatMap { it.questions }
        val allMissions = results.flatMap { it.firstMissions.missions + it.retryMissions.missions }

        appendLine("# 큐레이션 프롬프트 평가 요약")
        appendLine()
        appendLine("- 실행 시각: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
        appendLine("- 모델: ${OpenAiApiClient.MODEL_DEFAULT}")
        appendLine("- 카테고리 ${results.map { it.persona.key }.distinct().size}개 / 총 ${results.size}세션")
        appendLine("- API 호출 ${results.sumOf { it.apiCalls }}회 / 총 소요 ${results.sumOf { it.elapsedMillis } / 1000}초")
        appendLine()

        appendLine("## 목표별 달성 현황")
        appendLine()
        appendLine("| 검증 항목 | 결과 |")
        appendLine("| --- | --- |")
        appendLine("| 질문 검증 통과 | ${allQuestions.count { it.passed }}/${allQuestions.size} |")
        appendLine("| 질문 재생성 발생 | ${allQuestions.sumOf { it.attempts - 1 }}회 |")
        appendLine("| 물음표 2개 이상 질문 | ${allQuestions.count { q -> q.question.count { it == '?' } > 1 }}개 |")
        appendLine("| 질문 평균 길이 | ${allQuestions.map { it.question.length }.average().toInt()}자 |")
        appendLine("| 답변 ${answerLengthTarget}자 이내 (자유 입력 시) | ${allQuestions.count { it.answer.length <= answerLengthTarget }}/${allQuestions.size} |")
        appendLine("| 답변 평균 길이 (자유 입력 시) | ${allQuestions.map { it.answer.length }.average().toInt()}자 |")
        appendLine("| 예시 답변 ${ChatbotQuestionValidator.REQUIRED_EXAMPLE_COUNT}개 제공 | ${allQuestions.count { it.examples.size == ChatbotQuestionValidator.REQUIRED_EXAMPLE_COUNT }}/${allQuestions.size} |")
        appendLine("| 예시 답변 평균 길이 | ${allExamples(allQuestions).map { it.length }.average().toInt()}자 |")
        appendLine("| 예시 답변 최장 | ${allExamples(allQuestions).maxOfOrNull { it.length } ?: 0}자 |")
        appendLine("| 미션 문법 위반 | ${allMissions.count { it.grammarViolation != null }}/${allMissions.size} |")
        appendLine("| 미션 desc ${minDescriptionLength}자 미만 | ${allMissions.count { it.description.length < minDescriptionLength }}개 |")
        appendLine("| 미션 desc ${maxDescriptionLength}자 초과 | ${allMissions.count { it.description.length > maxDescriptionLength }}개 |")
        appendLine("| 미션 desc 평균 길이 | ${allMissions.map { it.description.length }.average().toInt()}자 |")
        appendLine("| 재추천 시 직전과 중복된 미션 | ${results.sumOf { it.duplicatedContents.size }}개 / ${results.sumOf { it.retryMissions.missions.size }}개 |")
        appendLine()

        appendLine("## 세션별 지표")
        appendLine()
        appendLine("| 세션 | 카테고리 | 질문 평균 | 재생성 | 답변 ${answerLengthTarget}자 이내 | 답변 평균 | 미션 생성 시도 | 재추천 시도 | 중복 미션 | desc 초과 | 문법 위반 |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        results.forEach { r ->
            val qs = r.questions
            val missions = r.firstMissions.missions + r.retryMissions.missions
            appendLine(
                "| ${r.persona.key} #${r.round} | ${r.persona.category} | " +
                    "${qs.map { it.question.length }.average().toInt()}자 | ${qs.sumOf { it.attempts - 1 }}회 | " +
                    "${qs.count { it.answer.length <= answerLengthTarget }}/${qs.size} | " +
                    "${qs.map { it.answer.length }.average().toInt()}자 | " +
                    "${r.firstMissions.attempts}회 | ${r.retryMissions.attempts}회 | " +
                    "${r.duplicatedContents.size}/${r.retryMissions.missions.size} | " +
                    "${missions.count { it.description.length > maxDescriptionLength }}개 | " +
                    "${missions.count { it.grammarViolation != null }}개 |"
            )
        }
        appendLine()

        appendLine("## 카테고리별 질문 목록")
        appendLine()
        results.forEach { r ->
            appendLine("**${r.persona.category} #${r.round}** (${r.persona.goalText})")
            appendLine()
            r.questions.forEach { appendLine("${it.number}. ${escape(it.question)} `${it.question.length}자`") }
            appendLine()
        }
    }

    private fun buildCategoryReport(persona: Persona, sessions: List<SessionResult>): String = buildString {
        appendLine("# ${persona.category} — ${persona.goalText}")
        appendLine()
        appendLine("## 가상 사용자 프로필")
        appendLine()
        persona.profile.lines().forEach { appendLine("- $it") }
        appendLine()

        sessions.forEach { session ->
            appendLine("---")
            appendLine()
            appendLine("# 세션 #${session.round}")
            appendLine()
            appendLine("API 호출 ${session.apiCalls}회 · 소요 ${session.elapsedMillis / 1000}초")
            appendLine()

            appendLine("## 1. 큐레이션 진행")
            appendLine()
            appendLine("| # | 질문 | 질문 길이 | 예시 답변 | 재생성 | 검증 | 자유 입력 답변 | 답변 길이 |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
            appendLine(
                "| 1 | ${escape(CurationChatbotUseCase.FIRST_QUESTION)} | - | 고정 예시 4개 | - | 고정 질문 | " +
                    "${escape(persona.goalText)} | ${persona.goalText.length}자 |"
            )
            session.questions.forEach {
                val verdict = if (it.passed) "통과" else "미통과: ${it.violation}"
                val mark = if (it.answer.length > answerLengthTarget) "⚠️ " else ""
                val examples = if (it.examples.isEmpty()) "-" else it.examples.joinToString(" / ") { e -> escape(e) }
                appendLine(
                    "| ${it.number} | ${escape(it.question)} | ${it.question.length}자 | $examples | ${it.attempts - 1}회 | " +
                        "$verdict | ${escape(it.answer)} | $mark${it.answer.length}자 |"
                )
            }
            appendLine()

            val rejected = session.questions.flatMap { it.rejectedCandidates }
            if (rejected.isNotEmpty()) {
                appendLine("### 검증에서 탈락해 재생성된 질문 후보")
                appendLine()
                rejected.forEach { appendLine("- ${escape(it)}") }
                appendLine()
            }

            appendLine("## 2. 큐레이션 요약")
            appendLine()
            appendLine("> ${escape(session.summary)}")
            appendLine()

            appendLine("## 3. 미션 생성 (1차)")
            appendLine()
            appendLine("생성 시도 ${session.firstMissions.attempts}회")
            session.firstMissions.failures.forEach { appendLine("- 재시도 사유: ${escape(it)}") }
            appendLine()
            appendLine(missionTable(session.firstMissions.missions))

            appendLine("## 4. 미션 재추천 (2차)")
            appendLine()
            appendLine("생성 시도 ${session.retryMissions.attempts}회")
            session.retryMissions.failures.forEach { appendLine("- 재시도 사유: ${escape(it)}") }
            appendLine("- 1차와 제목이 완전히 같은 미션: ${session.duplicatedContents.size}개" +
                if (session.duplicatedContents.isEmpty()) "" else " (${session.duplicatedContents.joinToString { escape(it) }})")
            appendLine()
            appendLine(missionTable(session.retryMissions.missions))
        }
    }

    private fun missionTable(missions: List<MissionRecord>): String = buildString {
        appendLine("| 난이도 | 미션 제목 | 실행 방법 | 설명 길이 | 문법 |")
        appendLine("| --- | --- | --- | --- | --- |")
        missions.sortedBy { it.difficulty }.forEach {
            val level = when (it.difficulty) {
                1 -> "하"
                2 -> "중"
                else -> "상"
            }
            val lengthMark = when {
                it.description.length < minDescriptionLength -> "⚠️ 미달 "
                it.description.length > maxDescriptionLength -> "⚠️ 초과 "
                else -> ""
            }
            appendLine(
                "| $level | ${escape(it.content)} | ${escape(it.description)} | " +
                    "$lengthMark${it.description.length}자 | ${it.grammarViolation?.let { v -> "⚠️ $v" } ?: "OK"} |"
            )
        }
        appendLine()
    }

    private fun buildCsv(results: List<SessionResult>): String = buildString {
        appendLine("persona,category,round,stage,key,text,length,attempts,passed,note")
        results.forEach { r ->
            r.questions.forEach {
                appendLine(csvRow(r, "question", it.number.toString(), it.question, it.question.length, it.attempts, it.passed, it.violation))
                appendLine(csvRow(r, "answer", it.number.toString(), it.answer, it.answer.length, 1, it.answer.length <= answerLengthTarget, null))
                it.examples.forEach { example ->
                    appendLine(csvRow(r, "example", it.number.toString(), example, example.length, 1, example.length <= ChatbotQuestionValidator.MAX_EXAMPLE_LENGTH, null))
                }
            }
            appendLine(csvRow(r, "summary", "-", r.summary, r.summary.length, 1, true, null))
            r.firstMissions.missions.forEach {
                appendLine(
                    csvRow(
                        r, "mission_first", it.difficulty.toString(), "${it.content} | ${it.description}",
                        it.description.length, r.firstMissions.attempts,
                        it.description.length in minDescriptionLength..maxDescriptionLength && it.grammarViolation == null,
                        it.grammarViolation
                    )
                )
            }
            r.retryMissions.missions.forEach {
                appendLine(
                    csvRow(
                        r, "mission_retry", it.difficulty.toString(), "${it.content} | ${it.description}",
                        it.description.length, r.retryMissions.attempts,
                        it.description.length in minDescriptionLength..maxDescriptionLength && it.grammarViolation == null,
                        if (it.content in r.duplicatedContents) "1차와 중복" else it.grammarViolation
                    )
                )
            }
        }
    }

    private fun csvRow(
        result: SessionResult,
        stage: String,
        key: String,
        text: String,
        length: Int,
        attempts: Int,
        passed: Boolean,
        note: String?
    ): String {
        fun q(value: String) = "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""
        return listOf(
            result.persona.key, q(result.persona.category), result.round.toString(), stage, key,
            q(text), length.toString(), attempts.toString(), passed.toString(), q(note ?: "")
        ).joinToString(",")
    }

    private fun buildConsoleSummary(results: List<SessionResult>): String {
        val allQuestions = results.flatMap { it.questions }
        val allMissions = results.flatMap { it.firstMissions.missions + it.retryMissions.missions }
        return buildString {
            appendLine("\n===== 결과 요약 =====")
            appendLine("세션 ${results.size}개 / API 호출 ${results.sumOf { it.apiCalls }}회")
            appendLine("질문 ${allQuestions.size}개")
            appendLine("  - 검증 통과: ${allQuestions.count { it.passed }}/${allQuestions.size} (재생성 ${allQuestions.sumOf { it.attempts - 1 }}회)")
            appendLine("  - 평균 길이: ${allQuestions.map { it.question.length }.average().toInt()}자")
            appendLine("예시 답변")
            appendLine("  - ${ChatbotQuestionValidator.REQUIRED_EXAMPLE_COUNT}개 제공: ${allQuestions.count { it.examples.size == ChatbotQuestionValidator.REQUIRED_EXAMPLE_COUNT }}/${allQuestions.size}")
            appendLine("  - 평균 ${allExamples(allQuestions).map { it.length }.average().toInt()}자 / 최장 ${allExamples(allQuestions).maxOfOrNull { it.length } ?: 0}자")
            appendLine("답변 시뮬레이션 (예시를 고르지 않고 직접 입력했을 때)")
            appendLine("  - ${answerLengthTarget}자 이내: ${allQuestions.count { it.answer.length <= answerLengthTarget }}/${allQuestions.size}")
            appendLine("  - 평균 ${allQuestions.map { it.answer.length }.average().toInt()}자 / 최장 ${allQuestions.maxOf { it.answer.length }}자")
            appendLine("미션 ${allMissions.size}개 (1차 + 재추천)")
            appendLine("  - 문법 위반: ${allMissions.count { it.grammarViolation != null }}개")
            appendLine("  - desc ${minDescriptionLength}자 미만: ${allMissions.count { it.description.length < minDescriptionLength }}개")
            appendLine("  - desc ${maxDescriptionLength}자 초과: ${allMissions.count { it.description.length > maxDescriptionLength }}개")
            appendLine("  - desc 평균: ${allMissions.map { it.description.length }.average().toInt()}자")
            appendLine("재추천 중복: ${results.sumOf { it.duplicatedContents.size }}개 / ${results.sumOf { it.retryMissions.missions.size }}개")
        }
    }

    private fun allExamples(questions: List<QuestionRecord>) = questions.flatMap { it.examples }

    private fun escape(text: String) = text.replace("\n", " ").replace("|", "/")

    /* ===================== 준비 ===================== */

    private fun selectPersonas(): List<Persona> {
        val requested = System.getProperty("curation.personas", "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (requested.isEmpty()) return personas
        return personas.filter { it.key in requested }.ifEmpty {
            error("알 수 없는 페르소나입니다: $requested (사용 가능: ${personas.joinToString { it.key }})")
        }
    }

    private fun createOpenAiClient(): OpenAiApiClient {
        val apiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: loadKeyFromEnvProperties()
            ?: error(
                "OPENAI_API_KEY를 찾을 수 없습니다. " +
                    "환경변수로 넘기거나 src/main/resources/env.properties에 OPENAI_API_KEY를 설정하세요."
            )
        val model = System.getenv("OPENAI_MODEL")?.takeIf { it.isNotBlank() } ?: OpenAiApiClient.MODEL_DEFAULT

        val restClient = RestClient.builder()
            .baseUrl("https://api.openai.com/v1")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()

        return OpenAiApiClient(openAiRestClient = restClient, defaultModel = model)
    }

    private fun loadKeyFromEnvProperties(): String? {
        val stream = javaClass.classLoader.getResourceAsStream("env.properties") ?: return null
        return stream.use {
            Properties().apply { load(it) }.getProperty("OPENAI_API_KEY")?.takeIf { k -> k.isNotBlank() }
        }
    }

    /* ===================== 데이터 ===================== */

    data class Persona(
        val key: String,
        val category: String,
        val goalText: String,
        val profile: String
    )

    data class QuestionRecord(
        val number: Int,
        val question: String,
        val examples: List<String>,
        val attempts: Int,
        val passed: Boolean,
        val violation: String?,
        val rejectedCandidates: List<String>,
        val answer: String
    )

    data class MissionRecord(
        val content: String,
        val description: String,
        val difficulty: Int,
        val grammarViolation: String?
    )

    data class MissionGenerationResult(
        val missions: List<MissionRecord>,
        val attempts: Int,
        val failures: List<String>
    )

    data class SessionResult(
        val persona: Persona,
        val round: Int,
        val questions: List<QuestionRecord>,
        val summary: String,
        val conversationRaw: String,
        val firstMissions: MissionGenerationResult,
        val retryMissions: MissionGenerationResult,
        val duplicatedContents: List<String>,
        val apiCalls: Int,
        val elapsedMillis: Long
    )
}
