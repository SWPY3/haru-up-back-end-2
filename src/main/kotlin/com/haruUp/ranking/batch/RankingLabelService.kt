package com.haruUp.ranking.batch

import com.haruUp.global.openai.OpenAiApiClient
import com.haruUp.global.openai.OpenAiEmbeddingClient
import com.haruUp.mission.domain.MemberMissionEntity
import com.haruUp.mission.infrastructure.MemberMissionRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 랭킹 배치에서 사용하는 라벨 처리 서비스
 *
 * 새로운 로직:
 * 1. 기존 라벨이 있으면 사용
 * 2. 관심사 + 미션 내용으로 임베딩 생성
 * 3. member_mission에서 유사 미션 검색
 * 4. 유사 미션 있으면 그 라벨명 사용
 * 5. 없으면 LLM으로 라벨명 생성
 * 6. member_mission에 label_name + embedding 저장
 */
@Service
class RankingLabelService(
    private val memberMissionRepository: MemberMissionRepository,
    private val openAiApiClient: OpenAiApiClient,
    private val openAiEmbeddingClient: OpenAiEmbeddingClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SIMILARITY_THRESHOLD = 0.35
    }

    /**
     * 라벨 처리 로직 (임베딩 유사도 검색 포함)
     *
     * 1. 기존 label_name이 있으면 해당 라벨 사용
     * 2. 관심사 + 미션 내용으로 임베딩 생성
     * 3. member_mission에서 유사 미션 검색
     * 4. 유사 미션 있으면 그 라벨명 복사
     * 5. 없으면 LLM으로 라벨명 생성
     * 6. member_mission에 label_name + embedding 저장
     *
     * @return 라벨명 (실패 시 null)
     */
    @Transactional
    fun processLabel(
        memberMissionId: Long,
        existingLabelName: String?,
        missionContent: String,
        interestPath: List<String>?
    ): String? {
        // 1. 기존 라벨이 있으면 사용
        if (!existingLabelName.isNullOrBlank()) {
            logger.info("기존 라벨 사용: memberMissionId=$memberMissionId, labelName=$existingLabelName")
            return existingLabelName
        }

        // 2. 관심사 + 미션 내용으로 임베딩 생성
        val embeddingText = buildEmbeddingText(missionContent, interestPath)
        val embedding = runBlocking {
            try {
                openAiEmbeddingClient.createEmbedding(embeddingText)
            } catch (e: Exception) {
                logger.error("임베딩 생성 실패: $embeddingText - ${e.message}")
                null
            }
        }

        if (embedding == null) {
            // 임베딩 생성 실패 시 LLM으로 라벨만 생성
            return createLabelWithoutEmbedding(memberMissionId, missionContent, interestPath)
        }

        val embeddingString = MemberMissionEntity.vectorToString(embedding)
        logger.info("임베딩 생성 완료: memberMissionId=$memberMissionId, embeddingSize=${embedding.size}")

        // 3. 유사 미션 검색
        val similarMission = try {
            memberMissionRepository.findSimilarMission(
                embedding = embeddingString,
                threshold = SIMILARITY_THRESHOLD
            )
        } catch (e: Exception) {
            logger.error("유사 미션 검색 실패: ${e.message}")
            null
        }

        val labelName: String
        if (similarMission != null && !similarMission.labelName.isNullOrBlank()) {
            // 4. 유사 미션의 라벨명 사용
            labelName = similarMission.labelName!!
            logger.info("유사 미션 라벨 재사용: memberMissionId=$memberMissionId, " +
                    "similarMissionId=${similarMission.id}, labelName=$labelName")
        } else {
            // 5. LLM으로 라벨명 생성
            val generatedLabel = generateLabelWithLLM(missionContent, interestPath)
            if (generatedLabel.isNullOrBlank()) {
                logger.warn("라벨명 생성 실패: memberMissionId=$memberMissionId")
                return null
            }
            labelName = generatedLabel
            logger.info("LLM 라벨명 생성 완료: memberMissionId=$memberMissionId, labelName=$labelName")
        }

        // 6. member_mission에 label_name + embedding 저장
        val updated = memberMissionRepository.updateLabelNameAndEmbedding(
            id = memberMissionId,
            labelName = labelName,
            embedding = embeddingString,
            updatedAt = LocalDateTime.now()
        )
        logger.info("member_mission 업데이트: memberMissionId=$memberMissionId, labelName=$labelName, updated=$updated")

        return labelName
    }

    /**
     * 임베딩용 텍스트 생성
     * 관심사 경로 + 미션 내용을 조합
     */
    private fun buildEmbeddingText(missionContent: String, interestPath: List<String>?): String {
        return if (!interestPath.isNullOrEmpty()) {
            "${interestPath.joinToString(" > ")} : $missionContent"
        } else {
            missionContent
        }
    }

    /**
     * 임베딩 없이 라벨 생성 (fallback)
     */
    private fun createLabelWithoutEmbedding(
        memberMissionId: Long,
        missionContent: String,
        interestPath: List<String>?
    ): String? {
        val labelName = generateLabelWithLLM(missionContent, interestPath)
        if (labelName.isNullOrBlank()) {
            logger.warn("라벨명 생성 실패 (임베딩 없음): memberMissionId=$memberMissionId")
            return null
        }

        val updated = memberMissionRepository.updateLabelName(
            id = memberMissionId,
            labelName = labelName,
            updatedAt = LocalDateTime.now()
        )
        logger.warn("임베딩 없이 라벨 저장: memberMissionId=$memberMissionId, labelName=$labelName, updated=$updated")
        return labelName
    }

    /**
     * LLM으로 라벨명 생성
     */
    private fun generateLabelWithLLM(missionContent: String, interestPath: List<String>?): String? {
        val interestContext = interestPath?.joinToString(" > ") ?: "없음"
        val prompt = """
            미션 내용을 분석하여 유사한 미션끼리 묶을 수 있는 대표 라벨(그룹명)을 생성해주세요.

            [규칙]
            - 반드시 하나의 활동만 포함 (쉼표로 여러 활동을 나열하지 말 것)
            - 미션에 여러 활동이 있으면 관심사 경로와 가장 관련 있는 활동 하나만 선택
            - 구체적인 숫자, 시간, 횟수는 제외하고 핵심 행동만 추출
            - 10자 이내의 간결한 명사형으로 작성
            - 따옴표, 설명 없이 라벨명만 출력
            - 관심사 경로의 하위 카테고리 수준으로 그룹화하기 좋은 라벨명 작성
            - 너무 구체적인 동작보다는 활동 카테고리 수준으로 표현
            - 동의어는 하나로 통일 (팔굽혀펴기=푸쉬업, 조깅=러닝, 윗몸일으키기=크런치)

            [올바른 예시]
            - 관심사: 외국어 공부 > 영어 > 단어 학습 / 미션: "영어 단어 20개 외우기" → 영어 단어 외우기
            - 관심사: 체력관리 > 유산소 > 러닝 / 미션: "30분 조깅하기" → 러닝
            - 관심사: 체력관리 > 유산소 > 러닝 / 미션: "아침 러닝 5km" → 러닝
            - 관심사: 체력관리 > 유산소 > 러닝 / 미션: "공원 한 바퀴 뛰기" → 러닝
            - 관심사: 건강한 식습관 > 수분 섭취 / 미션: "물 2L 마시기" → 물 마시기
            - 관심사: 체력관리 > 유산소 > 자전거 / 미션: "자전거 타고 스쿼트 30분" → 자전거 타기
            - 관심사: 체력관리 > 다이어트 > 식단 조절 / 미션: "밥 반 공기 덜어내고 먹기" → 식단 조절
            - 관심사: 체력관리 > 근력 운동 > 하체 / 미션: "스쿼트 50개, 런지 30개" → 하체 운동
            - 관심사: 체력관리 > 근력 운동 > 상체 / 미션: "팔굽혀펴기 30개" → 푸쉬업
            - 관심사: 체력관리 > 근력 운동 > 상체 / 미션: "푸쉬업 실시" → 푸쉬업

            [잘못된 예시]
            - "자전거 타기,스쿼트" ← 여러 활동 나열 금지
            - "스트레칭,러닝" ← 쉼표 사용 금지
            - "밥 덜어내기" ← 너무 구체적 (식단 조절이 적절)
            - "반 공기 먹기" ← 너무 구체적 (식사량 조절이 적절)
            - "푸쉬업 실시" ← "푸쉬업"으로 통일

            [관심사 경로]
            $interestContext

            [미션 내용]
            $missionContent

            라벨:
        """.trimIndent()

        return try {
            val response = openAiApiClient.generateText(
                userMessage = prompt,
                systemMessage = "당신은 미션 내용을 분석하여 대표 라벨(그룹명)을 생성하는 전문가입니다. 반드시 하나의 활동만 포함하는 라벨을 생성하세요. 관심사 하위 카테고리 수준으로 그룹화하기 좋은 이름을 만드세요. 동의어는 하나로 통일하세요.",
                model = OpenAiApiClient.MODEL_DEFAULT,
                temperature = 0.1,
                seed = 42
            )

            response.trim()
                .replace("\"", "")
                .replace("'", "")
                .take(100)
                .ifBlank { null }
        } catch (e: Exception) {
            logger.error("LLM 라벨 생성 실패: ${e.message}")
            null
        }
    }
}
