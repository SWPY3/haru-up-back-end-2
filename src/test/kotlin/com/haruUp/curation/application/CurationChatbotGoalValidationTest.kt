package com.haruUp.curation.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotCompleteResponse
import com.haruUp.curation.dto.ChatbotGoalRejectedResponse
import com.haruUp.curation.dto.ChatbotQuestionType
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ChatbotQuestionPrompt
import com.haruUp.global.prompt.GoalValidationPrompt
import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.mission.application.GoalBasedMissionGenerationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.mockito.kotlin.argumentCaptor
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

    /** 지정한 상태의 세션을 Redis에서 읽어오도록 스텁한다. */
    private fun stubSession(questionCount: Int, requiresTime: Boolean?, history: List<String>) {
        val session = ChatbotSession(
            memberId = 1L,
            questionCount = questionCount,
            history = history,
            firstAnswer = "토익 900점",
            requiresTimeInvestment = requiresTime
        )
        whenever(valueOperations.get(sessionKey)).thenReturn(objectMapper.writeValueAsString(session))
    }

    /** 꼬리질문 6개가 모두 끝난 시점의 history [A1, Q2, A2, Q3, A3, Q4, A4, Q5, A5, Q6] */
    private fun historyThroughLastFollowUp() = listOf(
        "토익 900점", "Q2", "A2", "Q3", "A3", "Q4", "A4", "Q5", "A5", "Q6"
    )

    @Test
    @DisplayName("시간 투자가 필요한 목표면 마지막 꼬리질문 뒤에 고정 시간 질문을 반환한다")
    fun `시간 필요 목표는 시간 질문 추가`() {
        stubSession(questionCount = 6, requiresTime = true, history = historyThroughLastFollowUp())

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "A6"))

        val answered = assertInstanceOf(ChatbotAnswerResponse::class.java, response)
        assertEquals(CurationChatbotUseCase.TIME_QUESTION, answered.question)
        assertEquals(listOf("10분 이내", "30분", "1시간 이상"), answered.examples)
        assertEquals(CurationChatbotUseCase.TIME_QUESTION_NUMBER, answered.questionNumber)
        assertEquals(ChatbotQuestionType.FIXED_TIME, answered.questionType)
        assertTrue(answered.isLast)
    }

    @Test
    @DisplayName("시간 투자가 필요 없는 목표면 시간 질문 없이 바로 대화를 마무리한다")
    fun `시간 불필요 목표는 시간 질문 생략`() {
        stubSession(questionCount = 6, requiresTime = false, history = historyThroughLastFollowUp())
        whenever(openAiApiClient.chatCompletion(any(), anyOrNull(), any(), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(summaryResponse())
        whenever(goalBasedMissionGenerationService.generateAndSaveMissions(any(), any(), any(), anyOrNull(), any()))
            .thenReturn(emptyList())

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "A6"))

        assertInstanceOf(ChatbotCompleteResponse::class.java, response)
    }

    @Test
    @DisplayName("시간 질문에 답하면 대화가 종료되고 답변이 미션 생성 맥락에 포함된다")
    fun `시간 답변 후 종료`() {
        stubSession(
            questionCount = CurationChatbotUseCase.TIME_QUESTION_NUMBER,
            requiresTime = true,
            history = historyThroughLastFollowUp() + listOf("A6", CurationChatbotUseCase.TIME_QUESTION)
        )
        whenever(openAiApiClient.chatCompletion(any(), anyOrNull(), any(), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(summaryResponse())
        val contextCaptor = argumentCaptor<String>()
        whenever(
            goalBasedMissionGenerationService.generateAndSaveMissions(
                any(), any(), any(), contextCaptor.capture(), any()
            )
        ).thenReturn(emptyList())

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "30분"))

        assertInstanceOf(ChatbotCompleteResponse::class.java, response)
        val conversationRaw = contextCaptor.firstValue
        assertTrue(
            conversationRaw.contains(CurationChatbotUseCase.TIME_QUESTION),
            "미션 생성 맥락에 시간 질문이 포함되어야 한다: $conversationRaw"
        )
        assertTrue(
            conversationRaw.contains("30분"),
            "미션 생성 맥락에 시간 답변이 포함되어야 한다: $conversationRaw"
        )
    }

    private fun summaryResponse(content: String = "요약") = com.haruUp.global.openai.OpenAiApiResponse(
        result = com.haruUp.global.openai.OpenAiResult(
            message = com.haruUp.global.openai.OpenAiMessage(role = "assistant", content = content)
        )
    )

    @Test
    @DisplayName("첫 질문은 선택형 예시 없이 placeholder만 제공한다")
    fun `첫 질문은 placeholder 제공`() {
        val response = useCase.startChatbot(memberId = 1L)

        assertTrue(response.examples.isEmpty(), "선택형 예시는 비어 있어야 한다: ${response.examples}")
        assertEquals(CurationChatbotUseCase.FIRST_QUESTION_PLACEHOLDER, response.placeholder)
        assertTrue(
            response.question.contains("직접 입력"),
            "첫 질문은 직접 입력을 요구해야 한다: ${response.question}"
        )
    }

    @Test
    @DisplayName("요약은 미션 생성용 상세본과 사용자 노출용 간략본으로 나뉘어 저장된다")
    fun `요약 2종 저장`() {
        stubSession(questionCount = 6, requiresTime = false, history = historyThroughLastFollowUp())
        whenever(openAiApiClient.chatCompletion(any(), anyOrNull(), any(), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(
                summaryResponse(
                    """{"detailed":"토익 700점에서 900점이 목표입니다. 취업을 위해 필요합니다.","brief":"토익 900점이 목표예요."}"""
                )
            )
        val goalCaptor = argumentCaptor<com.haruUp.goal.domain.MemberGoal>()
        whenever(memberGoalRepository.save(goalCaptor.capture())).thenAnswer { it.arguments[0] }
        whenever(goalBasedMissionGenerationService.generateAndSaveMissions(any(), any(), any(), anyOrNull(), any()))
            .thenReturn(emptyList())

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "A6"))

        val completed = assertInstanceOf(ChatbotCompleteResponse::class.java, response)
        assertEquals("토익 900점이 목표예요.", completed.summary)

        val savedGoal = goalCaptor.firstValue
        assertEquals("토익 700점에서 900점이 목표입니다. 취업을 위해 필요합니다.", savedGoal.conversationSummary)
        assertEquals("토익 900점이 목표예요.", savedGoal.userSummary)
    }
}
