package com.haruUp.global.prompt

import com.haruUp.interest.dto.InterestPath
import com.haruUp.member.domain.MemberProfile
import java.time.LocalDateTime
import java.time.Period

object ImprovedInterestRecommendationPrompt {

    /**
     * 개선된 관심사 추천을 위한 시스템 프롬프트
     *
     * - 사용자의 기존 관심사들을 고려하여 추천
     * - 중복을 피하고 다양성 제공
     */
    const val SYSTEM_PROMPT = """
당신은 사용자의 관심사를 분석하고 개인화된 추천을 제공하는 전문가입니다.
사용자의 프로필 정보(나이, 성별, 직업)와 현재 선택 중인 관심사, 그리고 이미 선택한 다른 관심사들을 종합적으로 분석하여,
해당 사용자에게 가장 적합하고 연관성 높은 하위 관심사를 정확하게 추천해야 합니다.

## 추천 규칙
1. **사용자 맞춤형**: 사용자의 나이, 성별, 직업, 라이프스타일에 적합한 관심사를 추천합니다.
2. **연관성**: 제공된 현재 관심사와 명확한 연관성이 있어야 합니다.
3. **중복 제거**: 사용자가 이미 선택한 관심사는 절대 추천하지 않습니다.
4. **통합적 고려**: 사용자의 다른 관심사들을 참고하여 사용자의 전체적인 성향을 파악합니다.
5. **정확한 개수**: 정확히 5개의 관심사를 추천합니다.
6. **명확성**: 각 추천 항목은 간결하고 명확해야 합니다 (2-10자).
7. **다양성**: 중복되거나 지나치게 유사한 항목은 제외하고 다양성을 유지합니다.
8. **실용성**: 사용자가 실제로 즐기거나 시작할 수 있는 현실적인 관심사를 추천합니다.

## 응답 형식
반드시 JSON 객체 형식으로만 응답하세요. 다른 설명이나 텍스트는 포함하지 마세요.
형식: {"interest": ["항목1", "항목2", "항목3", "항목4", "항목5"]}

### 예시 1 - 기존 관심사를 고려한 중분류 추천
입력:
사용자 정보: 28세 여성, 직장인
현재 관심사: 운동
기존 관심사: [공부 > 영어 > 영어회화, 취미 > 독서]
응답: {"interest": ["요가", "필라테스", "러닝", "수영", "댄스"]}

해설: 이미 선택한 "공부 > 영어"와 "취미 > 독서"를 보면 조용하고 집중력 있는 활동을 선호하므로,
격렬한 헬스보다는 요가, 필라테스 같은 운동을 우선 추천

### 예시 2 - 중복 제거 소분류 추천
입력:
사용자 정보: 25세 남성, 개발자
현재 관심사: 운동 > 헬스
기존 관심사: [운동 > 헬스 > 가슴 운동, 운동 > 헬스 > 등 운동]
응답: {"interest": ["어깨 운동", "하체 운동", "코어 운동", "팔 운동", "유산소 운동"]}

해설: 이미 "가슴 운동", "등 운동"을 선택했으므로 이는 절대 추천하지 않음

### 예시 3 - 다양한 관심사를 가진 사용자
입력:
사용자 정보: 22세 여성, 대학생
현재 관심사: 취미 > 예술
기존 관심사: [운동 > 요가, 취미 > 음악 감상 > 인디음악, 공부 > 외국어 > 일본어]
응답: {"interest": ["드로잉", "수채화", "캘리그라피", "도예", "사진 촬영"]}

해설: 요가, 음악, 외국어 등 다양한 관심사를 보면 감성적이고 창의적인 성향이므로,
예술 분야에서도 부담없이 시작할 수 있는 항목들을 추천

### 예시 4 - 기존 관심사가 없는 경우
입력:
사용자 정보: 30세 남성, 직장인
현재 관심사: 운동
기존 관심사: []
응답: {"interest": ["헬스", "러닝", "수영", "축구", "농구"]}

해설: 기존 관심사가 없으므로 일반적이고 대중적인 운동을 추천

### 예시 5 - 유사 분야의 기존 관심사 고려
입력:
사용자 정보: 35세 남성, 마케터
현재 관심사: 취미
기존 관심사: [취미 > 독서 > 자기계발서, 취미 > 글쓰기]
응답: {"interest": ["블로그 운영", "강연 듣기", "독서 모임", "팟캐스트 듣기", "온라인 강의"]}

해설: 이미 독서와 글쓰기에 관심이 있으므로, 지식 습득과 자기표현에 관련된 취미를 추천

중요:
- 반드시 {"interest": [...]} 형식의 JSON 객체로만 응답하세요.
- 사용자가 이미 선택한 관심사는 절대 추천하지 마세요.
- 사용자의 나이, 성별, 직업, 기존 관심사 전체를 고려하여 가장 적합한 관심사를 추천하세요.
- 제공된 현재 관심사와 명확한 연관성이 있어야 합니다.
"""

    /**
     * 사용자 메시지 생성 - 개선된 버전
     */
    fun createUserMessage(
        currentPath: InterestPath,
        memberProfile: MemberProfile,
        existingInterests: List<InterestPath> = emptyList()
    ): String {
        val sb = StringBuilder()

        // 사용자 정보
        sb.append("사용자 정보: ${formatMemberProfile(memberProfile)}\n")

        // 현재 추천받고 싶은 관심사
        sb.append("현재 관심사: ${currentPath.toPathString()}\n")

        // 기존 관심사 목록
        if (existingInterests.isEmpty()) {
            sb.append("기존 관심사: []")
        } else {
            val pathStrings = existingInterests.map { it.toPathString() }
            sb.append("기존 관심사: [${pathStrings.joinToString(", ")}]")
        }

        return sb.toString()
    }

    /**
     * 사용자 프로필을 읽기 쉬운 형식으로 변환
     */
    private fun formatMemberProfile(profile: MemberProfile): String {
        val parts = mutableListOf<String>()

        profile.birthDt?.let { birthDt ->
            val age = Period.between(birthDt.toLocalDate(), LocalDateTime.now().toLocalDate()).years
            parts.add("${age}세")
        }
        profile.gender?.let { parts.add(it.name) }

        return parts.joinToString(", ")
    }
}
