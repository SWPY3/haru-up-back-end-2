package com.haruUp.missionembedding.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * ================================
 * 미션 추천 요청/응답 DTO
 * ================================
 */

/**
 * 미션 추천 요청
 *
 * member_interest 테이블의 ID 목록을 받아서 해당 관심사들에 대한 미션 추천
 * - 사전에 관심사가 등록되어 있어야 함 (/api/interests/member)
 * - 각 관심사당 난이도 1~5 각각 1개씩, 총 5개의 미션이 추천됨
 */
@Schema(description = "미션 추천 요청")
data class MissionRecommendationRequest(
    @Schema(
        description = "멤버 관심사 ID 목록 (챗봇 목표 기반 미션 조회는 [0], 실제 관심사 ID와 혼합 불가)",
        example = "[1, 2, 3]",
        required = true
    )
    val memberInterestIds: List<Long>
)

/**
 * 미션 추천 응답
 */
@Schema(description = "미션 추천 응답")
data class MissionRecommendationResponse(
    @Schema(description = "관심사별 추천된 미션 그룹 목록")
    val missions: List<MissionGroupDto>,

    @Schema(description = "총 추천된 미션 개수", example = "10")
    val totalCount: Int,

    @Schema(description = "미션 재추천한 개수", example = "3")
    val retryCount: Long? = 0
)

/**
 * 관심사별 미션 그룹 DTO
 */
@Schema(description = "관심사별 미션 그룹")
data class MissionGroupDto(
    @Schema(description = "멤버 관심사 번호", example = "1")
    val memberInterestId: Int?,

    @Schema(description = "해당 관심사의 미션 목록")
    val data: List<MissionDto>
)

/**
 * 미션 DTO
 */
@Schema(description = "미션 정보")
data class MissionDto(
    @Schema(description = "member_mission 테이블 ID", example = "123")
    val member_mission_id: Long? = null,

    @Schema(description = "미션 내용", example = "주 3회 가슴 운동 루틴 완수하기")
    val content: String,

    @Schema(
        description = "미션 상세 설명 (목표 기반 미션에만 존재, 관심사 기반 미션은 null)",
        example = "오늘 회사에서 14:50~15:10 사이, 편의점 가기 전에 물 1컵을 천천히 마시기.",
        nullable = true
    )
    val missionDescription: String? = null,

    @Schema(
        description = "직접 저장된 전체 경로 [대분류, 중분류, 소분류]",
        example = """["체력관리 및 운동", "헬스", "근력 키우기"]"""
    )
    val directFullPath: List<String> = emptyList(),

    @Schema(description = "난이도 (1~5, null이면 난이도 미설정)", example = "3")
    val difficulty: Int? = null,

    @Schema(description = "획득 경험치", example = "10")
    val expEarned: Int = 0,

    @Schema(
        description = "생성 타입 (AI: LLM 생성)",
        example = "AI",
        allowableValues = ["AI"]
    )
    val createdType: String? = null
)

/**
 * ================================
 * 미션 선택 요청/응답 DTO
 * ================================
 */

/**
 * 미션 선택 아이템
 */
@Schema(description = "선택한 미션 정보")
data class SelectedMissionDto(
    @Schema(
        description = "관심사 ID (interest_embeddings 테이블의 ID)",
        example = "97",
        required = true
    )
    val interestId: Long,

    @Schema(
        description = "전체 경로 배열 [대분류, 중분류, 소분류]",
        example = """["직무 관련 역량 개발", "업무 능력 향상", "문서·기획·정리 스킬 향상(PPT·보고서)"]""",
        required = true
    )
    val directFullPath: List<String>,

    @Schema(
        description = "난이도 (1~5, 선택)",
        allowableValues = ["1", "2", "3", "4", "5"],
        example = "1",
        required = false
    )
    val difficulty: Int? = null,

    @Schema(
        description = "mission_embeddings 테이블의 ID",
        example = "123",
        required = true
    )
    val missionId: Long
)

/**
 * 오늘의 미션 추천 요청
 *
 * member_interest 테이블의 ID를 받아서 해당 유저가 저장한 관심사 정보와 프로필을 기반으로 미션 추천
 * - ACTIVE 상태인 기존 미션은 자동으로 제외
 * - Redis에 저장된 이전 추천 미션도 자동으로 제외
 */
@Schema(description = "오늘의 미션 추천 요청")
data class TodayMissionRecommendationRequest(
    @Schema(
        description = "멤버 관심사 ID (챗봇 목표 기반 미션 조회는 0)",
        example = "1",
        required = true
    )
    val memberInterestId: Long
)

/**
 * 오늘의 미션 추천 요청
 *
 * member_interest 테이블의 ID를 받아서 해당 유저가 저장한 관심사 정보와 프로필을 기반으로 미션 추천
 * - ACTIVE 상태인 기존 미션은 자동으로 제외
 * - Redis에 저장된 이전 추천 미션도 자동으로 제외
 */
@Schema(description = "오늘의 미션 추천 요청")
data class TodayMissionRetryRequest(
    @Schema(
        description = "멤버 관심사 ID (챗봇 목표 기반 미션 재추천은 0)",
        example = "1",
        required = true
    )
    val memberInterestId: Long,

    @Schema(
        description = "제외할 member_mission ID 목록 (해당 미션의 난이도는 재추천에서 제외됨)",
        example = "[1, 2, 3]",
        required = false
    )
    val excludeMemberMissionIds: List<Long>? = null
)
