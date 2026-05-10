package com.haruUp.goal.repository

import com.haruUp.goal.domain.MemberGoal
import org.springframework.data.jpa.repository.JpaRepository

interface MemberGoalRepository : JpaRepository<MemberGoal, Long> {

    fun findByMemberIdAndIsActiveTrue(memberId: Long): MemberGoal?

    fun findAllByIsActiveTrue(): List<MemberGoal>
}
