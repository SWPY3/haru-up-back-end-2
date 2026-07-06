package com.haruUp.curation.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotCompleteResponse
import com.haruUp.curation.dto.ChatbotMissionDto
import com.haruUp.curation.dto.ChatbotStartResponse
import com.haruUp.global.openai.ChatMessage
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ChatbotQuestionPrompt
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
            // 6번째 질문 답변 완료 → 대화 종료 처리
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

            // 원본 대화 JSON으로 변환
            val conversationRaw = buildConversationRaw(session.history, allHistory)

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
     * OpenAI를 사용하여 꼬리질문을 생성합니다.
     */
    private fun generateFollowUpQuestion(goalText: String, history: List<String>): String {
        // history 구조: [A1, Q2, A2, Q3, ...] - 홀수 인덱스(1,3,5...)가 AI 질문
        // Q1(고정 첫 질문) + 이미 생성된 AI 질문들을 중복 방지 목록으로 전달
        val previousQuestions = buildList {
            add(FIRST_QUESTION)
            history.filterIndexed { index, _ -> index % 2 == 1 }.forEach { add(it) }
        }

        val userMessage = ChatbotQuestionPrompt.buildUserMessage(goalText, history, previousQuestions)
        val raw = openAiApiClient.generateText(
            userMessage = userMessage,
            systemMessage = ChatbotQuestionPrompt.SYSTEM_PROMPT,
            model = OpenAiApiClient.MODEL_DEFAULT,
            temperature = 0.7
        ).trim()
        // "Q2: ", "Q3: " 등 번호 접두사가 붙어 나오는 경우 제거
        return raw.replace(Regex("^Q\\d+[:.\\s]+"), "")
    }

    /**
     * 전체 Q&A 대화를 가독성 있는 텍스트 형식으로 변환합니다.
     * finalHistory 구조: [A1, Q2, A2, Q3, A3, Q4, A4, Q5, A5, Q6, A6]
     */
    private fun buildConversationRaw(questionHistory: List<String>, finalHistory: List<String>): String {
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
     * OpenAI를 사용하여 전체 대화를 요약합니다.
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
    val firstAnswer: String
)
