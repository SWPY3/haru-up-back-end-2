package com.haruUp.curation.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChatbotQuestionValidatorTest {

    @Test
    @DisplayName("JSON 응답에서 질문과 예시 답변을 파싱한다")
    fun `JSON 응답 파싱`() {
        val raw = """{"question":"하루에 운동에 쓸 수 있는 시간이 몇 분인가요?","examples":["10분 이내","30분 정도","1시간 이상"]}"""

        val parsed = ChatbotQuestionValidator.parse(raw)

        assertEquals("하루에 운동에 쓸 수 있는 시간이 몇 분인가요?", parsed.question)
        assertEquals(listOf("10분 이내", "30분 정도", "1시간 이상"), parsed.examples)
        assertNull(ChatbotQuestionValidator.findViolation(parsed))
    }

    @Test
    @DisplayName("코드블록으로 감싸진 JSON도 파싱한다")
    fun `코드블록 제거 후 파싱`() {
        val raw = "```json\n{\"question\":\"평일에 집에 도착하는 시간이 몇 시인가요?\",\"examples\":[\"7시 이전\",\"8시쯤\",\"10시 이후\"]}\n```"

        val parsed = ChatbotQuestionValidator.parse(raw)

        assertEquals("평일에 집에 도착하는 시간이 몇 시인가요?", parsed.question)
        assertEquals(3, parsed.examples.size)
        assertNull(ChatbotQuestionValidator.findViolation(parsed))
    }

    @Test
    @DisplayName("JSON이 아닌 평문은 질문으로만 파싱되고 예시 누락으로 탈락한다")
    fun `평문 응답은 예시 누락으로 탈락`() {
        val parsed = ChatbotQuestionValidator.parse("Q2: 하루에 몇 개비 피우시나요?")

        assertEquals("하루에 몇 개비 피우시나요?", parsed.question)
        assertEquals(emptyList<String>(), parsed.examples)
        assertNotNull(ChatbotQuestionValidator.findViolation(parsed))
    }

    @Test
    @DisplayName("예시 답변이 3개가 아니면 탈락한다")
    fun `예시 개수 검증`() {
        val parsed = ChatbotQuestionValidator.ParsedQuestion(
            question = "하루에 몇 개비 피우시나요?",
            examples = listOf("반 갑", "한 갑")
        )

        assertNotNull(ChatbotQuestionValidator.findViolation(parsed))
    }

    @Test
    @DisplayName("예시 답변이 길거나 중복이면 탈락한다")
    fun `예시 길이와 중복 검증`() {
        val tooLong = ChatbotQuestionValidator.ParsedQuestion(
            question = "운동을 그만두게 만든 가장 큰 이유가 무엇인가요?",
            examples = listOf("퇴근이 너무 늦어서 도저히 시간을 낼 수가 없었어요", "너무 지쳐서", "재미가 없어서")
        )
        assertNotNull(ChatbotQuestionValidator.findViolation(tooLong))

        val duplicated = ChatbotQuestionValidator.ParsedQuestion(
            question = "운동을 그만두게 만든 가장 큰 이유가 무엇인가요?",
            examples = listOf("너무 지쳐서", "너무 지쳐서", "재미가 없어서")
        )
        assertNotNull(ChatbotQuestionValidator.findViolation(duplicated))
    }

    @Test
    @DisplayName("한 번에 두 가지를 묻는 질문은 탈락한다")
    fun `복수 정보 질문 검증`() {
        val twoQuestions = ChatbotQuestionValidator.ParsedQuestion(
            question = "전에 시도한 적 있나요? 있다면 왜 실패하셨나요?",
            examples = listOf("있어요", "없어요", "잘 모르겠어요")
        )
        assertNotNull(ChatbotQuestionValidator.findViolation(twoQuestions))

        val listing = ChatbotQuestionValidator.ParsedQuestion(
            question = "하루에 쓸 수 있는 시간과 그리고 장소는 어디인가요?",
            examples = listOf("30분 집", "1시간 헬스장", "10분 회사")
        )
        assertNotNull(ChatbotQuestionValidator.findViolation(listing))
    }

    @Test
    @DisplayName("서술형 답변을 요구하는 질문은 탈락한다")
    fun `서술형 요구 검증`() {
        val parsed = ChatbotQuestionValidator.ParsedQuestion(
            question = "지금까지의 생활 패턴을 자세히 알려주세요?",
            examples = listOf("불규칙해요", "규칙적이에요", "잘 모르겠어요")
        )

        assertNotNull(ChatbotQuestionValidator.findViolation(parsed))
    }

    @Test
    @DisplayName("질문이 여러 개 붙어 나오면 첫 번째 질문만 남긴다")
    fun `첫 질문만 사용`() {
        val result = ChatbotQuestionValidator.takeFirstQuestion("하루에 몇 분인가요? 그리고 언제 하시나요?")

        assertEquals("하루에 몇 분인가요?", result)
    }

    @Test
    @DisplayName("이전 질문과 의미가 중복되면 탈락한다")
    fun `의미 중복 질문 탈락`() {
        val parsed = ChatbotQuestionValidator.parse(
            """{"question":"목표 체중을 몇 kg으로 잡으셨나요?","examples":["60kg","65kg","70kg"]}"""
        )

        assertNull(ChatbotQuestionValidator.findViolation(parsed), "이전 질문이 없으면 통과해야 한다")
        assertNotNull(
            ChatbotQuestionValidator.findViolation(parsed, listOf("목표로 하는 감량 폭이 몇 kg인가요?"))
        )
    }

    @Test
    @DisplayName("이전 질문과 묻는 정보가 다르면 통과한다")
    fun `다른 정보를 묻는 질문은 통과`() {
        val parsed = ChatbotQuestionValidator.parse(
            """{"question":"지금 체중이 몇 kg인가요?","examples":["60kg대","70kg대","80kg대"]}"""
        )

        assertNull(
            ChatbotQuestionValidator.findViolation(parsed, listOf("목표 체중을 몇 kg으로 잡으셨나요?"))
        )
    }
}
