package com.haruUp.mission.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 이 검증기의 기준값은 프롬프트(DailyMissionFromGoalPrompt)의 기준과 반드시 같아야 한다.
 * 한쪽만 바꾸면 모델이 지킬 수 없는 기준으로 재시도만 반복하게 되므로, 경계값을 테스트로 고정한다.
 */
class GeneratedMissionValidatorTest {

    /** 검증 대상이 아닌 필드는 항상 기준을 만족시켜 두고, 보려는 필드만 바꾼다. */
    private fun mission(
        content: String = "보유 5종목 손익 적기",
        description: String = "증권 앱에서 보유 5종목 손익률과 수익금 적기",
        difficulty: Int = 1
    ) = ParsedMission(content, description, difficulty)

    /**
     * 글자수만 맞춘 더미 문장.
     *
     * 같은 음절을 3번 이상 반복하면 KoreanGrammarChecker에 걸려 길이와 무관한 위반이 섞이므로,
     * 음절을 돌려 쓴다. 마지막 글자도 조사(을/를/은/는)가 되지 않게 한다.
     */
    private fun padded(length: Int): String =
        (0 until length).map { "가나다라마바사아자차"[it % 10] }.joinToString("")

    /** 하5 + 중5 + 상5 = 15개. 제목이 겹치면 중복 검사에 걸리므로 번호로 구분한다. */
    private fun validMissions(): List<ParsedMission> {
        return (1..3).flatMap { difficulty ->
            (1..GeneratedMissionValidator.MISSIONS_PER_DIFFICULTY).map { index ->
                mission(content = "보유 $difficulty-$index 종목 손익 적기", difficulty = difficulty)
            }
        }
    }

    /* ===================== 난이도 분포 ===================== */

    @Test
    @DisplayName("하·중·상 각 5개면 분포 검증을 통과한다")
    fun `난이도 분포 통과`() {
        assertTrue(GeneratedMissionValidator.hasValidDifficultyDistribution(validMissions()))
    }

    @Test
    @DisplayName("한 난이도라도 개수가 어긋나면 분포 검증에 실패한다")
    fun `난이도 분포 불일치`() {
        val missions = validMissions().drop(1)

        assertFalse(GeneratedMissionValidator.hasValidDifficultyDistribution(missions))
    }

    /* ===================== content 글자수 ===================== */

    @Test
    @DisplayName("content 경계값(10자, 25자)은 통과한다")
    fun `content 글자수 경계 통과`() {
        val min = padded(GeneratedMissionValidator.MIN_CONTENT_LENGTH)
        val max = padded(GeneratedMissionValidator.MAX_CONTENT_LENGTH)

        val result = GeneratedMissionValidator.validate(
            listOf(mission(content = min), mission(content = max)),
            emptySet()
        )

        assertTrue(result.passed, "경계값은 상한 포함이다. 위반 내역: $result")
    }

    @Test
    @DisplayName("content가 기준보다 짧으면 위반으로 잡는다")
    fun `content 글자수 미달`() {
        // 평가(curationEval) 450개 중 유일하게 벗어난 사례 (9자)
        val short = mission(content = "스쿼트 맨몸 8회")

        val result = GeneratedMissionValidator.validate(listOf(short), emptySet())

        assertEquals(listOf(short), result.shortContents)
        assertFalse(result.passed)
    }

    @Test
    @DisplayName("content가 기준보다 길면 위반으로 잡는다")
    fun `content 글자수 초과`() {
        val long = mission(content = padded(GeneratedMissionValidator.MAX_CONTENT_LENGTH + 1))

        val result = GeneratedMissionValidator.validate(listOf(long), emptySet())

        assertEquals(listOf(long), result.longContents)
        assertFalse(result.passed)
    }

    /* ===================== description 글자수 ===================== */

    @Test
    @DisplayName("description 경계값(20자, 30자)은 통과한다")
    fun `description 글자수 경계 통과`() {
        val min = padded(GeneratedMissionValidator.MIN_DESCRIPTION_LENGTH)
        val max = padded(GeneratedMissionValidator.MAX_DESCRIPTION_LENGTH)

        val result = GeneratedMissionValidator.validate(
            listOf(mission(description = min), mission(description = max)),
            emptySet()
        )

        assertTrue(result.passed, "경계값은 상한 포함이다. 위반 내역: $result")
    }

    @Test
    @DisplayName("영어 원문을 담아 30자를 넘긴 description을 위반으로 잡는다")
    fun `description 글자수 초과`() {
        // 평가에서 30자 초과 25건 중 19건이 영어 회화 목표였고, 대부분 영어 원문이 원인이었다. (46자)
        val long = mission(description = "휴대폰 녹음기로 I'd like to check in, please. 10회 말하기")

        val result = GeneratedMissionValidator.validate(listOf(long), emptySet())

        assertEquals(listOf(long), result.longDescriptions)
    }

    @Test
    @DisplayName("description이 기준보다 짧으면 위반으로 잡는다")
    fun `description 글자수 미달`() {
        val short = mission(description = "물 한 컵 마시기")

        val result = GeneratedMissionValidator.validate(listOf(short), emptySet())

        assertEquals(listOf(short), result.shortDescriptions)
    }

    /* ===================== 문법 · 중복 ===================== */

    @Test
    @DisplayName("조사로 끝나 서술이 잘린 문장을 문법 위반으로 잡는다")
    fun `문법 위반`() {
        val broken = mission(description = "증권 앱에서 보유 5종목의 손익률과 수익금을")

        val result = GeneratedMissionValidator.validate(listOf(broken), emptySet())

        assertEquals(1, result.grammarViolations.size)
        assertFalse(result.passed)
    }

    @Test
    @DisplayName("이미 제공한 미션과 제목이 같으면 중복으로 잡는다")
    fun `중복 미션`() {
        val repeated = mission(content = "보유 5종목 손익 적기")

        val result = GeneratedMissionValidator.validate(listOf(repeated), setOf("보유 5종목 손익 적기"))

        assertEquals(listOf(repeated), result.duplicated)
        assertFalse(result.passed)
    }

    /* ===================== 통과 · 위반 집계 ===================== */

    @Test
    @DisplayName("모든 기준을 만족하면 통과로 판정한다")
    fun `전체 통과`() {
        val result = GeneratedMissionValidator.validate(validMissions(), emptySet())

        assertTrue(result.passed, "위반 내역: $result")
    }

    @Test
    @DisplayName("한 미션이 여러 기준을 동시에 어겨도 위반 1건으로 센다")
    fun `위반 건수는 미션 단위`() {
        // 제목 미달 + 설명 미달 + 중복이 한 미션에 겹친 경우
        val broken = mission(content = "짧은 제목", description = "짧은 설명")

        val count = GeneratedMissionValidator.countViolations(listOf(broken), setOf("짧은 제목"))

        assertEquals(1, count, "결과끼리 위반 정도를 비교하는 용도라 어긋난 미션 수가 기준이다")
    }

    @Test
    @DisplayName("위반이 없으면 건수가 0이다")
    fun `위반 없음`() {
        assertEquals(0, GeneratedMissionValidator.countViolations(validMissions(), emptySet()))
    }
}
