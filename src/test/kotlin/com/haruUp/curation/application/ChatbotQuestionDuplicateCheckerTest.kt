package com.haruUp.curation.application

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 중복 판정은 재생성(=추가 API 호출)을 유발하므로 오탐을 특히 경계한다.
 * "중복을 놓치는 것"보다 "정상 질문을 중복으로 막는 것"이 더 나쁘다는 기준으로 규칙을 좁게 잡았다.
 */
class ChatbotQuestionDuplicateCheckerTest {

    /* ===================== 중복으로 걸러야 하는 경우 ===================== */

    @Test
    @DisplayName("같은 동기 질문을 표현만 바꾼 경우 중복으로 판정한다")
    fun `동기 질문 반복`() {
        val previous = listOf("이 목표를 이루고 싶은 가장 큰 이유가 무엇인가요?")

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("왜 이 목표를 세우셨나요?", previous)

        assertNotNull(violation)
    }

    @Test
    @DisplayName("감량 폭과 목표 체중처럼 같은 목표 수치를 묻는 질문을 중복으로 판정한다")
    fun `같은 목표 수치 반복`() {
        // 평가(curationEval)에서 실제로 관측된 중복 사례
        val previous = listOf("목표로 하는 감량 폭이 몇 kg인가요?")

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("목표 체중을 몇 kg으로 잡으셨나요?", previous)

        assertNotNull(violation)
    }

    @Test
    @DisplayName("시간을 묻는 질문이 두 번 나오면 중복으로 판정한다")
    fun `시간 질문 반복`() {
        val previous = listOf("하루에 운동에 쓸 수 있는 시간이 몇 분인가요?")

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("하루에 집중할 수 있는 시간이 얼마인가요?", previous)

        assertNotNull(violation)
    }

    @Test
    @DisplayName("어휘가 거의 겹치는 재진술 질문을 중복으로 판정한다")
    fun `어휘 중복 재진술`() {
        val previous = listOf("지금 토익 점수가 몇 점인가요?")

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("지금 토익 점수는 몇 점인가요?", previous)

        assertNotNull(violation)
    }

    @Test
    @DisplayName("여러 이전 질문 중 하나라도 겹치면 중복으로 판정한다")
    fun `여러 질문 중 하나와 중복`() {
        val previous = listOf(
            "지금 토익 점수가 몇 점인가요?",
            "토익에서 가장 먼저 올리고 싶은 영역은 무엇인가요?"
        )

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("이 목표를 이루고 싶은 이유가 무엇인가요?", previous)

        assertNull(violation, "동기 질문은 아직 나온 적 없으므로 통과해야 한다")

        val duplicated = ChatbotQuestionDuplicateChecker.findDuplicate("현재 토익 점수는 몇 점인가요?", previous)

        assertNotNull(duplicated)
    }

    /* ===================== 통과해야 하는 경우 (오탐 방지) ===================== */

    @Test
    @DisplayName("현재 수치와 목표 수치는 단위가 같아도 서로 다른 질문이다")
    fun `현재 수치와 목표 수치는 별개`() {
        val previous = listOf("지금 체중이 몇 kg인가요?")

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("목표 체중이 몇 kg인가요?", previous)

        assertNull(violation)
    }

    @Test
    @DisplayName("같은 현재 상태 영역이라도 묻는 대상이 다르면 통과한다")
    fun `현재 상태 영역 내 다른 질문`() {
        val previous = listOf("지금 체중이 몇 kg인가요?")

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("지금 일주일에 몇 번 운동하시나요?", previous)

        assertNull(violation)
    }

    @Test
    @DisplayName("이전 질문이 없으면 항상 통과한다")
    fun `이전 질문 없음`() {
        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("지금 체중이 몇 kg인가요?", emptyList())

        assertNull(violation)
    }

    @Test
    @DisplayName("주제가 다른 질문은 통과한다")
    fun `다른 주제 질문`() {
        val previous = listOf(
            "이 목표를 이루고 싶은 가장 큰 이유가 무엇인가요?",
            "지금 토익 점수가 몇 점인가요?"
        )

        val violation = ChatbotQuestionDuplicateChecker.findDuplicate("목표로 하는 토익 점수가 몇 점인가요?", previous)

        assertNull(violation, "현재 점수와 목표 점수는 다른 정보다")
    }
}
