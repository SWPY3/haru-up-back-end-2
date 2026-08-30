package com.haruUp.character.controller

import com.haruUp.character.application.CharacterUseCase
import com.haruUp.character.domain.CharacterPersonality
import com.haruUp.character.domain.dto.CharacterDto
import com.haruUp.global.common.ApiResponse
import com.haruUp.global.security.MemberPrincipal
import io.lettuce.core.json.JsonObject
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/character")
class CharacterController(
    private val characterUseCase: CharacterUseCase
) {

    // 캐릭터 조회
    @GetMapping("/list")
    fun getCharacterList(
        @AuthenticationPrincipal principal: MemberPrincipal
    ): List<CharacterDto> {
        return characterUseCase.characterList()
    }

    // member 캐릭터 선택
    @PostMapping("/selected")
    fun selectedCharacter(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @RequestBody request: SelectCharacterRequest   //
    ): ApiResponse<String> {

        characterUseCase.createInitialCharacter(
            principal.id,
            request.characterId
        )
        return ApiResponse.success("OK")
    }


    // 선택 가능한 AI 성격 목록 (캐릭터 성격 선택 화면)
    @GetMapping("/personality/list")
    fun getPersonalityList(
        @AuthenticationPrincipal principal: MemberPrincipal
    ): ApiResponse<List<PersonalityResponse>> {
        val personalities = CharacterPersonality.entries.map {
            PersonalityResponse(code = it.name, label = it.label)
        }
        return ApiResponse.success(personalities)
    }

    // AI 성격 선택 (캐릭터 선택 이후, 챗봇 시작 전)
    @PostMapping("/personality")
    fun selectPersonality(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @RequestBody request: SelectPersonalityRequest
    ): ApiResponse<String> {
        characterUseCase.selectPersonality(principal.id, request.personality)
        return ApiResponse.success("OK")
    }

    data class SelectCharacterRequest(
        val characterId: Long
    )

    data class SelectPersonalityRequest(
        val personality: CharacterPersonality
    )

    data class PersonalityResponse(
        /** 선택 시 그대로 돌려보낼 값 */
        val code: String,
        /** 사용자에게 보여줄 문구 */
        val label: String
    )
}
