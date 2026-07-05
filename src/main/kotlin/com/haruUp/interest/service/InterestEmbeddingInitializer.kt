package com.haruUp.interest.service

import com.haruUp.interest.dto.InterestLevel
import com.haruUp.interest.repository.InterestEmbeddingJpaRepository
import com.haruUp.interest.repository.VectorInterestRepository
import com.haruUp.global.openai.OpenAiEmbeddingClient
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 관심사 임베딩 초기화 서비스
 *
 * interest_embeddings 테이블의 임베딩 벡터를 생성/업데이트합니다.
 */
@Service
class InterestEmbeddingInitializer(
    private val vectorRepository: VectorInterestRepository,
    private val openAiEmbeddingClient: OpenAiEmbeddingClient,
    private val embeddingJpaRepository: InterestEmbeddingJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 모든 관심사에 대해 임베딩 생성
     *
     * @param forceUpdate 이미 임베딩된 항목도 재생성할지 여부
     * @param source 특정 created_source만 재임베딩 (null이면 모든 source 포함)
     * @return 생성된 임베딩 개수
     */
    suspend fun initializeAllEmbeddings(
        forceUpdate: Boolean = false,
        source: String? = null
    ): EmbeddingInitResult {
        logger.info("=== 관심사 임베딩 초기화 시작 ===")
        logger.info("강제 업데이트: $forceUpdate")
        logger.info("Source 필터: ${source ?: "ALL"}")

        val startTime = System.currentTimeMillis()
        var successCount = 0
        var failCount = 0
        var skipCount = 0

        // interest_embeddings 테이블에서 조회하여 임베딩 생성/재생성
        val embeddingEntities = if (source != null) {
            logger.info("특정 source($source)의 임베딩 모드")
            embeddingJpaRepository.findByCreatedSource(source)
        } else {
            logger.info("전체 임베딩 모드")
            embeddingJpaRepository.findAll()
        }

        logger.info("대상 임베딩: ${embeddingEntities.size}개")

        for (entity in embeddingEntities) {
            try {
                // 이미 임베딩된 경우 스킵 (forceUpdate=false인 경우)
                if (!forceUpdate && entity.embedding != null) {
                    logger.debug("이미 임베딩됨, 스킵: ${entity.name}")
                    skipCount++
                    continue
                }

                // 임베딩 생성
                val embeddingText = buildEmbeddingText(entity.name, entity.fullPath)
                logger.info("임베딩 생성: ${entity.name} (${entity.fullPath})")
                logger.debug("임베딩 텍스트: $embeddingText")

                val embedding = openAiEmbeddingClient.createEmbedding(embeddingText)

                if (embedding.isEmpty()) {
                    logger.error("임베딩 생성 실패 (빈 벡터): ${entity.name}")
                    failCount++
                    continue
                }

                // 임베딩 업데이트
                vectorRepository.update(
                    interestId = entity.id.toString(),
                    embedding = embedding,
                    metadata = mapOf(
                        "usageCount" to entity.usageCount,
                        "isUserGenerated" to (entity.createdSource == "USER")
                    )
                )

                successCount++
                logger.info("✓ 임베딩 완료: ${entity.name}")

                // API Rate Limit 방지 (100ms 대기)
                delay(100)

            } catch (e: Exception) {
                logger.error("임베딩 생성 실패: ${entity.name} - ${e.message}", e)
                failCount++
            }
        }

        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0

        logger.info("=== 임베딩 초기화 완료 ===")
        logger.info("성공: ${successCount}개")
        logger.info("실패: ${failCount}개")
        logger.info("스킵: ${skipCount}개")
        logger.info("총 소요 시간: ${elapsedTime}초")

        return EmbeddingInitResult(
            successCount = successCount,
            failCount = failCount,
            skipCount = skipCount,
            elapsedSeconds = elapsedTime
        )
    }

    /**
     * 특정 레벨만 임베딩 생성
     */
    suspend fun initializeByLevel(
        level: InterestLevel,
        forceUpdate: Boolean = false,
        source: String? = null
    ): EmbeddingInitResult {
        logger.info("=== $level 레벨 임베딩 초기화 시작 ===")
        logger.info("Source 필터: ${source ?: "ALL"}")

        val startTime = System.currentTimeMillis()
        var successCount = 0
        var failCount = 0
        var skipCount = 0

        // interest_embeddings 테이블에서 조회
        val embeddingEntities = if (source != null) {
            embeddingJpaRepository.findByCreatedSourceAndLevel(source, level.name)
        } else {
            embeddingJpaRepository.findByLevelAndIsActivated(level.name, true)
        }

        logger.info("$level 레벨 임베딩: ${embeddingEntities.size}개")

        for (entity in embeddingEntities) {
            try {
                // 이미 임베딩된 경우 스킵 (forceUpdate=false인 경우)
                if (!forceUpdate && entity.embedding != null) {
                    skipCount++
                    continue
                }

                val embeddingText = buildEmbeddingText(entity.name, entity.fullPath)
                val embedding = openAiEmbeddingClient.createEmbedding(embeddingText)

                if (embedding.isEmpty()) {
                    failCount++
                    continue
                }

                vectorRepository.update(
                    interestId = entity.id.toString(),
                    embedding = embedding,
                    metadata = mapOf(
                        "usageCount" to entity.usageCount,
                        "isUserGenerated" to (entity.createdSource == "USER")
                    )
                )

                successCount++
                delay(100)

            } catch (e: Exception) {
                logger.error("임베딩 생성 실패: ${entity.name} - ${e.message}")
                failCount++
            }
        }

        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0

        return EmbeddingInitResult(
            successCount = successCount,
            failCount = failCount,
            skipCount = skipCount,
            elapsedSeconds = elapsedTime
        )
    }

    /**
     * 임베딩 텍스트 구성
     *
     * 의미있는 컨텍스트를 포함한 텍스트 생성하여 임베딩 품질 향상
     *
     * 예시:
     * - MAIN: "관심사 대분류: 운동. 신체 활동과 체력 단련에 관한 관심사입니다."
     * - MIDDLE: "관심사 분류: 운동 분야의 헬스. 체육관에서 근력 운동과 웨이트 트레이닝을 하는 활동입니다."
     * - SUB: "관심사 세부 분류: 운동 > 헬스 > 가슴운동. 벤치프레스, 덤벨 플라이 등 가슴 근육을 단련하는 운동입니다."
     */
    private fun buildEmbeddingText(name: String, fullPath: List<String>): String {
        return when (fullPath.size) {
            // MAIN 레벨 (대분류)
            1 -> {
                "관심사 대분류: ${name}. ${getMainCategoryDescription(name)}"
            }
            // MIDDLE 레벨 (중분류)
            2 -> {
                val main = fullPath[0]
                "관심사 중분류: ${main} 분야의 ${name}. ${getMiddleCategoryDescription(main, name)}"
            }
            // SUB 레벨 (소분류)
            3 -> {
                val main = fullPath[0]
                val middle = fullPath[1]
                "관심사 소분류: ${main} > ${middle} > ${name}. ${getSubCategoryDescription(main, middle, name)}"
            }
            // fallback
            else -> "관심사: ${fullPath.joinToString(" > ").ifBlank { name }}"
        }
    }

    /**
     * 대분류 설명 생성
     */
    private fun getMainCategoryDescription(name: String): String {
        return when (name) {
            "운동" -> "신체 활동과 체력 단련, 건강 증진에 관한 관심사입니다."
            "공부" -> "학습과 지식 습득, 교육 활동에 관한 관심사입니다."
            "요리" -> "음식 조리와 요리, 식재료 다루기에 관한 관심사입니다."
            "예술" -> "창작 활동과 예술적 표현, 미적 감각에 관한 관심사입니다."
            "음악" -> "음악 감상과 연주, 음악 활동에 관한 관심사입니다."
            else -> "다양한 활동과 취미에 관한 관심사입니다."
        }
    }

    /**
     * 중분류 설명 생성
     */
    private fun getMiddleCategoryDescription(main: String, middle: String): String {
        return when (main) {
            "운동" -> when (middle) {
                "헬스" -> "체육관에서 근력 운동과 웨이트 트레이닝을 하는 활동입니다."
                "요가" -> "요가 자세와 호흡법을 통해 심신을 단련하는 활동입니다."
                "러닝" -> "달리기와 조깅을 통해 체력과 지구력을 기르는 활동입니다."
                "수영" -> "수영장에서 수영 기술을 연습하고 체력을 기르는 활동입니다."
                else -> "운동을 통해 건강과 체력을 증진하는 활동입니다."
            }
            "공부" -> when (middle) {
                "영어" -> "영어 공부와 회화, 문법, 독해 학습 활동입니다."
                "수학" -> "수학 개념과 문제 풀이, 논리적 사고력 학습 활동입니다."
                "독서" -> "책을 읽고 지식을 습득하는 독서 활동입니다."
                "코딩" -> "프로그래밍과 코딩 학습, 소프트웨어 개발 활동입니다."
                else -> "학습과 지식 습득을 위한 공부 활동입니다."
            }
            "요리" -> when (middle) {
                "한식" -> "한국 전통 음식을 만들고 조리하는 요리 활동입니다."
                "양식" -> "서양식 요리와 음식을 만드는 조리 활동입니다."
                else -> "음식을 만들고 조리하는 요리 활동입니다."
            }
            "예술" -> when (middle) {
                "그림" -> "그림을 그리고 미술 작품을 창작하는 활동입니다."
                "사진" -> "사진 촬영과 사진 예술 활동입니다."
                else -> "창작과 예술적 표현 활동입니다."
            }
            "음악" -> when (middle) {
                "피아노" -> "피아노 연주와 음악 학습 활동입니다."
                "기타" -> "기타 연주와 음악 활동입니다."
                else -> "음악 연주와 감상 활동입니다."
            }
            else -> "${main} 분야의 ${middle} 활동입니다."
        }
    }

    /**
     * 소분류 설명 생성
     */
    private fun getSubCategoryDescription(main: String, middle: String, sub: String): String {
        // 소분류는 기본 설명 제공 (필요시 확장 가능)
        return "${main} 분야의 ${middle} 중에서 ${sub}에 특화된 활동입니다."
    }
}

/**
 * 임베딩 초기화 결과
 */
data class EmbeddingInitResult(
    val successCount: Int,
    val failCount: Int,
    val skipCount: Int,
    val elapsedSeconds: Double
) {
    val totalProcessed: Int
        get() = successCount + failCount

    fun summary(): String {
        return """
            임베딩 초기화 완료
            - 성공: ${successCount}개
            - 실패: ${failCount}개
            - 스킵: ${skipCount}개
            - 총 처리: ${totalProcessed}개
            - 소요 시간: ${String.format("%.2f", elapsedSeconds)}초
        """.trimIndent()
    }
}
