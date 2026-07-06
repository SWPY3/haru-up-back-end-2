package com.haruUp.interest.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.haruUp.interest.entity.InterestEmbeddingEntity
import com.haruUp.interest.dto.InterestLevel
import com.haruUp.interest.dto.InterestNode
import com.haruUp.interest.dto.InterestPath
import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.member.domain.MemberProfile
import com.haruUp.global.util.PostgresArrayUtils.listToPostgresArray
import java.time.Period
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*

/**
 * AI 기반 관심사 추천 서비스
 *
 * RAG로 충분히 추천하지 못할 때 AI가 추가 추천
 */
@Service
class AIInterestRecommender(
    private val openAiApiClient: OpenAiApiClient,
    private val embeddingRepository: com.haruUp.interest.repository.InterestEmbeddingJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    /**
     * AI 추천
     */
    fun recommend(
        selectedInterests: List<InterestPath>,
        currentLevel: InterestLevel,
        excludeNames: List<String>,
        count: Int,
        memberProfile: MemberProfile,
        jobName: String? = null,
        jobDetailName: String? = null
    ): List<InterestNode> {
        logger.info("AI 추천 시작 - 레벨: $currentLevel, 개수: $count, 직업: $jobName, 직업상세: $jobDetailName")

        val prompt = buildPrompt(
            selectedInterests = selectedInterests,
            currentLevel = currentLevel,
            excludeNames = excludeNames,
            count = count,
            memberProfile = memberProfile,
            jobName = jobName,
            jobDetailName = jobDetailName
        )

        try {
            val response = openAiApiClient.generateText(
                userMessage = prompt,
                systemMessage = SYSTEM_PROMPT
            )

            val recommendations = parseResponse(
                response = response,
                level = currentLevel,
                selectedInterests = selectedInterests
            )
            logger.info("AI 추천 성공: ${recommendations.size}개")

            return recommendations

        } catch (e: Exception) {
            logger.error("AI 추천 실패: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * 프롬프트 생성 - 단순화 버전
     */
    private fun buildPrompt(
        selectedInterests: List<InterestPath>,
        currentLevel: InterestLevel,
        excludeNames: List<String>,
        count: Int,
        memberProfile: MemberProfile,
        jobName: String? = null,
        jobDetailName: String? = null
    ): String {
        val sb = StringBuilder()

        // 사용자 정보
        sb.appendLine("사용자 정보: ${formatMemberProfile(memberProfile)}")

        // 직업상세 정보 (SUB 레벨에서 직무 전문 분야일 때 핵심)
        if (jobDetailName != null) {
            sb.appendLine("직업상세: $jobDetailName")
        } else if (jobName != null) {
            sb.appendLine("직업: $jobName")
        }

        // 선택된 관심사 경로
        if (selectedInterests.isNotEmpty()) {
            val firstPath = selectedInterests.first()
            sb.appendLine("선택된 경로: ${firstPath.toPathString()}")

            // 추천 요청
            when (currentLevel) {
                InterestLevel.MAIN -> {
                    sb.appendLine("추천 요청: 대분류")
                }
                InterestLevel.MIDDLE -> {
                    sb.appendLine("추천 요청: '${firstPath.mainCategory}'의 중분류")
                }
                InterestLevel.SUB -> {
                    val parentPath = listOfNotNull(firstPath.mainCategory, firstPath.middleCategory).joinToString(" > ")
                    sb.appendLine("추천 요청: '${parentPath}'의 소분류(목표)")
                }
            }
        }

        // 제외 항목
        if (excludeNames.isNotEmpty()) {
            sb.appendLine("제외: ${excludeNames.joinToString(", ")}")
        }

        sb.appendLine("개수: ${count}개")

        return sb.toString()
    }

    /**
     * 응답 파싱 및 DB 저장
     */
    private fun parseResponse(
        response: String,
        level: InterestLevel,
        selectedInterests: List<InterestPath>
    ): List<InterestNode> {
        return try {
            val jsonResponse = objectMapper.readValue<AIRecommendationResponse>(response.trim())

            jsonResponse.interest.mapNotNull { name ->
                var parentId: String? = null

                // 부모 경로 리스트 생성
                var parentPathList: List<String>? = null

                // 부모 정보 설정
                if (selectedInterests.isNotEmpty()) {
                    val firstPath = selectedInterests.first()

                    when (level) {
                        InterestLevel.MAIN -> {
                            // 대분류는 부모 없음
                            parentPathList = null
                        }
                        InterestLevel.MIDDLE -> {
                            // 중분류의 부모는 대분류
                            parentPathList = listOf(firstPath.mainCategory)
                        }
                        InterestLevel.SUB -> {
                            // 소분류의 부모는 [대분류, 중분류]
                            parentPathList = if (firstPath.middleCategory != null) {
                                listOf(firstPath.mainCategory, firstPath.middleCategory!!)
                            } else {
                                listOf(firstPath.mainCategory)
                            }
                        }
                    }

                    // parentPathList로부터 parentId 조회
                    parentPathList?.let { pPathList ->
                        embeddingRepository.findIdByFullPath(listToPostgresArray(pPathList))?.let { id ->
                            parentId = id.toString()
                        }
                    }
                }

                // fullPath 계산 (List<String> 형태)
                val fullPath: List<String> = if (parentPathList != null) {
                    parentPathList + name
                } else {
                    listOf(name)
                }

                // DB에 저장하지 않고 메모리에서만 생성하여 반환
                InterestNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    level = level,
                    parentId = parentId,
                    fullPath = fullPath,
                    isEmbedded = false,
                    isUserGenerated = false,
                    usageCount = 0,
                    createdAt = LocalDateTime.now()
                )
            }
        } catch (e: Exception) {
            logger.error("AI 응답 파싱 실패: ${e.message}")
            emptyList()
        }
    }

    private fun formatMemberProfile(profile: MemberProfile): String {
        val parts = mutableListOf<String>()
        profile.birthDt?.let { birthDt ->
            val age = Period.between(birthDt.toLocalDate(), LocalDateTime.now().toLocalDate()).years
            parts.add("${age}세")
        }
        profile.gender?.let { parts.add(it.name) }
        return parts.joinToString(", ")
    }

    private data class AIRecommendationResponse(
        val interest: List<String>
    )

    companion object {
        private const val SYSTEM_PROMPT = """
관심사 추천 전문가입니다.

## 핵심 규칙
1. 대중적이고 보편적인 관심사를 우선 추천 (많은 사람들이 관심 가질 만한 것)
2. 직업상세가 있으면 해당 직업에 특화된 세부 분야를 추천
   - 예: 직업상세 "요리사" + "직무 전문 분야" → 한식, 중식, 일식, 양식 등
   - 예: 직업상세 "개발자" + "직무 전문 분야" → 백엔드, 프론트엔드, 클라우드 등
3. 제외 항목은 절대 추천 금지
4. 요청된 개수만큼 정확히 추천
5. 각 항목은 2-10자 내 간결하게

## 응답 형식
{"interest": ["항목1", "항목2", ...]}
"""
    }
}
