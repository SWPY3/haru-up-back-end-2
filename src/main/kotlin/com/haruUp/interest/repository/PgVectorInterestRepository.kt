package com.haruUp.interest.repository

import com.haruUp.interest.entity.InterestEmbeddingEntity
import com.haruUp.interest.dto.InterestLevel
import com.haruUp.interest.dto.InterestNode
import com.haruUp.global.openai.OpenAiEmbeddingClient
import com.haruUp.global.util.PostgresArrayUtils.listToPostgresArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * pgvector를 사용한 VectorInterestRepository 구현
 *
 * - PostgreSQL + pgvector extension
 * - 코사인 유사도 기반 검색
 * - OpenAI Embedding (1024차원)
 */
@Repository
class PgVectorInterestRepository(
    private val embeddingJpaRepository: InterestEmbeddingJpaRepository,
    private val openAiEmbeddingClient: OpenAiEmbeddingClient
) : VectorInterestRepository {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 임베딩 데이터 삽입
     */
    override suspend fun insert(
        interestId: String,
        name: String,
        level: InterestLevel,
        fullPath: List<String>,
        embedding: List<Float>,
        metadata: Map<String, Any>
    ) {
        withContext(Dispatchers.IO) {
            try {
                // 새 임베딩 저장 (Native Query 사용)
                embeddingJpaRepository.insertEmbedding(
                    name = name,
                    level = level.name,
                    parentId = metadata["parentId"] as? String,
                    fullPath = listToPostgresArray(fullPath),
                    embedding = InterestEmbeddingEntity.vectorToString(embedding),
                    usageCount = metadata["usageCount"] as? Int ?: 0,
                    createdSource = metadata["createdSource"] as? String ?: "SYSTEM",
                    isActivated = metadata["isActivated"] as? Boolean ?: true,
                    createdAt = LocalDateTime.now()
                )

                logger.info("임베딩 저장 완료: $name")

            } catch (e: Exception) {
                logger.error("임베딩 저장 실패: $name - ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * 유사 관심사 검색 (단일 쿼리)
     */
    override suspend fun searchSimilar(
        query: String,
        level: InterestLevel,
        topK: Int,
        minScore: Float
    ): List<InterestNode> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 쿼리 임베딩 생성
                val queryEmbedding = openAiEmbeddingClient.createEmbedding(query)

                // 2. pgvector 검색
                searchByVector(queryEmbedding, level, topK, minScore)

            } catch (e: Exception) {
                logger.error("유사 검색 실패: $query - ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 유사 관심사 검색 (다중 쿼리)
     */
    override suspend fun searchSimilarMultiple(
        queries: List<String>,
        level: InterestLevel,
        topK: Int,
        minScore: Float
    ): List<InterestNode> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 여러 쿼리의 임베딩 생성
                val embeddings = queries.map { openAiEmbeddingClient.createEmbedding(it) }

                // 2. 임베딩 평균 계산
                val avgEmbedding = averageEmbeddings(embeddings)

                // 3. 평균 벡터로 검색
                searchByVector(avgEmbedding, level, topK, minScore)

            } catch (e: Exception) {
                logger.error("다중 유사 검색 실패: ${queries.joinToString(",")} - ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 벡터로 직접 검색
     */
    override suspend fun searchByVector(
        vector: List<Float>,
        level: InterestLevel,
        topK: Int,
        minScore: Float
    ): List<InterestNode> {
        return withContext(Dispatchers.IO) {
            try {
                val vectorString = InterestEmbeddingEntity.vectorToString(vector)

                // pgvector 코사인 유사도 검색
                val results = if (minScore > 0) {
                    // 임계값 적용
                    embeddingJpaRepository.findSimilarWithScore(
                        embedding = vectorString,
                        level = level.name,
                        threshold = minScore.toDouble(),
                        limit = topK
                    )
                } else {
                    // 임계값 없이 상위 K개
                    embeddingJpaRepository.findSimilarByLevel(
                        embedding = vectorString,
                        level = level.name,
                        limit = topK
                    )
                }

                // Entity → Model 변환 (임베딩 테이블에서 직접 변환)
                results.map { entity ->
                    entity.toInterestNode()
                }

            } catch (e: Exception) {
                logger.error("벡터 검색 실패: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 하이브리드 검색 (유사도 + 인기도)
     */
    override suspend fun searchSimilarWithHybridScore(
        query: String,
        level: InterestLevel,
        topK: Int,
        minScore: Float
    ): List<InterestNode> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 쿼리 임베딩 생성
                val queryEmbedding = openAiEmbeddingClient.createEmbedding(query)
                val vectorString = InterestEmbeddingEntity.vectorToString(queryEmbedding)

                // 2. 하이브리드 스코어로 검색
                val results = embeddingJpaRepository.findSimilarWithHybridScore(
                    embedding = vectorString,
                    level = level.name,
                    threshold = minScore.toDouble(),
                    limit = topK
                )

                // 3. Entity → Model 변환 (임베딩 테이블에서 직접 변환)
                results.map { entity ->
                    entity.toInterestNode()
                }

            } catch (e: Exception) {
                logger.error("하이브리드 검색 실패: $query - ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 하이브리드 검색 (다중 쿼리)
     */
    override suspend fun searchSimilarMultipleWithHybridScore(
        queries: List<String>,
        level: InterestLevel,
        topK: Int,
        minScore: Float
    ): List<InterestNode> {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 여러 쿼리의 임베딩 생성
                val embeddings = queries.map { openAiEmbeddingClient.createEmbedding(it) }

                // 2. 임베딩 평균 계산
                val avgEmbedding = averageEmbeddings(embeddings)
                val vectorString = InterestEmbeddingEntity.vectorToString(avgEmbedding)

                // 3. 하이브리드 스코어로 검색
                val results = embeddingJpaRepository.findSimilarWithHybridScore(
                    embedding = vectorString,
                    level = level.name,
                    threshold = minScore.toDouble(),
                    limit = topK
                )

                // 4. Entity → Model 변환 (임베딩 테이블에서 직접 변환)
                results.map { entity ->
                    entity.toInterestNode()
                }

            } catch (e: Exception) {
                logger.error("다중 하이브리드 검색 실패: ${queries.joinToString(",")} - ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * 특정 관심사 삭제
     */
    override suspend fun delete(interestId: String) {
        withContext(Dispatchers.IO) {
            try {
                val id = interestId.toLongOrNull()
                if (id != null) {
                    embeddingJpaRepository.deleteById(id)
                    logger.info("임베딩 삭제 완료: $interestId")
                } else {
                    logger.warn("잘못된 ID 형식: $interestId")
                }
            } catch (e: Exception) {
                logger.error("임베딩 삭제 실패: $interestId - ${e.message}", e)
            }
        }
    }

    /**
     * 임베딩 업데이트
     */
    override suspend fun update(
        interestId: String,
        embedding: List<Float>,
        metadata: Map<String, Any>
    ) {
        withContext(Dispatchers.IO) {
            try {
                val id = interestId.toLongOrNull()
                if (id != null) {
                    // 기존 임베딩 entity에서 정보 가져오기
                    val existingEntity = embeddingJpaRepository.findById(id).orElse(null)
                    if (existingEntity != null) {
                        // 삭제 후 재삽입
                        delete(interestId)
                        insert(
                            interestId = interestId,
                            name = existingEntity.name,
                            level = existingEntity.level,
                            fullPath = existingEntity.fullPath,
                            embedding = embedding,
                            metadata = metadata
                        )
                        logger.info("임베딩 업데이트 완료: $interestId")
                    } else {
                        logger.warn("임베딩을 찾을 수 없음: $interestId")
                    }
                } else {
                    logger.warn("잘못된 ID 형식: $interestId")
                }
            } catch (e: Exception) {
                logger.error("임베딩 업데이트 실패: $interestId - ${e.message}", e)
            }
        }
    }

    /**
     * 임베딩 평균 계산
     */
    private fun averageEmbeddings(embeddings: List<List<Float>>): List<Float> {
        if (embeddings.isEmpty()) return emptyList()
        if (embeddings.size == 1) return embeddings[0]

        val size = embeddings[0].size
        val result = FloatArray(size) { 0f }

        embeddings.forEach { embedding ->
            embedding.forEachIndexed { index, value ->
                result[index] += value
            }
        }

        val count = embeddings.size.toFloat()
        return result.map { it / count }
    }
}
