package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.character.application.CharacterUseCase
import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotCompleteResponse
import com.haruUp.curation.dto.ChatbotFinishConfirmResponse
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
    private val characterUseCase: CharacterUseCase,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SESSION_KEY_PREFIX = "chatbot:session:"
        private const val SESSION_TTL_MINUTES = 30L

        /**
         * 꼬리질문 개수는 대화 상황에 따라 달라진다. (목표 입력은 포함하지 않는다)
         *
         * - 최소 [MIN_FOLLOW_UP_QUESTIONS]개는 무조건 묻는다. 그 전에는 AI가 충분하다고 해도 마무리하지 않는다.
         * - 그 이후에는 AI가 "미션을 만들 만큼 정보가 모였다"고 판단하면 사용자에게 마무리할지 확인한다.
         * - 사용자가 계속을 선택해도 [MAX_FOLLOW_UP_QUESTIONS]개에서 강제로 마무리한다.
         */
        const val MIN_FOLLOW_UP_QUESTIONS = 3
        const val MAX_FOLLOW_UP_QUESTIONS = 8

        const val FINISH_CONFIRM_QUESTION = "이대로 마무리할까요?"
        const val FINISH_CONFIRM_YES = "네, 미션 만들어주세요"
        const val FINISH_CONFIRM_NO = "아니요, 더 이야기할게요"
        private val FINISH_CONFIRM_EXAMPLES = listOf(FINISH_CONFIRM_YES, FINISH_CONFIRM_NO)

        /** 부정을 먼저 걸러내야 "아니요, 미션 만들어주세요" 같은 답을 긍정으로 잘못 읽지 않는다. */
        private val NEGATIVE_ANSWER_REGEX = Regex("아니|아뇨|싫|더 (이야기|얘기|대화|물어)|계속|안 ?할래|나중")
        private val AFFIRMATIVE_ANSWER_REGEX = Regex("네|예|응|좋|만들|마무리|끝|그만|충분|시작")

        /** 질문이 검증(한 정보 / 짧은 답변 / 문법)을 통과하지 못했을 때 재생성하는 최대 횟수 */
        const val MAX_QUESTION_RETRY = 3

        /**
         * 첫 질문 문구를 회원이 고른 캐릭터 이름으로 만든다.
         * 캐릭터를 고르지 않았으면 기본 이름이 들어간다.
         */
        fun firstQuestion(characterName: String) =
            "이루고 싶은 목표 한 개를 직접 입력해주세요.\n" +
            "자세하게 작성할수록 ${characterName}가 더 정확한 질문을 할 수 있어요."

        /** 캐릭터를 고르지 않은 경우의 첫 질문. 이전 세션 호환과 평가 하네스에서 쓴다. */
        val FIRST_QUESTION = firstQuestion(CharacterUseCase.DEFAULT_CHARACTER_NAME)

        /**
         * 첫 질문 입력란의 placeholder.
         *
         * 선택형 예시를 제공하면 사용자가 예시를 그대로 골라 목표가 구체화되지 않는 문제가 있어,
         * 고를 수 없는 placeholder 형태로만 예시를 보여준다.
         */
        const val FIRST_QUESTION_PLACEHOLDER = "예시) 근육 향상 및 체력 증진 / 월 주식 투자 수익 30만원 / 금연하기"

        /**
         * 투자 가능 시간 질문.
         *
         * 시간 투자가 필요한 목표일 때만 꼬리질문이 모두 끝난 뒤 한 번 더 붙는 고정 질문이다.
         * 꼬리질문 개수가 가변이라 질문 번호도 고정이 아니다.
         * AI가 만드는 질문이 아니므로 [ChatbotQuestionPrompt] 에서는 시간 질문을 전면 금지하고,
         * 여기서만 정해진 선택지로 묻는다.
         */
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

        // 캐릭터 이름과 성격은 세션 시작 시 한 번만 조회해 세션에 담아 둔다.
        val profile = characterUseCase.getCurationProfile(memberId)
        val firstQuestionText = firstQuestion(profile.name)

        val session = ChatbotSession(
            memberId = memberId,
            questionCount = 1,
            history = mutableListOf(),
            firstAnswer = "",
            personality = profile.personality,
            firstQuestionText = firstQuestionText
        )

        redisTemplate.opsForValue().set(
            sessionKey,
            objectMapper.writeValueAsString(session),
            Duration.ofMinutes(SESSION_TTL_MINUTES)
        )

        logger.info(
            "챗봇 세션 시작 - memberId: $memberId, sessionId: $sessionId, " +
            "캐릭터: ${profile.name}, 성격: ${profile.personality}"
        )

        return ChatbotStartResponse(
            sessionId = sessionId,
            question = firstQuestionText,
            characterName = profile.name,
            examples = emptyList(),
            placeholder = FIRST_QUESTION_PLACEHOLDER,
            questionNumber = 1
        )
    }

    /**
     * 사용자의 답변을 받아 다음 질문 또는 완료 응답을 반환합니다.
     *
     * 꼬리질문 개수는 고정이 아니다. 최소 [MIN_FOLLOW_UP_QUESTIONS]개를 채운 뒤부터는
     * AI가 정보가 충분한지 판단하고, 충분하면 사용자에게 마무리할지 확인한다.
     * 사용자가 계속을 선택해도 [MAX_FOLLOW_UP_QUESTIONS]개에서 강제로 마무리한다.
     */
    @Transactional
    fun answer(request: ChatbotAnswerRequest): Any {
        val sessionKey = "$SESSION_KEY_PREFIX${request.sessionId}"
        val sessionJson = redisTemplate.opsForValue().get(sessionKey) as? String
            ?: throw IllegalArgumentException("세션을 찾을 수 없습니다. 챗봇을 다시 시작해주세요.")

        val session = objectMapper.readValue(sessionJson, ChatbotSession::class.java)

        // 마무리 확인에 대한 답변은 꼬리질문 답변이 아니라 예/아니오 선택이다.
        if (session.awaitingFinishConfirmation) {
            return handleFinishConfirmation(sessionKey, session, request)
        }

        // 투자 가능 시간 답변이 마지막 단계다.
        if (session.awaitingTimeAnswer) {
            val history = session.history.toMutableList().also { it.add(request.answer) }
            return completeCuration(sessionKey, session, request.sessionId, history, session.pendingSummary)
        }

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

        val updatedFirstAnswer = if (isGoalAnswer) request.answer else session.firstAnswer
        // 시간 투자 필요 여부는 목표를 받은 시점에만 판정하고, 이후 단계에서 재사용한다.
        val requiresTimeInvestment = goalAnalysis?.requiresTime ?: session.requiresTimeInvestment
        val updatedHistory = session.history.toMutableList().also { it.add(request.answer) }

        val base = session.copy(
            firstAnswer = updatedFirstAnswer,
            requiresTimeInvestment = requiresTimeInvestment
        )

        // 방금 받은 답변까지 반영한 꼬리질문 답변 수 (목표 답변은 세지 않는다)
        val answeredFollowUps = session.questionCount - 1

        if (answeredFollowUps >= MAX_FOLLOW_UP_QUESTIONS) {
            logger.info(
                "꼬리질문 상한 도달 - memberId: ${session.memberId}, " +
                "${MAX_FOLLOW_UP_QUESTIONS}개 답변 완료로 대화를 마무리합니다."
            )
            return finishOrAskTime(sessionKey, base, request.sessionId, updatedHistory, pendingSummary = null)
        }

        return askNextQuestionOrConfirm(
            sessionKey = sessionKey,
            session = base,
            sessionId = request.sessionId,
            history = updatedHistory,
            answeredFollowUps = answeredFollowUps,
            forceQuestion = false
        )
    }

    /**
     * 마무리 확인에 대한 사용자의 선택을 처리합니다.
     *
     * 마무리를 고르면 확인 화면에 보여준 요약을 그대로 재사용해 대화를 끝내고,
     * 계속을 고르면 AI가 충분하다고 판단했더라도 질문을 하나 더 만듭니다.
     */
    private fun handleFinishConfirmation(
        sessionKey: String,
        session: ChatbotSession,
        request: ChatbotAnswerRequest
    ): Any {
        val wantsToFinish = isAffirmative(request.answer)
        val cleared = session.copy(awaitingFinishConfirmation = false)

        if (wantsToFinish) {
            logger.info("사용자가 마무리를 선택 - memberId: ${session.memberId}")
            return finishOrAskTime(
                sessionKey, cleared, request.sessionId, session.history, session.pendingSummary
            )
        }

        logger.info("사용자가 대화 계속을 선택 - memberId: ${session.memberId}")
        return askNextQuestionOrConfirm(
            sessionKey = sessionKey,
            // 확인 화면에서 보여준 요약은 대화가 이어지면 더 이상 유효하지 않다.
            session = cleared.copy(pendingSummary = null),
            sessionId = request.sessionId,
            history = session.history,
            answeredFollowUps = session.questionCount - 1,
            // 사용자가 계속을 원했으므로 이번에는 반드시 질문을 만든다.
            forceQuestion = true
        )
    }

    /**
     * 다음 꼬리질문을 만들거나, AI가 충분하다고 판단하면 마무리 확인 화면을 반환합니다.
     *
     * @param answeredFollowUps 지금까지 받은 꼬리질문 답변 수
     * @param forceQuestion true면 AI가 충분하다고 해도 질문을 만든다
     */
    private fun askNextQuestionOrConfirm(
        sessionKey: String,
        session: ChatbotSession,
        sessionId: String,
        history: List<String>,
        answeredFollowUps: Int,
        forceQuestion: Boolean
    ): Any {
        // 최소 개수를 채우기 전에는 충분 판정을 허용하지 않는다.
        val canFinish = !forceQuestion && answeredFollowUps >= MIN_FOLLOW_UP_QUESTIONS

        val next = generateFollowUpQuestion(
            goalText = session.firstAnswer,
            history = history,
            canFinish = canFinish,
            personality = session.personality,
            firstQuestionText = session.firstQuestionText ?: FIRST_QUESTION
        )

        if (next.sufficient) {
            val summaries = generateConversationSummary(session.firstAnswer, history)
            logger.info(
                "정보 충분 판정 - memberId: ${session.memberId}, " +
                "꼬리질문 ${answeredFollowUps}개 후 마무리 확인을 요청합니다."
            )

            val updatedSession = session.copy(
                history = history,
                awaitingFinishConfirmation = true,
                pendingSummary = summaries
            )
            saveSession(sessionKey, updatedSession)

            return ChatbotFinishConfirmResponse(
                sessionId = sessionId,
                summary = summaries.brief,
                question = FINISH_CONFIRM_QUESTION,
                examples = FINISH_CONFIRM_EXAMPLES,
                answeredCount = answeredFollowUps
            )
        }

        val nextQuestionCount = session.questionCount + 1
        val questionHistory = history.toMutableList().also { it.add(next.question) }
        val updatedSession = session.copy(
            questionCount = nextQuestionCount,
            history = questionHistory
        )
        saveSession(sessionKey, updatedSession)

        return ChatbotAnswerResponse(
            sessionId = sessionId,
            question = next.question,
            examples = next.examples,
            questionNumber = nextQuestionCount,
            // 상한에 도달했고 시간 질문도 없다면 이번이 마지막 질문이다.
            isLast = nextQuestionCount - 1 >= MAX_FOLLOW_UP_QUESTIONS &&
                session.requiresTimeInvestment != true
        )
    }

    /**
     * 시간 투자가 필요한 목표면 투자 가능 시간을 한 번 더 묻고, 아니면 바로 대화를 마무리합니다.
     */
    private fun finishOrAskTime(
        sessionKey: String,
        session: ChatbotSession,
        sessionId: String,
        history: List<String>,
        pendingSummary: ConversationSummaryParser.ConversationSummaries?
    ): Any {
        if (session.requiresTimeInvestment != true) {
            return completeCuration(sessionKey, session, sessionId, history, pendingSummary)
        }

        val questionHistory = history.toMutableList().also { it.add(TIME_QUESTION) }
        val updatedSession = session.copy(
            history = questionHistory,
            awaitingTimeAnswer = true,
            // 시간 답변이 더해지면 요약이 달라지므로 확인 화면에서 만든 요약은 버린다.
            pendingSummary = null
        )
        saveSession(sessionKey, updatedSession)

        logger.info("투자 가능 시간 질문 반환 - memberId: ${session.memberId}, 목표: ${session.firstAnswer}")

        return ChatbotAnswerResponse(
            sessionId = sessionId,
            question = TIME_QUESTION,
            examples = TIME_QUESTION_EXAMPLES,
            questionNumber = session.questionCount + 1,
            isLast = true,
            questionType = ChatbotQuestionType.FIXED_TIME
        )
    }

    /**
     * 목표를 저장하고 오늘의 미션을 생성해 대화를 종료합니다.
     *
     * @param pendingSummary 마무리 확인 화면에서 이미 만든 요약. 있으면 재생성하지 않는다.
     */
    private fun completeCuration(
        sessionKey: String,
        session: ChatbotSession,
        sessionId: String,
        history: List<String>,
        pendingSummary: ConversationSummaryParser.ConversationSummaries?
    ): ChatbotCompleteResponse {
        val summaries = pendingSummary ?: generateConversationSummary(session.firstAnswer, history)
        logger.info(
            "챗봇 대화 요약 생성 완료 - memberId: ${session.memberId}, 목표: ${session.firstAnswer}, " +
            "상세 요약: ${summaries.detailed}, 사용자 요약: ${summaries.brief}"
        )

        // 기존 활성 목표 비활성화
        memberGoalRepository.findByMemberIdAndIsActiveTrue(session.memberId)?.let { existingGoal ->
            existingGoal.deactivate()
            memberGoalRepository.save(existingGoal)
        }

        val conversationRaw = buildConversationRaw(history, session.firstQuestionText ?: FIRST_QUESTION)

        val memberGoal = MemberGoal(
            memberId = session.memberId,
            goalText = session.firstAnswer,
            conversationSummary = summaries.detailed,
            userSummary = summaries.brief,
            conversationRaw = conversationRaw,
            isActive = true
        )
        memberGoalRepository.save(memberGoal)

        // 즉시 오늘의 미션 생성 (새 목표 시작일 = 오늘)
        val savedMissions = goalBasedMissionGenerationService.generateAndSaveMissions(
            memberId = session.memberId,
            goalText = session.firstAnswer,
            conversationRaw = conversationRaw,
            goalStartDate = java.time.LocalDate.now()
        )

        redisTemplate.delete(sessionKey)

        logger.info("챗봇 대화 완료 및 미션 생성 - memberId: ${session.memberId}, sessionId: $sessionId")

        return ChatbotCompleteResponse(
            isCompleted = true,
            goalText = session.firstAnswer,
            summary = summaries.brief,
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

    /**
     * 마무리 확인 답변이 긍정인지 판단합니다.
     *
     * 프론트는 제시한 선택지를 그대로 보내지만, 사용자가 직접 입력할 수도 있어
     * 부정 표현을 먼저 걸러낸 뒤 긍정 여부를 본다.
     */
    private fun isAffirmative(answer: String): Boolean {
        val normalized = answer.trim()
        if (NEGATIVE_ANSWER_REGEX.containsMatchIn(normalized)) return false
        return AFFIRMATIVE_ANSWER_REGEX.containsMatchIn(normalized)
    }

    private fun saveSession(sessionKey: String, session: ChatbotSession) {
        redisTemplate.opsForValue().set(
            sessionKey,
            objectMapper.writeValueAsString(session),
            Duration.ofMinutes(SESSION_TTL_MINUTES)
        )
    }

    /**
     * OpenAI를 사용하여 꼬리질문과 예시 답변 3개를 생성합니다.
     *
     * 생성 결과가 [ChatbotQuestionValidator] 검증(한 질문 = 한 정보 / 30자 이내 답변 가능 / 한국어 문법 /
     * 예시 답변 3개 / 의미 중복)을 통과하지 못하면 탈락 사유를 프롬프트에 덧붙여
     * 최대 [MAX_QUESTION_RETRY]회까지 재생성합니다.
     *
     * @param canFinish true면 모델이 질문 대신 "정보가 충분하다"고 응답할 수 있습니다.
     */
    private fun generateFollowUpQuestion(
        goalText: String,
        history: List<String>,
        canFinish: Boolean,
        personality: CharacterPersonality?,
        firstQuestionText: String
    ): ChatbotQuestionValidator.ParsedQuestion {
        // history 구조: [A1, Q2, A2, Q3, ...] - 홀수 인덱스(1,3,5...)가 AI 질문
        // Q1(고정 첫 질문) + 이미 생성된 AI 질문들을 중복 방지 목록으로 전달
        val previousQuestions = buildList {
            add(firstQuestionText)
            history.filterIndexed { index, _ -> index % 2 == 1 }.forEach { add(it) }
        }

        val baseUserMessage = ChatbotQuestionPrompt.buildUserMessage(
            goalText, history, previousQuestions, canFinish, personality
        )

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
            if (parsed.sufficient) {
                return parsed
            }
            if (parsed.question.isBlank()) {
                logger.warn("꼬리질문 생성 결과가 비어 있음 (시도 ${attempt + 1}/$MAX_QUESTION_RETRY) - 원문: \"$raw\"")
                return@repeat
            }

            val violation = ChatbotQuestionValidator.findViolation(parsed, previousQuestions)
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
    private fun buildConversationRaw(finalHistory: List<String>, firstQuestionText: String): String {
        return buildString {
            append("Q1: $firstQuestionText\n")
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
     *
     * 한 번의 호출로 용도가 다른 요약 2개를 받습니다.
     * - detailed: 미션 생성 프롬프트에 넣는 상세 요약
     * - brief: 사용자 화면에 그대로 보여주는 짧은 요약
     */
    private fun generateConversationSummary(
        goalText: String,
        history: List<String>
    ): ConversationSummaryParser.ConversationSummaries {
        val conversationText = ChatbotSummaryPrompt.buildConversationText(goalText, history)

        val messages = listOf(
            ChatMessage(role = "system", content = ChatbotSummaryPrompt.SYSTEM_PROMPT),
            ChatMessage(role = "user", content = conversationText)
        )

        val raw = openAiApiClient.chatCompletion(
            messages = messages,
            model = OpenAiApiClient.MODEL_DEFAULT,
            temperature = 0.5
        ).result?.message?.content.orEmpty()

        return ConversationSummaryParser.parse(raw)
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
    val requiresTimeInvestment: Boolean? = null,

    /**
     * 마무리 확인 질문을 던져 놓고 사용자의 예/아니오를 기다리는 중인지.
     * 이 상태에서 들어온 답변은 꼬리질문의 답변이 아니라 마무리 여부 선택으로 해석한다.
     */
    val awaitingFinishConfirmation: Boolean = false,

    /** 투자 가능 시간 질문을 던져 놓고 답변을 기다리는 중인지 */
    val awaitingTimeAnswer: Boolean = false,

    /**
     * 마무리 확인 화면에 보여준 요약.
     * 사용자가 그대로 마무리를 선택하면 재생성하지 않고 이 값을 그대로 쓴다.
     */
    val pendingSummary: ConversationSummaryParser.ConversationSummaries? = null,

    /** 꼬리질문 말투에 반영할 AI 성격. 세션 시작 시 정해진다. */
    val personality: CharacterPersonality? = null,

    /**
     * 이 세션에서 실제로 사용한 첫 질문 문구.
     * 캐릭터 이름이 들어가 회원마다 달라지므로, 중복 방지 목록과 대화 전문에 그대로 쓴다.
     */
    val firstQuestionText: String? = null
)
