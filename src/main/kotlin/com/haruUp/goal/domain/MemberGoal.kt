package com.haruUp.goal.domain

import com.haruUp.global.common.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "member_goal",
    indexes = [
        Index(name = "idx_member_goal_member_id", columnList = "member_id"),
        Index(name = "idx_member_goal_is_active", columnList = "is_active")
    ]
)
class MemberGoal(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "goal_text", nullable = false, columnDefinition = "TEXT")
    val goalText: String,

    // 미션 생성 프롬프트에 넣는 상세 요약 (사용자에게 노출하지 않음)
    @Column(name = "conversation_summary", nullable = false, columnDefinition = "TEXT")
    val conversationSummary: String,

    // 사용자 화면에 그대로 보여주는 짧은 요약. 이 컬럼 추가 전에 만들어진 목표는 null이다.
    @Column(name = "user_summary", columnDefinition = "TEXT")
    val userSummary: String? = null,

    // 원본 Q&A 대화 전체 (JSON 형태로 저장, 미션 생성 시 활용)
    @Column(name = "conversation_raw", columnDefinition = "TEXT")
    val conversationRaw: String? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

) : BaseEntity() {

    fun deactivate() {
        this.isActive = false
    }
}
