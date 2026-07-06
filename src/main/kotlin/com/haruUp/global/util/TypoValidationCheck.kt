package com.haruUp.global.util

import com.haruUp.global.openai.ChatMessage
import com.haruUp.global.openai.OpenAiApiClient
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class TypoValidationCheck(
    private val openAiApiClient: OpenAiApiClient,
    private val stringRedisTemplate: StringRedisTemplate
) {

    data class ValidationResult(
        val isValid: Boolean,
        val reason: String? = null
    )

    private companion object {
        private const val MAX_VALIDATION_COUNT = 3L
        private const val BLOCK_MINUTES = 30L
        private const val COUNT_KEY_PREFIX = "typo-validation:count:"
        private const val BLOCK_KEY_PREFIX = "typo-validation:block:"

        private val KOREAN_JAMO_REGEX = Regex("[ㄱ-ㅎㅏ-ㅣ]")
        private val KOREAN_WITH_SPACES_REGEX = Regex("^[가-힣\\s]+$")
        private val REPEATED_CHAR_REGEX = Regex("(.)\\1{2,}")

        const val SYSTEM_PROMPT =
            """
            당신은 한국어 문자열의 유효성과 의미를 판별하는 검증 전문가입니다.
            사용자가 입력한 문자열이 실제로 의미 있는 한국어 단어 또는 구인지 판단해야 합니다.

            ## 검증 규칙
            1. 완성형 한글로 이루어진 의미 있는 단어 또는 구만 허용합니다.
            2. 자음/모음 반복(예: ㄱㄱㄱ, ㅋㅋㅋ), 의미 없는 문자열은 허용하지 않습니다.
            3. 오타가 포함되어 있거나 의미가 불분명하면 허용하지 않습니다.
            4. 확신이 들지 않으면 false를 반환해야 합니다.

            ## 응답 형식
            - 반드시 true 또는 false 중 하나만 출력하세요.
            - 소문자 영문으로만 출력하세요.
            - 설명, 문장, JSON, 공백을 절대 포함하지 마세요.
        """
    }

    /**
     * 한글 기반 문자열 1차 유효성 검증
     */
    fun validateKoreanText(input: String): ValidationResult {
        val text = input.trim()

        /* =========================
         * 1️⃣ 로컬 1차 유효성 검증
         * ========================= */

        if (text.isEmpty()) {
            return ValidationResult(false, "빈 문자열입니다.")
        }

        if (text.any { it.isDigit() }) {
            return ValidationResult(false, "숫자가 포함되어 있습니다.")
        }

        // 자음/모음 단독 문자 포함
        if (KOREAN_JAMO_REGEX.containsMatchIn(text)) {
            return ValidationResult(false, "자음 또는 모음만으로 구성된 문자는 허용되지 않습니다.")
        }

        // 한글 완성형 + 공백만 허용
        if (!KOREAN_WITH_SPACES_REGEX.matches(text)) {
            return ValidationResult(false, "한글 이외의 문자가 포함되어 있습니다.")
        }

        // 같은 문자 3회 이상 반복
        if (REPEATED_CHAR_REGEX.containsMatchIn(text)) {
            return ValidationResult(false, "같은 문자의 반복이 과도합니다.")
        }

        // 최소 길이
        if (text.replace(" ", "").length < 2) {
            return ValidationResult(false, "문자열이 너무 짧습니다.")
        }

        /* =========================
         * 2️⃣ AI 기반 의미 / 오타 검증
         * ========================= */

        val userPrompt =
            """
                다음 문자열이 의미 있는 한국어 표현인지 판단하라: "$text"
            """

        return try {
            val response = openAiApiClient.chatCompletion(
                messages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = userPrompt)
                )
            )

            val result = response.result
                ?.message
                ?.content
                ?.trim()
                ?.lowercase()

            if (result == "true") {
                ValidationResult(true)
            } else {
                ValidationResult(false, "AI 판단 결과 의미 없는 문자열입니다.")
            }

        } catch (e: Exception) {
            // AI 실패 시 보수적으로 차단
            ValidationResult(false, "AI 검증 중 오류가 발생했습니다.")
        }
    }


    fun checkRedisCount(memberId: Long): ValidationResult? {
        val countKey = interestCountRedisKey(memberId)
        val blockKey = interestBlockRedisKey(memberId)

        // 1️⃣ 차단 여부 확인
        if (stringRedisTemplate.hasKey(blockKey)) {
            val remainSeconds = stringRedisTemplate.getExpire(blockKey, TimeUnit.SECONDS)
            val remainMinutes = if (remainSeconds > 0) (remainSeconds + 30) / 60 else 0

            return ValidationResult(
                false,
                "요청 횟수를 초과했습니다. 약 ${remainMinutes}분 후 다시 시도해주세요."
            )
        }

        val count = requireNotNull(stringRedisTemplate.opsForValue().increment(countKey)) {
            "Redis increment 결과가 null 입니다. key=$countKey"
        }

        // 3️⃣ 제한 초과 → 차단
        if (count > MAX_VALIDATION_COUNT) {
            stringRedisTemplate.opsForValue().set(blockKey, "BLOCKED", Duration.ofMinutes(BLOCK_MINUTES))
            stringRedisTemplate.delete(countKey)

            return ValidationResult(
                false,
                "관심사 검증은 최대 $MAX_VALIDATION_COUNT 회까지만 가능합니다. 30분 후 다시 시도해주세요."
            )
        }

        return null

    }


    /* 오타 검증 limit redis */
    private fun interestCountRedisKey(memberId: Long) = "$COUNT_KEY_PREFIX$memberId"
    private fun interestBlockRedisKey(memberId: Long) = "$BLOCK_KEY_PREFIX$memberId"

}
