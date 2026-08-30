package com.haruUp.curation.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConversationSummaryParserTest {

    @Test
    @DisplayName("detailed와 brief를 각각 파싱한다")
    fun `요약 2종 파싱`() {
        val raw = """{"detailed":"68kg에서 5kg 감량이 목표입니다. 하루 30분 운동 가능합니다.","brief":"현재 68kg에서 5kg 감량이 목표예요."}"""

        val parsed = ConversationSummaryParser.parse(raw)

        assertEquals("68kg에서 5kg 감량이 목표입니다. 하루 30분 운동 가능합니다.", parsed.detailed)
        assertEquals("현재 68kg에서 5kg 감량이 목표예요.", parsed.brief)
    }

    @Test
    @DisplayName("코드블록으로 감싸진 JSON도 파싱한다")
    fun `코드블록 제거 후 파싱`() {
        val raw = "```json\n{\"detailed\":\"상세 요약\",\"brief\":\"짧은 요약\"}\n```"

        val parsed = ConversationSummaryParser.parse(raw)

        assertEquals("상세 요약", parsed.detailed)
        assertEquals("짧은 요약", parsed.brief)
    }

    @Test
    @DisplayName("brief가 없으면 detailed를 사용자 요약으로도 쓴다")
    fun `brief 누락 시 detailed로 대체`() {
        val parsed = ConversationSummaryParser.parse("""{"detailed":"상세 요약만 있음"}""")

        assertEquals("상세 요약만 있음", parsed.detailed)
        assertEquals("상세 요약만 있음", parsed.brief)
    }

    @Test
    @DisplayName("JSON이 아닌 평문은 전체를 두 요약 모두로 사용한다")
    fun `평문 응답 처리`() {
        val parsed = ConversationSummaryParser.parse("체중 감량이 목표입니다.")

        assertEquals("체중 감량이 목표입니다.", parsed.detailed)
        assertEquals("체중 감량이 목표입니다.", parsed.brief)
    }

    @Test
    @DisplayName("빈 응답은 기본 문구로 대체한다")
    fun `빈 응답 처리`() {
        val parsed = ConversationSummaryParser.parse("   ")

        assertEquals(ConversationSummaryParser.FALLBACK_SUMMARY, parsed.detailed)
        assertEquals(ConversationSummaryParser.FALLBACK_SUMMARY, parsed.brief)
    }
}
