package com.haruUp.curation.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.haruUp.character.application.CharacterUseCase
import com.haruUp.character.application.CurationCharacterProfile
import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.curation.dto.ChatbotAnswerResponse
import com.haruUp.curation.dto.ChatbotCompleteResponse
import com.haruUp.curation.dto.ChatbotFinishConfirmResponse
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
import org.mockito.kotlin.doAnswer
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
/**
 * 꼬리질문 개수가 대화 상황에 따라 달라지는 흐름을 검증한다.
 * 최소 3개 / 최대 8개, 그 사이에서 AI 판단과 사용자 선택으로 종료 시점이 결정된다.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurationChatbotDynamicFlowTest {

    @Mock lateinit var redisTemplate: RedisTemplate<String, Any>
    @Mock lateinit var valueOperations: ValueOperations<String, Any>
    @Mock lateinit var openAiApiClient: OpenAiApiClient
    @Mock lateinit var memberGoalRepository: MemberGoalRepository
    @Mock lateinit var goalBasedMissionGenerationService: GoalBasedMissionGenerationService
    @Mock lateinit var characterUseCase: CharacterUseCase

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
            characterUseCase = characterUseCase,
            objectMapper = objectMapper
        )
        whenever(redisTemplate.opsForValue()).thenReturn(valueOperations)
        whenever(characterUseCase.getCurationProfile(any())).thenReturn(
            CurationCharacterProfile(CharacterUseCase.DEFAULT_CHARACTER_NAME, CharacterPersonality.DEFAULT)
        )
        whenever(goalBasedMissionGenerationService.generateAndSaveMissions(any(), any(), any(), anyOrNull(), any()))
            .thenReturn(emptyList())
        // save()는 non-null을 반환해 whenever(...) 형태로 스텁하면 스텁 시점에 NPE가 난다.
        doAnswer { it.arguments[0] }.whenever(memberGoalRepository).save(any())
    }

    private fun stubSession(
        questionCount: Int,
        awaitingFinishConfirmation: Boolean = false,
        pendingSummary: ConversationSummaryParser.ConversationSummaries? = null
    ) {
        val history = mutableListOf("토익 900점")
        repeat(questionCount - 1) { index ->
            history.add("Q${index + 2}")
            if (index < questionCount - 2) history.add("A${index + 2}")
        }
        val session = ChatbotSession(
            memberId = 1L,
            questionCount = questionCount,
            history = history,
            firstAnswer = "토익 900점",
            requiresTimeInvestment = false,
            awaitingFinishConfirmation = awaitingFinishConfirmation,
            pendingSummary = pendingSummary
        )
        whenever(valueOperations.get(sessionKey)).thenReturn(objectMapper.writeValueAsString(session))
    }

    private fun stubQuestionGeneration(json: String) {
        whenever(
            openAiApiClient.generateText(
                any(), eq(ChatbotQuestionPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
            )
        ).thenReturn(json)
    }

    private fun stubSummary() {
        whenever(openAiApiClient.chatCompletion(any(), anyOrNull(), any(), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(
                com.haruUp.global.openai.OpenAiApiResponse(
                    result = com.haruUp.global.openai.OpenAiResult(
                        message = com.haruUp.global.openai.OpenAiMessage(
                            role = "assistant",
                            content = """{"detailed":"상세 요약","brief":"짧은 요약"}"""
                        )
                    )
                )
            )
    }

    /** 질문 생성 프롬프트에 실제로 전달된 사용자 메시지 */
    private fun capturedQuestionPrompt(): String {
        val captor = argumentCaptor<String>()
        verify(openAiApiClient).generateText(
            captor.capture(), eq(ChatbotQuestionPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
        )
        return captor.firstValue
    }

    @Test
    @DisplayName("최소 개수를 채우기 전에는 AI에게 충분 판정을 허용하지 않는다")
    fun `최소 개수 전에는 충분 판정 금지`() {
        // 꼬리질문 2개를 답한 시점 (최소 3개 미만)
        stubSession(questionCount = 3)
        stubQuestionGeneration("""{"question":"지금 토익 점수가 몇 점인가요?","examples":["600점대","700점대","800점대"]}""")

        useCase.answer(ChatbotAnswerRequest(sessionId, "A3"))

        assertTrue(
            capturedQuestionPrompt().contains("충분 판정 금지"),
            "최소 개수 전에는 충분 판정을 금지해야 한다"
        )
    }

    @Test
    @DisplayName("최소 개수를 채운 뒤에는 AI가 충분하다고 답할 수 있게 허용한다")
    fun `최소 개수 후에는 충분 판정 허용`() {
        // 꼬리질문 3개를 답한 시점
        stubSession(questionCount = 4)
        stubQuestionGeneration("""{"question":"목표 점수를 언제까지 만들고 싶으신가요?","examples":["3개월","6개월","1년"]}""")

        useCase.answer(ChatbotAnswerRequest(sessionId, "A4"))

        assertTrue(
            capturedQuestionPrompt().contains("sufficient"),
            "최소 개수 후에는 충분 판정을 허용해야 한다"
        )
    }

    @Test
    @DisplayName("AI가 충분하다고 판단하면 요약과 함께 마무리 확인을 반환한다")
    fun `충분 판정 시 마무리 확인`() {
        stubSession(questionCount = 4)
        stubQuestionGeneration("""{"sufficient":true}""")
        stubSummary()

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "A4"))

        val confirm = assertInstanceOf(ChatbotFinishConfirmResponse::class.java, response)
        assertEquals("짧은 요약", confirm.summary)
        assertEquals(CurationChatbotUseCase.FINISH_CONFIRM_QUESTION, confirm.question)
        assertEquals(
            listOf(CurationChatbotUseCase.FINISH_CONFIRM_YES, CurationChatbotUseCase.FINISH_CONFIRM_NO),
            confirm.examples
        )
        assertEquals(3, confirm.answeredCount)
    }

    @Test
    @DisplayName("마무리 확인에 예라고 답하면 대화를 종료한다")
    fun `마무리 선택 시 종료`() {
        stubSession(
            questionCount = 4,
            awaitingFinishConfirmation = true,
            pendingSummary = ConversationSummaryParser.ConversationSummaries("상세 요약", "짧은 요약")
        )

        val response = useCase.answer(
            ChatbotAnswerRequest(sessionId, CurationChatbotUseCase.FINISH_CONFIRM_YES)
        )

        val completed = assertInstanceOf(ChatbotCompleteResponse::class.java, response)
        assertEquals("짧은 요약", completed.summary)
    }

    @Test
    @DisplayName("마무리 확인 요약을 재사용해 요약을 다시 생성하지 않는다")
    fun `마무리 선택 시 요약 재사용`() {
        stubSession(
            questionCount = 4,
            awaitingFinishConfirmation = true,
            pendingSummary = ConversationSummaryParser.ConversationSummaries("상세 요약", "짧은 요약")
        )

        useCase.answer(ChatbotAnswerRequest(sessionId, CurationChatbotUseCase.FINISH_CONFIRM_YES))

        verify(openAiApiClient, never())
            .chatCompletion(any(), anyOrNull(), any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    @DisplayName("마무리 확인에 아니오라고 답하면 질문을 이어간다")
    fun `계속 선택 시 질문 지속`() {
        stubSession(
            questionCount = 4,
            awaitingFinishConfirmation = true,
            pendingSummary = ConversationSummaryParser.ConversationSummaries("상세 요약", "짧은 요약")
        )
        stubQuestionGeneration("""{"question":"어느 파트를 먼저 올리고 싶으신가요?","examples":["LC","RC","둘 다"]}""")

        val response = useCase.answer(
            ChatbotAnswerRequest(sessionId, CurationChatbotUseCase.FINISH_CONFIRM_NO)
        )

        val answered = assertInstanceOf(ChatbotAnswerResponse::class.java, response)
        assertEquals("어느 파트를 먼저 올리고 싶으신가요?", answered.question)
        assertTrue(
            capturedQuestionPrompt().contains("충분 판정 금지"),
            "사용자가 계속을 원했으므로 이번에는 반드시 질문을 만들어야 한다"
        )
    }

    @Test
    @DisplayName("상한에 도달하면 AI 판단과 무관하게 대화를 마무리한다")
    fun `상한 도달 시 강제 종료`() {
        stubSession(questionCount = CurationChatbotUseCase.MAX_FOLLOW_UP_QUESTIONS + 1)
        stubSummary()

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "마지막 답변"))

        assertInstanceOf(ChatbotCompleteResponse::class.java, response)
        verify(openAiApiClient, never()).generateText(
            any(), eq(ChatbotQuestionPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
        )
    }

    @Test
    @DisplayName("직접 입력한 부정 표현도 대화 계속으로 해석한다")
    fun `직접 입력 부정 표현`() {
        stubSession(
            questionCount = 4,
            awaitingFinishConfirmation = true,
            pendingSummary = ConversationSummaryParser.ConversationSummaries("상세 요약", "짧은 요약")
        )
        stubQuestionGeneration("""{"question":"어느 파트를 먼저 올리고 싶으신가요?","examples":["LC","RC","둘 다"]}""")

        val response = useCase.answer(ChatbotAnswerRequest(sessionId, "아니요 더 얘기할래요"))

        assertInstanceOf(ChatbotAnswerResponse::class.java, response)
    }
}
