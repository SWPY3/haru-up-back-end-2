package com.haruUp.character.domain.dto

import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.character.domain.MemberCharacter
import java.time.LocalDate


class MemberCharacterDto(

    var id: Long? = null,

    var memberId: Long,
    var characterId: Long,
    var levelId: Long,                 // Level 엔티티 직접 참조 X → ID만 저장

    var totalExp: Int = 0,
    var currentExp: Int = 0,

    var completedMissions: Int = 0,
    var failedMissions: Int = 0,
    var totalMissions: Int = 0,

    var currentStreakDays: Int = 0,
    var longestStreakDays: Int = 0,

    var lastMissionDate: LocalDate? = null,

    /** 큐레이션 꼬리질문 말투에 반영되는 AI 성격 */
    var personality: CharacterPersonality? = null,

) {
    fun toEntity(): MemberCharacter = MemberCharacter(
        id = this.id,
        memberId = this.memberId,
        characterId = this.characterId,
        levelId = this.levelId,
        totalExp = this.totalExp,
        currentExp = this.currentExp,
        completedMissions = this.completedMissions,
        failedMissions = this.failedMissions,
        totalMissions = this.totalMissions,
        currentStreakDays = this.currentStreakDays,
        longestStreakDays = this.longestStreakDays,
        lastMissionDate = this.lastMissionDate

    )
}