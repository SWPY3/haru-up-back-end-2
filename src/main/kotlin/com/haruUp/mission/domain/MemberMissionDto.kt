package com.haruUp.mission.domain

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

@Schema(description = "멤버 미션 정보")
data class MemberMissionDto (
    @Schema(description = "멤버 미션 ID", example = "1")
    val id: Long? = null,

    @Schema(description = "멤버 ID", example = "1")
    val memberId: Long,

    @Schema(description = "멤버 관심사 ID", example = "1")
    val memberInterestId: Long,

    @Schema(description = "미션 상태", example = "ACTIVE")
    val missionStatus: MissionStatus = MissionStatus.ACTIVE,

    @Schema(description = "획득 경험치", example = "10")
    val expEarned: Int,

    @Schema(description = "목표 날짜", example = "2025-01-01")
    val targetDate: LocalDate = LocalDate.now(),

    @Schema(description = "미션 내용", example = "LC 파트3,4 핵심 패턴 메모")
    val missionContent: String? = null,

    @Schema(description = "미션 설명 (구체적인 실행 방법)", example = "유튜브 '토익 독학 길잡이' 채널의 '[LC] 파트3,4 빈출 질문 유형 완전정복' 영상 시청 후 핵심 질문 패턴 5가지 메모하기")
    val missionDescription: String? = null,

    @Schema(description = "미션 난이도 (1~3)", example = "1")
    val difficulty: Int? = null,

    @Schema(description = "관심사 전체 경로 (interest_embeddings.full_path)", example = "[\"체력관리 및 운동\", \"헬스\", \"근력 키우기\"]")
    val fullPath: List<String>? = null,

    @Schema(description = "직접 저장된 전체 경로 (member_interest.direct_full_path)", example = "[\"체력관리 및 운동\", \"헬스\", \"근력 키우기\"]")
    val directFullPath: List<String>? = null
)

data class AiMissionResult(
    val missionId: Long,
    val score: Double,
    val reason: String
)

data class MissionCandidateDto(
    val memberMissionId: Long,
    val missionStatus: MissionStatus,
    val content: String,
    val directFullPath: List<String>,  // 전체 경로 배열 ["대분류", "중분류", "소분류"]
    val difficulty: Int?,
    val expEarned: Int,
    val targetDate: LocalDate,
    val missionDescription: String? = null
)

data class MissionRecommendResult(
    val missions: List<MissionCandidateDto>,
    val retryCount: Long? = 0
)

/**
 * 개별 미션 상태 변경 항목
 */
data class MissionStatusChangeItem(
    @Schema(
        description = "member_mission ID",
        example = "1",
        required = true
    )
    val memberMissionId: Long,

    @Schema(
        description = "변경할 미션 상태",
        example = "COMPLETED",
        required = false
    )
    val missionStatus: MissionStatus? = null,

    @Schema(
        description = "soft delete 여부 (true일 경우 상태 변경 없이 바로 삭제)",
        example = "false",
        required = false
    )
    val deleted: Boolean? = null
)

/**
 * 미션 상태 변경 벌크 요청 DTO
 */
data class MissionStatusChangeRequest(
    val missions: List<MissionStatusChangeItem>
)

/**
 * 미션 선택 요청
 */
@Schema(description = "미션 선택 요청")
data class MemberMissionSelectionRequest(
    @Schema(
        description = "선택할 member_mission ID 목록",
        example = "[1, 2, 3]",
        required = true
    )
    val memberMissionIds: List<Long>
)

/**
 * 일별 미션 완료 상태
 */
@Schema(description = "일별 미션 완료 상태")
data class DailyCompletionStatus(
    @Schema(description = "날짜", example = "2025-01-01")
    val targetDate: LocalDate,

    @Schema(description = "해당 날짜에 미션 완료 여부", example = "true")
    val isCompleted: Boolean
)

/**
 * 미션 경험치 계산 유틸리티
 */
object MissionExpCalculator {
    /**
     * 난이도에 따른 경험치 계산
     * - 난이도 1: 50
     * - 난이도 2: 100
     * - 난이도 3: 150
     * - 난이도 4: 200
     * - 난이도 5: 250
     */
    fun calculateByDifficulty(difficulty: Int?): Int {
        return when (difficulty) {
            1 -> 50
            2 -> 100
            3 -> 150
            4 -> 200
            5 -> 250
            else -> 0
        }
    }
}