package com.haruUp.character.application.service

import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.character.domain.Level
import com.haruUp.character.domain.MemberCharacter
import com.haruUp.character.domain.dto.LevelDto
import com.haruUp.character.domain.dto.MemberCharacterDto
import com.haruUp.character.infrastructure.MemberCharacterRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.LocalDate
import kotlin.math.max

@Service
class MemberCharacterService(
    private val memberCharacterRepository: MemberCharacterRepository
) {

    /** 초기 캐릭터 정보를 생성한다. */
    @Transactional
    fun createInitial(memberId: Long, characterId: Long, levelId: Long): MemberCharacter {

        val mc = MemberCharacter(
            memberId = memberId,
            characterId = characterId,
            levelId = levelId
        )

        return memberCharacterRepository.save(mc)
    }

    /** 회원이 고른 AI 성격을 저장한다. */
    @Transactional
    fun selectPersonality(memberId: Long, personality: CharacterPersonality): MemberCharacter {
        val mc = getSelectedCharacter(memberId)
            ?: throw IllegalArgumentException("선택된 캐릭터가 없습니다. 캐릭터를 먼저 선택해주세요.")
        mc.changePersonality(personality)
        return memberCharacterRepository.save(mc)
    }

    /** 회원의 현재 선택 캐릭터를 조회한다. */
    @Transactional
    fun getSelectedCharacter(memberId: Long): MemberCharacter? {
        return memberCharacterRepository.findFirstByMemberIdAndDeletedFalseOrderByIdDesc(memberId)
    }

    /** 미션 완료 결과를 캐릭터 경험치/연속일수에 반영한다. */
    @Transactional
    fun applyExpWithResolvedValues(
        mc: MemberCharacter,
        newLevelId: Long,
        totalExp: Int,
        currentExp: Int
    ): MemberCharacter {

        mc.levelId = newLevelId
        mc.totalExp = totalExp
        mc.currentExp = currentExp

        // streak, mission count 등 업데이트
        mc.totalMissions += 1
        mc.completedMissions += 1

        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        if (mc.lastMissionDate == yesterday) {
            mc.currentStreakDays += 1
        } else {
            mc.currentStreakDays = 1
        }

        mc.longestStreakDays = max(mc.longestStreakDays, mc.currentStreakDays)

        mc.lastMissionDate = today

        return memberCharacterRepository.save(mc)
    }
}
