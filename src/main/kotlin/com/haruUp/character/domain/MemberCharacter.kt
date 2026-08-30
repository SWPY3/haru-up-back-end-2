package com.haruUp.character.domain

import com.haruUp.character.domain.dto.MemberCharacterDto
import com.haruUp.global.common.BaseEntity
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import lombok.NoArgsConstructor
import java.time.LocalDate

@Entity
@NoArgsConstructor
class MemberCharacter (
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    // 큐레이션 꼬리질문의 말투에만 반영되는 AI 성격. 아직 고르지 않았으면 null이다.
    @Enumerated(EnumType.STRING)
    var personality: CharacterPersonality? = null,

) : BaseEntity() {

    fun changePersonality(personality: CharacterPersonality) {
        this.personality = personality
    }

    fun toDto() : MemberCharacterDto = MemberCharacterDto(
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
        lastMissionDate = this.lastMissionDate,
        personality = this.personality
    )
}