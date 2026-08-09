package com.haruUp.global.util

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class KoreanGrammarCheckerTest {

    @DisplayName("정상적인 질문/미션 문장은 통과한다")
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "하루에 운동에 쓸 수 있는 시간이 몇 분인가요?",
            "평일에 집에 도착하는 시간이 몇 시인가요?",
            "이전에 금연했다가 다시 피우게 된 계기가 무엇이었나요?",
            "유튜브 토익 LC 강의 1편 보고 핵심 패턴 5개 메모하기",
            "아침에 요가 매트 위에서 10분 스트레칭하기",
            "만보기 앱 없이 하루 10000보 걷기",
            "팀 회의 전에 자료 3장 읽고 요점 정리하기",
            "할수록 쉬워지는 스쿼트를 20개씩 3세트 하기"
        ]
    )
    fun `문법 오류가 없으면 null을 반환한다`(text: String) {
        assertNull(KoreanGrammarChecker.findViolation(text))
    }

    @DisplayName("문법이 깨진 문장은 사유를 반환한다")
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
        strings = [
            "한달 20거래일 기준 계산을",       // 조사로 종결
            "스쿼트 20개를",                  // 조사로 종결
            "저녁에 먹는 간식은",              // 조사로 종결
            "공부는 어디서 할수 있나요?",       // 띄어쓰기 오류
            "ㅋㅋ 운동하기",                   // 단독 자음
            "하하하 웃으며 걷기",              // 같은 음절 반복
            "   "                            // 빈 문장
        ]
    )
    fun `문법 오류가 있으면 사유를 반환한다`(text: String) {
        assertNotNull(KoreanGrammarChecker.findViolation(text))
    }
}
