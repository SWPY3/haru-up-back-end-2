package com.haruUp.interest.service

import com.haruUp.interest.dto.*
import com.haruUp.interest.repository.InterestEmbeddingJpaRepository
import com.haruUp.interest.repository.VectorInterestRepository
import com.haruUp.member.domain.MemberProfile
import com.haruUp.global.util.PostgresArrayUtils.listToPostgresArray
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*

/**
 * RAG + AI 하이브리드 관심사 추천 서비스
 *
 * 동작 방식:
 * 1. Vector DB에서 임베딩 기반 유사 관심사 검색 (RAG)
 * 2. 결과가 부족하면 AI로 추가 추천
 * 3. 사용자 직접 입력 지원
 * 4. 검증된 관심사만 점진적으로 임베딩에 추가
 */
@Service
class HybridInterestRecommendationService(
    private val vectorRepository: VectorInterestRepository,
    private val embeddingRepository: InterestEmbeddingJpaRepository,
    private val aiRecommender: AIInterestRecommender
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val RAG_RATIO = 0.7  // RAG로 70% 추천 시도
        private const val MIN_SIMILARITY_SCORE = 0.975f  // 최소 유사도 (임베딩 모델 변경 시 재튜닝 필요)
    }

    /**
     * AI 기반 추천
     *
     * @param selectedInterests 사용자가 이미 선택한 관심사들
     * @param currentLevel 추천받을 레벨 (MAIN, MIDDLE, SUB)
     * @param targetCount 추천받을 개수 (기본 10개)
     * @param memberProfile 멤버 프로필
     * @param jobName 직업명 (선택)
     * @param jobDetailName 직업 상세명 (선택)
     * @return 추천된 관심사 목록
     */
    suspend fun recommend(
        selectedInterests: List<InterestPath>,
        currentLevel: InterestLevel,
        targetCount: Int = 10,
        memberProfile: MemberProfile,
        jobName: String? = null,
        jobDetailName: String? = null
    ): List<InterestNode> {
        logger.info("추천 요청 - 레벨: $currentLevel, 목표: ${targetCount}개, 직업: $jobName, 직업상세: $jobDetailName")

        val aiResults = recommendFromAI(
            selectedInterests = selectedInterests,
            currentLevel = currentLevel,
            excludeNames = emptyList(),
            count = targetCount,
            memberProfile = memberProfile,
            jobName = jobName,
            jobDetailName = jobDetailName
        )

        logger.info("AI 추천 완료: ${aiResults.size}개")

        return aiResults
            .distinctBy { it.name.lowercase().trim() }
            .take(targetCount)
    }

    /**
     * Vector DB에서 유사 관심사 검색 (RAG)
     */
    private suspend fun searchFromVectorDB(
        selectedInterests: List<InterestPath>,
        level: InterestLevel,
        topK: Int,
        useHybridScoring: Boolean = false
    ): List<InterestNode> {
        return try {
            if (selectedInterests.isEmpty()) {
                // 선택된 게 없으면 인기 있는 항목 반환
                embeddingRepository.findPopularByLevel(level.name, topK)
                    .map { it.toInterestNode() }
            } else {
                // 여러 관심사 기반 검색
                if (useHybridScoring) {
                    // 하이브리드 스코어링: 유사도 + 인기도
                    vectorRepository.searchSimilarMultipleWithHybridScore(
                        queries = selectedInterests.map { it.toPathString() },
                        level = level,
                        topK = topK,
                        minScore = MIN_SIMILARITY_SCORE
                    )
                } else {
                    // 기존 방식: 유사도만
                    vectorRepository.searchSimilarMultiple(
                        queries = selectedInterests.map { it.toPathString() },
                        level = level,
                        topK = topK,
                        minScore = MIN_SIMILARITY_SCORE
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("RAG 검색 실패: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * AI로 추가 추천
     */
    private fun recommendFromAI(
        selectedInterests: List<InterestPath>,
        currentLevel: InterestLevel,
        excludeNames: List<String>,
        count: Int,
        memberProfile: MemberProfile,
        jobName: String? = null,
        jobDetailName: String? = null
    ): List<InterestNode> {
        return try {
            aiRecommender.recommend(
                selectedInterests = selectedInterests,
                currentLevel = currentLevel,
                excludeNames = excludeNames,
                count = count,
                memberProfile = memberProfile,
                jobName = jobName,
                jobDetailName = jobDetailName
            )
        } catch (e: Exception) {
            logger.error("AI 추천 실패: ${e.message}", e)
            emptyList()
        }
    }

}
