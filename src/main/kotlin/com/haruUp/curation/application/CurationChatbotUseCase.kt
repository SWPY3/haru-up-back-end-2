package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotCompleteResponse
import com.haruUp.curation.dto.ChatbotStartResponse
import com.haruUp.global.clova.ClovaApiClient
import com.haruUp.global.clova.ChatbotQuestionPrompt
import com.haruUp.global.clova.ChatMessage
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
    private val clovaApiClient: ClovaApiClient,
    private val memberGoalRepository: MemberGoalRepository,
    private val goalBasedMissionGenerationService: GoalBasedMissionGenerationService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SESSION_KEY_PREFIX = "chatbot:session:"
        private const val SESSION_TTL_MINUTES = 30L
        private const val TOTAL_QUESTIONS = 5

        private const val FIRST_QUESTION = "어떤 목표를 이루고 싶으신가요?\n도전하고 싶은 목표를 선택하거나 직접 입력해주세요."
        private val FIRST_QUESTION_EXAMPLES = listOf(
            "🏋️‍♀️ 체중 감량 5kg",
            "🚭 금연하기",
            "📚 토익 900점",
            "📈 주식 수익률 월 1%"
        )

        private const val SUMMARY_SYSTEM_PROMPT = """
당신은 대화 요약 AI입니다.
사용자와의 목표 관련 대화를 3~5문장으로 간결하게 요약해주세요.
요약에는 사용자의 목표, 현재 상황, 생활 패턴, 어려운 점이 포함되어야 합니다.
요약문만 출력하세요 (설명이나 제목 없이).
"""
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

        // 첫 번째 답변이면 firstAnswer 저장
        val updatedFirstAnswer = if (session.questionCount == 1) {
            request.answer
        } else {
            session.firstAnswer
        }

        // history에 현재 답변 추가
        val updatedHistory = session.history.toMutableList().also { it.add(request.answer) }

        return if (session.questionCount < TOTAL_QUESTIONS) {
            // 꼬리질문 생성
            val nextQuestion = generateFollowUpQuestion(
                goalText = updatedFirstAnswer,
                history = updatedHistory
            )
            val nextQuestionCount = session.questionCount + 1
            val isLast = nextQuestionCount == TOTAL_QUESTIONS

            // 세션 업데이트 (history에 방금 생성된 질문도 추가)
            val questionHistory = updatedHistory.toMutableList().also { it.add(nextQuestion) }
            val updatedSession = session.copy(
                questionCount = nextQuestionCount,
                history = questionHistory,
                firstAnswer = updatedFirstAnswer
            )
            redisTemplate.opsForValue().set(
                sessionKey,
                objectMapper.writeValueAsString(updatedSession),
                Duration.ofMinutes(SESSION_TTL_MINUTES)
            )

            ChatbotAnswerResponse(
                sessionId = request.sessionId,
                question = nextQuestion,
                questionNumber = nextQuestionCount,
                isLast = isLast
            )
        } else {
            // 5번째 질문 답변 완료 → 대화 종료 처리
            val allHistory = updatedHistory

            // 대화 요약 생성
            val conversationSummary = generateConversationSummary(
                goalText = updatedFirstAnswer,
                history = allHistory
            )

            // 기존 활성 목표 비활성화
            memberGoalRepository.findByMemberIdAndIsActiveTrue(session.memberId)?.let { existingGoal ->
                existingGoal.deactivate()
                memberGoalRepository.save(existingGoal)
            }

            // 새 MemberGoal 저장
            val memberGoal = MemberGoal(
                memberId = session.memberId,
                goalText = updatedFirstAnswer,
                conversationSummary = conversationSummary,
                isActive = true
            )
            memberGoalRepository.save(memberGoal)

            // 즉시 오늘의 미션 생성 (새 목표 시작일 = 오늘)
            goalBasedMissionGenerationService.generateAndSaveMissions(
                memberId = session.memberId,
                goalText = updatedFirstAnswer,
                conversationSummary = conversationSummary,
                goalStartDate = java.time.LocalDate.now()
            )

            // Redis 세션 삭제
            redisTemplate.delete(sessionKey)

            logger.info("챗봇 대화 완료 및 미션 생성 - memberId: ${session.memberId}, sessionId: ${request.sessionId}")

            ChatbotCompleteResponse(
                isCompleted = true,
                goalText = updatedFirstAnswer
            )
        }
    }

    /**
     * Clova AI를 사용하여 꼬리질문을 생성합니다.
     */
    private fun generateFollowUpQuestion(goalText: String, history: List<String>): String {
        val userMessage = ChatbotQuestionPrompt.buildUserMessage(goalText, history)
        return clovaApiClient.generateText(
            userMessage = userMessage,
            systemMessage = ChatbotQuestionPrompt.SYSTEM_PROMPT,
            model = ClovaApiClient.MODEL_HCX_003,
            temperature = 0.7
        ).trim()
    }

    /**
     * Clova AI를 사용하여 전체 대화를 요약합니다.
     */
    private fun generateConversationSummary(goalText: String, history: List<String>): String {
        val conversationText = buildString {
            append("사용자 목표: $goalText\n\n")
            append("대화 내용:\n")
            history.forEachIndexed { index, text ->
                val prefix = if (index % 2 == 0) "답변" else "질문"
                append("$prefix: $text\n")
            }
        }

        val messages = listOf(
            ChatMessage(role = "system", content = SUMMARY_SYSTEM_PROMPT),
            ChatMessage(role = "user", content = conversationText)
        )

        return clovaApiClient.chatCompletion(
            messages = messages,
            model = ClovaApiClient.MODEL_HCX_003,
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
    val firstAnswer: String
)
