package com.haruUp.curation.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotGoalRejectedResponse
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ChatbotQuestionPrompt
import com.haruUp.global.prompt.GoalValidationPrompt
import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.mission.application.GoalBasedMissionGenerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * 목표 입력 검증(목표가 하나인지 / 시간 투자가 필요한지)이 첫 답변 처리에 반영되는지 확인한다.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurationChatbotGoalValidationTest {

    @Mock lateinit var redisTemplate: RedisTemplate<String, Any>
    @Mock lateinit var valueOperations: ValueOperations<String, Any>
    @Mock lateinit var openAiApiClient: OpenAiApiClient
    @Mock lateinit var memberGoalRepository: MemberGoalRepository
    @Mock lateinit var goalBasedMissionGenerationService: GoalBasedMissionGenerationService

    // 운영에서는 Spring이 등록한 Kotlin 모듈 포함 ObjectMapper를 사용하므로 동일하게 맞춘다.
    private val objectMapper = jacksonObjectMapper()
    private lateinit var useCase: CurationChatbotUseCase

    private val sessionId = "test-session"
    private val sessionKey = "chatbot:session:$sessionId"

    @BeforeEach
    fun setUp() {
        useCase = CurationChatbotUseCase(
            redisTemplate = redisTemplate,
            openAiApiClient = openAiApiClient,
            memberGoalRepository = memberGoalRepository,
            goalBasedMissionGenerationService = goalBasedMissionGenerationService,
            objectMapper = objectMapper
        )
        whenever(redisTemplate.opsForValue()).thenReturn(valueOperations)

        val session = ChatbotSession(memberId = 1L, questionCount = 1, history = emptyList(), firstAnswer = "")
        whenever(valueOperations.get(sessionKey)).thenReturn(objectMapper.writeValueAsString(session))
    }

    /** 목표 분석 호출만 지정한 응답을 내도록 스텁한다. */
    private fun stubGoalAnalysis(json: String) {
        whenever(
            openAiApiClient.generateText(
                any(), eq(GoalValidationPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
            )
        ).thenReturn(json)
    }

    /** 꼬리질문 생성 호출을 스텁한다. */
    private fun stubFollowUpQuestion(json: String) {
        whenever(
            openAiApiClient.generateText(
                any(), eq(ChatbotQuestionPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
            )
        ).thenReturn(json)
    }

    @Test
    @DisplayName("목표가 2개면 질문을 진행하지 않고 재입력 응답을 반환한다")
    fun `복수 목표는 재입력 응답`() {
        stubGoalAnalysis("""{"goalCount":2,"goals":["다이어트","토익 900점"],"requiresTime":true}""")

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "다이어트하고 토익 900점 받기"))

        val rejected = assertInstanceOf(ChatbotGoalRejectedResponse::class.java, response)
        assertFalse(rejected.isValidGoal)
        assertEquals(GoalValidator.MULTIPLE_GOAL_MESSAGE, rejected.message)
        assertEquals(listOf("다이어트", "토익 900점"), rejected.detectedGoals)
        assertEquals(1, rejected.questionNumber)
    }

    @Test
    @DisplayName("목표가 2개면 세션을 갱신하지 않아 같은 세션으로 다시 제출할 수 있다")
    fun `복수 목표는 세션 미갱신`() {
        stubGoalAnalysis("""{"goalCount":2,"goals":["금연","주식 공부"],"requiresTime":false}""")

        useCase.answer(ChatbotAnswerRequest(sessionId, "금연하고 주식 공부도 시작하기"))

        verify(valueOperations, never()).set(eq(sessionKey), any(), any<Duration>())
    }

    @Test
    @DisplayName("목표가 1개면 검증을 통과하고 꼬리질문 생성으로 넘어간다")
    fun `단일 목표는 통과`() {
        stubGoalAnalysis("""{"goalCount":1,"goals":["체중 5kg 감량"],"requiresTime":true}""")
        stubFollowUpQuestion("""{"question":"지금 체중이 몇 kg인가요?","examples":["60kg대","70kg대","80kg대"]}""")

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "체중 감량 5kg"))

        val answered = assertInstanceOf(ChatbotAnswerResponse::class.java, response)
        assertEquals("지금 체중이 몇 kg인가요?", answered.question)
        assertEquals(2, answered.questionNumber)
    }

    @Test
    @DisplayName("목표 분석 호출이 실패해도 사용자를 막지 않고 질문을 이어간다")
    fun `분석 실패 시 통과 처리`() {
        whenever(
            openAiApiClient.generateText(
                any(), eq(GoalValidationPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
            )
        ).thenThrow(RuntimeException("OpenAI 호출 실패"))
        stubFollowUpQuestion("""{"question":"지금 토익 점수가 몇 점인가요?","examples":["600점대","700점대","800점대"]}""")

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "토익 900점"))

        assertInstanceOf(ChatbotAnswerResponse::class.java, response)
    }
}
