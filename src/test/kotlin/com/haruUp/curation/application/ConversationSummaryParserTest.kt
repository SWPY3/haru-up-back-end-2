package com.haruUp.curation.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    /* ===================== brief 길이 검증 ===================== */

    @Test
    @DisplayName("기준 안에 있는 brief는 통과한다")
    fun `brief 길이 통과`() {
        val summaries = ConversationSummaryParser.ConversationSummaries(
            detailed = "68kg에서 5kg 감량이 목표입니다. 하루 30분 운동 가능합니다.",
            brief = "현재 68kg에서 5kg 감량이 목표이고, 하루 30분 정도 운동할 수 있어요."
        )

        assertNull(ConversationSummaryParser.findBriefViolation(summaries))
    }

    @Test
    @DisplayName("길이를 크게 넘긴 brief는 위반으로 판정한다")
    fun `brief 길이 위반`() {
        // 검증(curationEval) 바디프로필 #5에서 실제로 나온 96자 요약
        val summaries = ConversationSummaryParser.ConversationSummaries(
            detailed = "상세 요약",
            brief = "6개월 뒤 바디프로필 촬영이 목표이고, 현재 체지방률은 약 30%예요. " +
                "어깨·등 라인을 살린 건강하고 여성스러운 분위기를 원하고, 운동은 주 2회 러닝머신 30분 정도예요."
        )

        assertNotNull(ConversationSummaryParser.findBriefViolation(summaries))
    }

    @Test
    @DisplayName("정확히 80자인 brief는 통과한다")
    fun `brief 길이 경계 - 통과`() {
        // 80자. 상한은 포함이다.
        val summaries = ConversationSummaryParser.ConversationSummaries(
            detailed = "상세 요약",
            brief = "해외여행에서 영어로 대화하는 것이 목표이고, 토익 780점이지만 말하기는 거의 못 해요. " +
                "겨울 유럽 여행의 공항과 호텔 체크인이 가장 걱정돼요."
        )

        assertEquals(ConversationSummaryParser.MAX_BRIEF_LENGTH, summaries.brief.length)
        assertNull(ConversationSummaryParser.findBriefViolation(summaries))
    }

    @Test
    @DisplayName("81자부터는 위반으로 판정한다")
    fun `brief 길이 경계 - 위반`() {
        // 81자. 검증에서 초과한 4건 중 3건이 81~83자여서, 여유를 두면 검증이 사실상 무력해진다.
        val summaries = ConversationSummaryParser.ConversationSummaries(
            detailed = "상세 요약",
            brief = "해외여행에서 영어로 대화하는 것이 목표이고, 토익 780점이지만 말하기는 거의 못 해요. " +
                "겨울 유럽 여행에서 공항과 호텔 체크인이 가장 걱정돼요."
        )

        assertEquals(ConversationSummaryParser.MAX_BRIEF_LENGTH + 1, summaries.brief.length)
        assertNotNull(ConversationSummaryParser.findBriefViolation(summaries))
    }

    @Test
    @DisplayName("파싱 실패로 채운 기본 문구는 검사하지 않는다")
    fun `기본 문구는 검사 대상이 아니다`() {
        val summaries = ConversationSummaryParser.parse("")

        assertEquals(ConversationSummaryParser.FALLBACK_SUMMARY, summaries.brief)
        assertNull(
            ConversationSummaryParser.findBriefViolation(summaries),
            "재생성해도 나아지지 않으므로 재시도를 유발하면 안 된다"
        )
    }

    @Test
    @DisplayName("brief가 비어 detailed로 채워지면 길이 위반으로 잡힌다")
    fun `brief 누락 시 위반 판정`() {
        val raw = """{"detailed":"해외여행에서 영어로 대화하는 것이 목표입니다. 토익 780점이지만 말하기는 거의 못 합니다. 겨울 유럽 배낭여행을 앞두고 공항 체크인, 호텔 체크인, 식당 주문에서 막히는 것을 가장 두려워합니다. 출퇴근은 지하철로 편도 40분입니다."}"""

        val summaries = ConversationSummaryParser.parse(raw)

        assertEquals(summaries.detailed, summaries.brief, "brief가 없으면 detailed로 채운다")
        assertNotNull(
            ConversationSummaryParser.findBriefViolation(summaries),
            "상세 요약이 그대로 사용자에게 나가지 않도록 재생성시켜야 한다"
        )
    }
}
