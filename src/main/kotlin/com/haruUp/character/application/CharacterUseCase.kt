package com.haruUp.character.application

import com.haruUp.character.application.service.CharacterService
import com.haruUp.character.application.service.LevelService
import com.haruUp.character.application.service.MemberCharacterService
import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.character.domain.dto.CharacterDto
import com.haruUp.character.domain.dto.MemberCharacterDto
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class CharacterUseCase(
    private val memberCharacterService: MemberCharacterService,
    private val levelService: LevelService,
    private val characterService: CharacterService
) {

    /** 사용 가능한 캐릭터 목록을 조회한다. */
    @Transactional
    fun characterList(): List<CharacterDto> =
        characterService.getCharacterList().map { it.toDto() }

    /** 회원의 초기 캐릭터/레벨 정보를 생성한다. */
    @Transactional
    fun createInitialCharacter(memberId: Long, characterId: Long): MemberCharacterDto {

        // 1. 캐릭터 존재 여부 확인
        characterService.validateExists(characterId)

        // 2. 초기 레벨 조회
        val levelId = levelService.getInitialLevelId()

        // 3. MemberCharacter 생성
        val mc = memberCharacterService.createInitial(memberId, characterId, levelId)

        return mc.toDto()
    }

    /** 회원이 고른 AI 성격을 저장한다. */
    @Transactional
    fun selectPersonality(memberId: Long, personality: CharacterPersonality): MemberCharacterDto =
        memberCharacterService.selectPersonality(memberId, personality).toDto()

    /**
     * 큐레이션 꼬리질문에 쓸 캐릭터 정보를 조회한다.
     *
     * 캐릭터를 아직 고르지 않았거나 성격을 고르지 않았어도 큐레이션은 진행되어야 하므로
     * 기본값으로 채워서 반환한다.
     */
    @Transactional
    fun getCurationProfile(memberId: Long): CurationCharacterProfile {
        val mc = memberCharacterService.getSelectedCharacter(memberId)
            ?: return CurationCharacterProfile(DEFAULT_CHARACTER_NAME, CharacterPersonality.DEFAULT)

        val name = characterService.getById(mc.characterId)?.name?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CHARACTER_NAME

        return CurationCharacterProfile(name, mc.personality ?: CharacterPersonality.DEFAULT)
    }

    companion object {
        /** 캐릭터를 아직 고르지 않았거나 이름이 비어 있을 때 쓰는 이름 */
        const val DEFAULT_CHARACTER_NAME = "하루"
    }
}

/**
 * 큐레이션에서 사용하는 캐릭터 정보
 *
 * @param name 사용자에게 보여줄 캐릭터 이름
 * @param personality 꼬리질문 말투에 반영할 성격
 */
data class CurationCharacterProfile(
    val name: String,
    val personality: CharacterPersonality
)
