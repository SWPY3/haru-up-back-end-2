package com.haruUp.curation.application

import com.haruUp.character.application.CharacterUseCase
import com.haruUp.character.application.CurationCharacterProfile
import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.curation.dto.ChatbotAnswerRequest
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.prompt.ChatbotQuestionPrompt
import com.haruUp.global.prompt.DailyMissionFromGoalPrompt
import com.haruUp.goal.repository.MemberGoalRepository
import com.haruUp.mission.application.GoalBasedMissionGenerationService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations

/**
 * AI 성격이 꼬리질문 생성에만 반영되는지 확인한다.
 * 미션 생성에는 반영하지 않는다. 미션은 성격과 무관하게 목표에 맞아야 하기 때문이다.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurationChatbotPersonalityTest {

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
    }

    private fun stubProfile(name: String, personality: CharacterPersonality) {
        whenever(characterUseCase.getCurationProfile(any()))
            .thenReturn(CurationCharacterProfile(name, personality))
    }

    private fun stubSessionWith(personality: CharacterPersonality?, firstQuestionText: String?) {
        val session = ChatbotSession(
            memberId = 1L,
            questionCount = 2,
            history = listOf("토익 900점", "Q2"),
            firstAnswer = "토익 900점",
            requiresTimeInvestment = false,
            personality = personality,
            firstQuestionText = firstQuestionText
        )
        whenever(valueOperations.get(sessionKey)).thenReturn(objectMapper.writeValueAsString(session))
        whenever(
            openAiApiClient.generateText(
                any(), eq(ChatbotQuestionPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
            )
        ).thenReturn("""{"question":"지금 토익 점수가 몇 점인가요?","examples":["600점대","700점대","800점대"]}""")
    }

    private fun capturedQuestionPrompt(): String {
        val captor = argumentCaptor<String>()
        verify(openAiApiClient).generateText(
            captor.capture(), eq(ChatbotQuestionPrompt.SYSTEM_PROMPT), anyOrNull(), any(), anyOrNull(), any()
        )
        return captor.firstValue
    }

    @Test
    @DisplayName("첫 질문에 회원이 고른 캐릭터 이름이 들어간다")
    fun `첫 질문에 캐릭터 이름 반영`() {
        stubProfile("초코", CharacterPersonality.WARM_FRIEND)

        val response = useCase.startChatbot(memberId = 1L)

        assertTrue(
            response.question.contains("초코"),
            "첫 질문에 캐릭터 이름이 들어가야 한다: ${response.question}"
        )
    }

    @Test
    @DisplayName("캐릭터를 고르지 않았으면 기본 이름과 기본 성격을 쓴다")
    fun `캐릭터 미선택 시 기본값`() {
        stubProfile(CharacterUseCase.DEFAULT_CHARACTER_NAME, CharacterPersonality.DEFAULT)

        val response = useCase.startChatbot(memberId = 1L)

        assertTrue(response.question.contains(CharacterUseCase.DEFAULT_CHARACTER_NAME))
    }

    @Test
    @DisplayName("따뜻한 친구 성격이 꼬리질문 프롬프트에 반영된다")
    fun `따뜻한 친구 말투 반영`() {
        stubSessionWith(CharacterPersonality.WARM_FRIEND, "첫 질문")

        useCase.answer(ChatbotAnswerRequest(sessionId, "A2"))

        val prompt = capturedQuestionPrompt()
        assertTrue(
            prompt.contains(CharacterPersonality.WARM_FRIEND.questionToneGuide),
            "따뜻한 친구 말투 지시문이 프롬프트에 있어야 한다"
        )
        assertFalse(prompt.contains(CharacterPersonality.CLEAR_COACH.questionToneGuide))
    }

    @Test
    @DisplayName("명확한 코치 성격이 꼬리질문 프롬프트에 반영된다")
    fun `명확한 코치 말투 반영`() {
        stubSessionWith(CharacterPersonality.CLEAR_COACH, "첫 질문")

        useCase.answer(ChatbotAnswerRequest(sessionId, "A2"))

        val prompt = capturedQuestionPrompt()
        assertTrue(prompt.contains(CharacterPersonality.CLEAR_COACH.questionToneGuide))
        assertFalse(prompt.contains(CharacterPersonality.WARM_FRIEND.questionToneGuide))
    }

    @Test
    @DisplayName("성격이 없는 기존 세션도 말투 지시 없이 그대로 동작한다")
    fun `성격 없는 세션 호환`() {
        stubSessionWith(personality = null, firstQuestionText = null)

        useCase.answer(ChatbotAnswerRequest(sessionId, "A2"))

        val prompt = capturedQuestionPrompt()
        assertFalse(prompt.contains(CharacterPersonality.WARM_FRIEND.questionToneGuide))
        assertFalse(prompt.contains(CharacterPersonality.CLEAR_COACH.questionToneGuide))
    }

    @Test
    @DisplayName("성격은 미션 생성 프롬프트에는 반영하지 않는다")
    fun `미션 프롬프트에는 성격 미반영`() {
        val missionPrompt = DailyMissionFromGoalPrompt.buildUserMessage(
            goalText = "토익 900점",
            conversationContext = "Q1: 첫 질문\nA1: 토익 900점\n"
        )

        assertFalse(missionPrompt.contains(CharacterPersonality.WARM_FRIEND.questionToneGuide))
        assertFalse(missionPrompt.contains(CharacterPersonality.CLEAR_COACH.questionToneGuide))
        assertFalse(DailyMissionFromGoalPrompt.SYSTEM_PROMPT.contains("말투"))
    }

    @Test
    @DisplayName("성격 선택지는 회의에서 정한 두 가지다")
    fun `성격 선택지`() {
        assertEquals(2, CharacterPersonality.entries.size)
        assertEquals("따뜻하게 응원하며 함께 가는 친구", CharacterPersonality.WARM_FRIEND.label)
        assertEquals("명확한 계획으로 이끌어주는 코치", CharacterPersonality.CLEAR_COACH.label)
    }
}
