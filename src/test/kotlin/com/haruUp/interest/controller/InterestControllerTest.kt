package com.haruUp.interest.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.openai.OpenAiApiResponse
import com.haruUp.global.openai.OpenAiMessage
import com.haruUp.global.openai.OpenAiResult
import com.haruUp.global.security.MemberPrincipal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import org.mockito.BDDMockito.given
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InterestValidationIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var stringRedisTemplate: StringRedisTemplate

    /**
     * 🔥 외부 AI 호출은 반드시 Mock
     */
    @MockBean
    lateinit var openAiApiClient: OpenAiApiClient

    private val testPrincipal = MemberPrincipal(
        id = 101L,
        email = "interest-test@test.com",
        name = "interest-test-user"
    )

    @BeforeEach
    fun setUp() {
        // 목적: 테스트 순서/재실행 여부와 무관하게 Redis 횟수 제한 상태를 초기화한다.
        stringRedisTemplate.delete("typo-validation:count:${testPrincipal.id}")
        stringRedisTemplate.delete("typo-validation:block:${testPrincipal.id}")

        // 기본적으로 AI는 "true" 반환하도록 설정
        given(openAiApiClient.chatCompletion(
            messages = any(),
            model = any(),
            maxTokens = any(),
            temperature = any(),
            topK = any(),
            topP = any(),
            repeatPenalty = any(),
            seed = anyOrNull()
        )).willReturn(
            OpenAiApiResponse(
                status = null,
                result = OpenAiResult(
                    message = OpenAiMessage(
                        role = "assistant",
                        content = "true"
                    )
                )
            )
        )
    }

    // =========================================
    // 1) 정상 문자열
    // =========================================
    @Test
    fun `관심사 검증 - 정상 문자열`() {

        val request = mapOf(
            "interest" to "근력 키우기"
        )

        val result = mockMvc.post("/api/interests/interest/validation") {
            with(user(testPrincipal))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.data.isValid") { value(true) }
            }
            .andReturn()

        assertNotNull(result.response.contentAsString)
    }

    // =========================================
    // 2) 자음 반복 → 로컬 검증에서 컷
    // =========================================
    @Test
    fun `관심사 검증 - 자음 반복`() {

        val request = mapOf(
            "interest" to "ㄱㄱㄱㄱ"
        )

        mockMvc.post("/api/interests/interest/validation") {
            with(user(testPrincipal))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.isValid") { value(false) }
            }
    }

    // =========================================
    // 3) 숫자 포함 → 로컬 검증에서 컷
    // =========================================
    @Test
    fun `관심사 검증 - 숫자 포함`() {

        val request = mapOf(
            "interest" to "헬스123"
        )

        mockMvc.post("/api/interests/interest/validation") {
            with(user(testPrincipal))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.isValid") { value(false) }
            }
    }

    // =========================================
    // 4) AI가 false 반환하는 경우
    // =========================================
    @Test
    fun `관심사 검증 - AI 판단으로 실패`() {

        // AI 응답을 false로 변경
        given(openAiApiClient.chatCompletion(
            messages = any(),
            model = any(),
            maxTokens = any(),
            temperature = any(),
            topK = any(),
            topP = any(),
            repeatPenalty = any(),
            seed = anyOrNull()
        )).willReturn(
            OpenAiApiResponse(
                status = null,
                result = OpenAiResult(
                    message = OpenAiMessage(
                        role = "assistant",
                        content = "false"
                    )
                )
            )
        )

        val request = mapOf(
            "interest" to "근력 키우기기"
        )

        mockMvc.post("/api/interests/interest/validation") {
            with(user(testPrincipal))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.isValid") { value(false) }
            }
    }
}
