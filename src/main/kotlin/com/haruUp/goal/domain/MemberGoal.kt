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

    @Column(name = "conversation_summary", nullable = false, columnDefinition = "TEXT")
    val conversationSummary: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

) : BaseEntity() {

    fun deactivate() {
        this.isActive = false
    }
}
