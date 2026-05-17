package com.haruUp.mission.domain

import com.haruUp.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import lombok.NoArgsConstructor
import java.time.LocalDate

@Entity
@NoArgsConstructor
@Table(
    name = "member_mission",
    indexes = [
        Index(name = "idx_member_mission_member_id", columnList = "member_id"),
        Index(name = "idx_member_mission_created_at", columnList = "created_at"),
        Index(name = "idx_member_mission_difficulty", columnList = "difficulty")
    ]
)
class MemberMissionEntity (
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "member_interest_id", nullable = false)
    val memberInterestId: Long,

    @Column(name = "mission_content", nullable = false, columnDefinition = "TEXT")
    val missionContent: String,

    @Column(name = "mission_description", columnDefinition = "TEXT")
    val missionDescription: String? = null,

    @Column(name = "difficulty")
    val difficulty: Int? = null,

    @Column(name = "label_name", length = 100)
    var labelName: String? = null,

    /**
     * 임베딩 벡터 (pgvector type)
     * 관심사 + 미션 내용 기반 1024차원 임베딩
     * "[0.1, 0.2, ...]" 형태의 문자열로 저장
     *
     * JPA에서 직접 INSERT/UPDATE 불가 (String → vector 변환 안됨)
     * Native Query로만 업데이트 (MemberMissionRepository.updateLabelNameAndEmbedding)
     */
    @Column(name = "embedding", columnDefinition = "vector(1024)", insertable = false, updatable = false)
    var embedding: String? = null,

    @Enumerated(EnumType.STRING)
    var missionStatus : MissionStatus = MissionStatus.READY,

    var expEarned : Int,

    @Column(name = "target_date", nullable = false)
    var targetDate: LocalDate = LocalDate.now(),

    @Column(name = "is_selected", nullable = false)
    var isSelected: Boolean = false

) : BaseEntity() {

    companion object {
        /**
         * Float 리스트를 pgvector 문자열로 변환
         */
        fun vectorToString(vector: List<Float>): String {
            return vector.joinToString(separator = ",", prefix = "[", postfix = "]")
        }
    }

    fun toDto(
        fullPath: List<String>? = null,
        directFullPath: List<String>? = null
    ): MemberMissionDto = MemberMissionDto(
        id = this.id,
        memberId = this.memberId,
        memberInterestId = this.memberInterestId,
        expEarned = this.expEarned,
        missionStatus = this.missionStatus,
        targetDate = this.targetDate,
        missionContent = this.missionContent,
        missionDescription = this.missionDescription,
        difficulty = this.difficulty,
        fullPath = fullPath,
        directFullPath = directFullPath
    )

}