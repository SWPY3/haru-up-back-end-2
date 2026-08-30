package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotCompleteResponse
import com.haruUp.curation.dto.ChatbotGoalRejectedResponse
import com.haruUp.curation.dto.ChatbotMissionDto
import com.haruUp.curation.dto.ChatbotQuestionType
import com.haruUp.curation.dto.ChatbotStartResponse
import com.haruUp.global.openai.ChatMessage
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ChatbotQuestionPrompt
import com.haruUp.global.prompt.ChatbotSummaryPrompt
import com.haruUp.global.prompt.GoalValidationPrompt
import com.haruUp.goal.domain.MemberGoal
import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.mission.application.GoalBasedMissionGenerationService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
class CurationChatbotUseCase(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val openAiApiClient: OpenAiApiClient,
    private val memberGoalRepository: MemberGoalRepository,
    private val goalBasedMissionGenerationService: GoalBasedMissionGenerationService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SESSION_KEY_PREFIX = "chatbot:session:"
        private const val SESSION_TTL_MINUTES = 30L
        private const val TOTAL_QUESTIONS = 6

        /** 질문이 검증(한 정보 / 짧은 답변 / 문법)을 통과하지 못했을 때 재생성하는 최대 횟수 */
        const val MAX_QUESTION_RETRY = 3

        const val FIRST_QUESTION = "어떤 목표를 이루고 싶으신가요?\n도전하고 싶은 목표를 선택하거나 직접 입력해주세요."
        private val FIRST_QUESTION_EXAMPLES = listOf(
            "🏋️‍♀️ 체중 감량 5kg",
            "🚭 금연하기",
            "📚 토익 900점",
            "📈 주식 수익률 월 1%"
        )

        /**
         * 투자 가능 시간 질문 번호.
         *
         * 시간 투자가 필요한 목표일 때만 꼬리질문 뒤에 한 번 더 붙는 고정 질문이다.
         * AI가 만드는 질문이 아니므로 [ChatbotQuestionPrompt] 에서는 시간 질문을 전면 금지하고,
         * 여기서만 정해진 선택지로 묻는다.
         */
        const val TIME_QUESTION_NUMBER = TOTAL_QUESTIONS + 1

        const val TIME_QUESTION = "목표를 위해 하루에 쓸 수 있는 시간이 얼마인가요?"
        private val TIME_QUESTION_EXAMPLES = listOf(
            "10분 이내",
            "30분",
            "1시간 이상"
        )
    }

    /**
     * 챗봇 세션을 시작하고 첫 번째 질문을 반환합니다.
     */
    fun startChatbot(memberId: Long): ChatbotStartResponse {
        val sessionId = UUID.randomUUID().toString()
        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"

        val session = ChatbotSession(
            memberId = memberId,
            questionCount = 1,
            history = mutableListOf(),
            firstAnswer = ""
        )

        redisTemplate.opsForValue().set(
            sessionKey,
            objectMapper.writeValueAsString(session),
            Duration.ofMinutes(SESSION_TTL_MINUTES)
        )

        logger.info("챗봇 세션 시작 - memberId: $memberId, sessionId: $sessionId")

        return ChatbotStartResponse(
            sessionId = sessionId,
            question = FIRST_QUESTION,
            examples = FIRST_QUESTION_EXAMPLES,
            questionNumber = 1
        )
    }

    /**
     * 사용자의 답변을 받아 다음 질문 또는 완료 응답을 반환합니다.
     */
    @Transactional
    fun answer(request: ChatbotAnswerRequest): Any {
        val sessionKey = "$SESSION_KEY_PREFIX${request.sessionId}"
        val sessionJson = redisTemplate.opsForValue().get(sessionKey) as? String
            ?: throw IllegalArgumentException("세션을 찾을 수 없습니다. 챗봇을 다시 시작해주세요.")

        val session = objectMapper.readValue(sessionJson, ChatbotSession::class.java)

        val isGoalAnswer = session.questionCount == 1

        // 첫 번째 답변은 목표다. 꼬리질문을 만들기 전에 목표가 하나인지 먼저 검증한다.
        val goalAnalysis = if (isGoalAnswer) analyzeGoal(session.memberId, request.answer) else null
        if (goalAnalysis != null && !goalAnalysis.isSingleGoal) {
            logger.info(
                "목표 검증 실패 - memberId: ${session.memberId}, " +
                "입력: \"${request.answer}\", 감지된 목표 ${goalAnalysis.goalCount}개: ${goalAnalysis.goals}"
            )
            // 세션은 그대로 둔다. 같은 sessionId로 목표를 다시 제출하면 이 검증부터 다시 시작한다.
            return ChatbotGoalRejectedResponse(
                sessionId = request.sessionId,
                message = GoalValidator.MULTIPLE_GOAL_MESSAGE,
                detectedGoals = goalAnalysis.goals
            )
        }

        // 첫 번째 답변이면 firstAnswer 저장
        val updatedFirstAnswer = if (isGoalAnswer) request.answer else session.firstAnswer

        // 시간 투자 필요 여부는 목표를 받은 시점에만 판정하고, 이후 단계에서 재사용한다.
        val requiresTimeInvestment = goalAnalysis?.requiresTime ?: session.requiresTimeInvestment

        // history에 현재 답변 추가
        val updatedHistory = session.history.toMutableList().also { it.add(request.answer) }

        return if (session.questionCount < TOTAL_QUESTIONS) {
            // 꼬리질문 생성 (질문 + 예시 답변 3개)
            val next = generateFollowUpQuestion(
                goalText = updatedFirstAnswer,
                history = updatedHistory
            )
            val nextQuestion = next.question
            val nextQuestionCount = session.questionCount + 1
            // 시간 투자가 필요한 목표면 꼬리질문 뒤에 고정 시간 질문이 한 번 더 붙는다.
            val isLast = nextQuestionCount == TOTAL_QUESTIONS && requiresTimeInvestment != true

            // 세션 업데이트 (history에 방금 생성된 질문도 추가)
            val questionHistory = updatedHistory.toMutableList().also { it.add(nextQuestion) }
            val updatedSession = session.copy(
                questionCount = nextQuestionCount,
                history = questionHistory,
                firstAnswer = updatedFirstAnswer,
                requiresTimeInvestment = requiresTimeInvestment
            )
            redisTemplate.opsForValue().set(
                sessionKey,
                objectMapper.writeValueAsString(updatedSession),
                Duration.ofMinutes(SESSION_TTL_MINUTES)
            )

            ChatbotAnswerResponse(
                sessionId = request.sessionId,
                question = nextQuestion,
                examples = next.examples,
                questionNumber = nextQuestionCount,
                isLast = isLast
            )
        } else if (session.questionCount == TOTAL_QUESTIONS && requiresTimeInvestment == true) {
            // 마지막 꼬리질문까지 답변 완료 → 투자 가능 시간을 고정 선택지로 한 번 더 묻는다.
            val questionHistory = updatedHistory.toMutableList().also { it.add(TIME_QUESTION) }
            val updatedSession = session.copy(
                questionCount = TIME_QUESTION_NUMBER,
                history = questionHistory,
                firstAnswer = updatedFirstAnswer,
                requiresTimeInvestment = true
            )
            redisTemplate.opsForValue().set(
                sessionKey,
                objectMapper.writeValueAsString(updatedSession),
                Duration.ofMinutes(SESSION_TTL_MINUTES)
            )

            logger.info("투자 가능 시간 질문 반환 - memberId: ${session.memberId}, 목표: $updatedFirstAnswer")

            ChatbotAnswerResponse(
                sessionId = request.sessionId,
                question = TIME_QUESTION,
                examples = TIME_QUESTION_EXAMPLES,
                questionNumber = TIME_QUESTION_NUMBER,
                isLast = true,
                questionType = ChatbotQuestionType.FIXED_TIME
            )
        } else {
            // 6번째 질문 답변 완료 → 대화 종료 처리
            val allHistory = updatedHistory

            // 대화 요약 생성
            val conversationSummary = generateConversationSummary(
                goalText = updatedFirstAnswer,
                history = allHistory
            )
            logger.info("챗봇 대화 요약 생성 완료 - memberId: ${session.memberId}, 목표: $updatedFirstAnswer, 요약: $conversationSummary")

            // 기존 활성 목표 비활성화
            memberGoalRepository.findByMemberIdAndIsActiveTrue(session.memberId)?.let { existingGoal ->
                existingGoal.deactivate()
                memberGoalRepository.save(existingGoal)
            }

            // 원본 대화 JSON으로 변환
            val conversationRaw = buildConversationRaw(allHistory)

            // 새 MemberGoal 저장
            val memberGoal = MemberGoal(
                memberId = session.memberId,
                goalText = updatedFirstAnswer,
                conversationSummary = conversationSummary,
                conversationRaw = conversationRaw,
                isActive = true
            )
            memberGoalRepository.save(memberGoal)

            // 즉시 오늘의 미션 생성 (새 목표 시작일 = 오늘)
            val savedMissions = goalBasedMissionGenerationService.generateAndSaveMissions(
                memberId = session.memberId,
                goalText = updatedFirstAnswer,
                conversationRaw = conversationRaw,
                goalStartDate = java.time.LocalDate.now()
            )

            // Redis 세션 삭제
            redisTemplate.delete(sessionKey)

            logger.info("챗봇 대화 완료 및 미션 생성 - memberId: ${session.memberId}, sessionId: ${request.sessionId}")

            ChatbotCompleteResponse(
                isCompleted = true,
                goalText = updatedFirstAnswer,
                missions = savedMissions.map { entity ->
                    ChatbotMissionDto(
                        id = entity.id!!,
                        missionContent = entity.missionContent,
                        missionDescription = entity.missionDescription,
                        difficulty = entity.difficulty ?: 1,
                        expEarned = entity.expEarned
                    )
                }
            )
        }
    }

    /**
     * OpenAI를 사용하여 꼬리질문과 예시 답변 3개를 생성합니다.
     *
     * 생성 결과가 [ChatbotQuestionValidator] 검증(한 질문 = 한 정보 / 30자 이내 답변 가능 / 한국어 문법 /
     * 예시 답변 3개)을 통과하지 못하면 탈락 사유를 프롬프트에 덧붙여 최대 [MAX_QUESTION_RETRY]회까지 재생성합니다.
     */
    private fun generateFollowUpQuestion(
        goalText: String,
        history: List<String>
    ): ChatbotQuestionValidator.ParsedQuestion {
        // history 구조: [A1, Q2, A2, Q3, ...] - 홀수 인덱스(1,3,5...)가 AI 질문
        // Q1(고정 첫 질문) + 이미 생성된 AI 질문들을 중복 방지 목록으로 전달
        val previousQuestions = buildList {
            add(FIRST_QUESTION)
            history.filterIndexed { index, _ -> index % 2 == 1 }.forEach { add(it) }
        }

        val baseUserMessage = ChatbotQuestionPrompt.buildUserMessage(goalText, history, previousQuestions)

        // 검증에 모두 실패했을 때 사용할 후보 (질문 1개로 잘라낸 형태)
        var fallback: ChatbotQuestionValidator.ParsedQuestion? = null
        var retryHint = ""

        repeat(MAX_QUESTION_RETRY) { attempt ->
            val raw = openAiApiClient.generateText(
                userMessage = baseUserMessage + retryHint,
                systemMessage = ChatbotQuestionPrompt.SYSTEM_PROMPT,
                model = OpenAiApiClient.MODEL_DEFAULT,
                temperature = 0.7
            ).trim()

            val parsed = ChatbotQuestionValidator.parse(raw)
            if (parsed.question.isBlank()) {
                logger.warn("꼬리질문 생성 결과가 비어 있음 (시도 ${attempt + 1}/$MAX_QUESTION_RETRY) - 원문: \"$raw\"")
                return@repeat
            }

            val violation = ChatbotQuestionValidator.findViolation(parsed)
            // 여러 질문이 붙어 나온 경우를 대비해 항상 첫 번째 질문까지만 사용
            val result = parsed.copy(question = ChatbotQuestionValidator.takeFirstQuestion(parsed.question))

            if (violation == null) {
                if (attempt > 0) {
                    logger.info("꼬리질문 검증 통과 (${attempt + 1}번째 시도) - 질문: \"${result.question}\"")
                }
                return result
            }

            fallback = result
            retryHint = ChatbotQuestionPrompt.buildRetryHint(raw, violation)
            logger.warn(
                "꼬리질문 검증 실패 (시도 ${attempt + 1}/$MAX_QUESTION_RETRY) " +
                "- 사유: $violation, 질문: \"${parsed.question}\", 예시: ${parsed.examples}"
            )
        }

        return fallback?.also {
            logger.warn("${MAX_QUESTION_RETRY}회 시도 후에도 검증을 통과하지 못해 마지막 질문을 사용합니다 - 질문: \"${it.question}\"")
        } ?: throw IllegalStateException("${MAX_QUESTION_RETRY}회 시도 후에도 꼬리질문 생성에 실패했습니다.")
    }

    /**
     * 전체 Q&A 대화를 가독성 있는 텍스트 형식으로 변환합니다.
     * finalHistory 구조: [A1, Q2, A2, Q3, A3, Q4, A4, Q5, A5, Q6, A6]
     * 시간 투자가 필요한 목표면 뒤에 [Q7(투자 가능 시간), A7]이 더 붙는다.
     */
    private fun buildConversationRaw(finalHistory: List<String>): String {
        return buildString {
            append("Q1: $FIRST_QUESTION\n")
            finalHistory.forEachIndexed { index, text ->
                if (index % 2 == 0) {
                    // 사용자 답변 (index 0, 2, 4, 6, 8)
                    val num = index / 2 + 1
                    append("A$num: $text\n")
                } else {
                    // AI 질문 (index 1, 3, 5, 7)
                    val num = index / 2 + 2
                    append("Q$num: $text\n")
                }
            }
        }
    }

    /**
     * OpenAI로 사용자가 입력한 목표를 분석합니다.
     *
     * 목표가 하나인지, 하루 중 일정 시간을 떼어 써야 하는 유형인지 판정합니다.
     * 호출이 실패하면 사용자를 막지 않도록 통과 처리합니다.
     * (잘못된 차단은 이탈로 이어지지만, 잘못된 통과는 질문이 한 번 더 나가는 정도의 비용입니다.)
     */
    private fun analyzeGoal(memberId: Long, goalText: String): GoalValidator.GoalAnalysis {
        val raw = try {
            openAiApiClient.generateText(
                userMessage = GoalValidationPrompt.buildUserMessage(goalText),
                systemMessage = GoalValidationPrompt.SYSTEM_PROMPT,
                model = OpenAiApiClient.MODEL_DEFAULT,
                temperature = 0.0
            ).trim()
        } catch (e: Exception) {
            logger.warn("목표 분석 호출 실패 - memberId: $memberId, 목표: \"$goalText\", 오류: ${e.message}")
            return GoalValidator.parse("")
        }

        return GoalValidator.parse(raw).also {
            logger.info(
                "목표 분석 완료 - memberId: $memberId, 목표: \"$goalText\", " +
                "목표 수: ${it.goalCount}, 시간 투자 필요: ${it.requiresTime}"
            )
        }
    }

    /**
     * OpenAI를 사용하여 전체 대화를 요약합니다.
     */
    private fun generateConversationSummary(goalText: String, history: List<String>): String {
        val conversationText = ChatbotSummaryPrompt.buildConversationText(goalText, history)

        val messages = listOf(
            ChatMessage(role = "system", content = ChatbotSummaryPrompt.SYSTEM_PROMPT),
            ChatMessage(role = "user", content = conversationText)
        )

        return openAiApiClient.chatCompletion(
            messages = messages,
            model = OpenAiApiClient.MODEL_DEFAULT,
            temperature = 0.5
        ).result?.message?.content?.trim()
            ?: "목표 달성을 위한 대화를 완료했습니다."
    }
}

/**
 * Redis에 저장되는 챗봇 세션 데이터
 */
data class ChatbotSession(
    val memberId: Long,
    val questionCount: Int,
    val history: List<String>,
    val firstAnswer: String,

    /**
     * 목표가 하루 중 일정 시간을 떼어 써야 이룰 수 있는 유형인지.
     * 목표 입력 검증([GoalValidator])에서 판정하며, 투자 가능 시간을 따로 묻는 단계에서 사용한다.
     * 아직 목표를 입력받지 않은 세션에서는 null이다.
     */
    val requiresTimeInvestment: Boolean? = null
)
