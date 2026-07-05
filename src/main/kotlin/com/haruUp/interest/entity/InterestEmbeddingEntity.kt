package com.haruUp.interest.entity

import com.haruUp.global.common.BaseEntity
import com.haruUp.interest.dto.InterestLevel
import com.haruUp.interest.dto.InterestNode
import io.hypersistence.utils.hibernate.type.array.ListArrayType
import jakarta.persistence.*
import org.hibernate.annotations.Type
import java.time.LocalDateTime

/**
 * 관심사 임베딩 Entity (pgvector)
 *
 * pgvector extension을 사용하여 벡터 저장 및 검색
 */
@Entity
@Table(
    name = "interest_embeddings",
    indexes = [
        Index(name = "idx_level", columnList = "level"),
        Index(name = "idx_is_activated", columnList = "is_activated")
    ]
)
class InterestEmbeddingEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val level: InterestLevel,

    /**
     * 전체 경로 배열 (PostgreSQL TEXT[])
     * 예: ["외국어 공부", "일본어", "단어 학습"]
     */
    @Type(ListArrayType::class)
    @Column(name = "full_path", nullable = false, columnDefinition = "text[]")
    val fullPath: List<String>,

    @Column(name = "parent_id")
    val parentId: String? = null,

    /**
     * 임베딩 벡터 (pgvector type)
     * OpenAI Embedding: 1024차원
     * NULL 허용: 임베딩되지 않은 관심사
     */
    @Column(name = "embedding", columnDefinition = "vector(1024)")
    val embedding: String? = null,  // "[0.1, 0.2, ...]" 형태의 문자열

    @Column(name = "usage_count")
    val usageCount: Int = 0,

    @Column(name = "created_source", length = 20)
    val createdSource: String = "SYSTEM",  // "SYSTEM" or "USER"

    @Column(name = "is_activated")
    val isActivated: Boolean = true
) : BaseEntity() {
    /**
     * 임베딩 벡터를 Float 리스트로 변환
     */
    fun getEmbeddingVector(): List<Float>? {
        return embedding?.let {
            it.trim('[', ']')
                .split(',')
                .map { value -> value.trim().toFloat() }
        }
    }

    /**
     * Entity → Model 변환
     */
    fun toInterestNode(): InterestNode {
        return InterestNode(
            id = id.toString(),
            name = name,
            level = level,
            parentId = parentId,
            fullPath = fullPath,
            isEmbedded = embedding != null,  // embedding이 있으면 true
            isUserGenerated = createdSource == "USER",
            usageCount = usageCount,
            createdBy = null
        )
    }

    companion object {
        /**
         * Float 리스트를 pgvector 문자열로 변환
         */
        fun vectorToString(vector: List<Float>): String {
            return vector.joinToString(separator = ",", prefix = "[", postfix = "]")
        }
    }
}
