package com.haruUp.global.prompt

import com.haruUp.interest.dto.InterestPath

object ImprovedMissionRecommendationPrompt {

    /**
     * 미션 추천 시스템 프롬프트 (v3 - 간결화)
     */
    const val SYSTEM_PROMPT = """
당신은 미션 추천 AI입니다.

【필수 규칙】
1. difficulty는 1, 2, 3, 4, 5 각각 정확히 1번씩만 사용 (중복 금지)
2. content는 10-30자, 반드시 한국어로만 작성
3. JSON만 출력 (마크다운, 설명 금지)

【출력 형식】
{"missions":[{"content":"미션1","relatedInterest":["대","중","소"],"difficulty":1},{"content":"미션2","relatedInterest":["대","중","소"],"difficulty":2},{"content":"미션3","relatedInterest":["대","중","소"],"difficulty":3},{"content":"미션4","relatedInterest":["대","중","소"],"difficulty":4},{"content":"미션5","relatedInterest":["대","중","소"],"difficulty":5}]}
"""

    /**
     * 사용자 메시지 생성 - 전체 관심사 기반
     */
    fun createUserMessageForAllInterests(
        interests: List<InterestPath>,
        missionMemberProfile: MissionMemberProfile
    ): String {
        val sb = StringBuilder()

        // 사용자 정보
        sb.append("사용자 정보: ${formatMissionMemberProfile(missionMemberProfile)}\n")

        // 관심사 목록
        val pathStrings = interests.map { it.toPathString() }
        sb.append("관심사: [${pathStrings.joinToString(", ")}]\n")

        return sb.toString()
    }

    /**
     * 미션 멤버 프로필을 읽기 쉬운 형식으로 변환
     */
    private fun formatMissionMemberProfile(profile: MissionMemberProfile): String {
        val parts = mutableListOf<String>()

        profile.age?.let { parts.add("${it}세") }
        profile.gender?.let {
            val genderKorean = when (it) {
                "MALE" -> "남성"
                "FEMALE" -> "여성"
                else -> it
            }
            parts.add(genderKorean)
        }
        profile.jobName?.let { parts.add("직업: $it") }
        profile.jobDetailName?.let { parts.add("직업상세: $it") }

        return parts.joinToString(", ")
    }
}

/**
 * 미션 추천을 위한 멤버 프로필 정보
 *
 * @property age 나이 (선택)
 * @property gender 성별 (선택, 예: "MALE", "FEMALE")
 * @property jobName 직업명 (선택, 예: "학생", "직장인")
 * @property jobDetailName 직업 상세명 (선택, 예: "대학생", "IT 개발자")
 */
data class MissionMemberProfile(
    val age: Int? = null,
    val gender: String? = null,
    val jobName: String? = null,
    val jobDetailName: String? = null
)
