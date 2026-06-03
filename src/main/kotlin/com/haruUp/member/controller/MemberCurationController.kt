package com.haruUp.member.controller

import com.haruUp.character.application.service.LevelService
import com.haruUp.character.application.service.MemberCharacterService
import com.haruUp.global.common.ApiResponse
import com.haruUp.global.security.MemberPrincipal
import com.haruUp.interest.dto.InterestsDto
import com.haruUp.member.application.service.MemberProfileService
import com.haruUp.member.application.useCase.CurationResult
import com.haruUp.member.application.useCase.MemberCurationUseCase
import com.haruUp.member.domain.dto.MemberProfileDto
import com.haruUp.member.domain.type.MemberGender
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.coroutines.runBlocking
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.LocalDate
import java.util.concurrent.Executor

@RestController
@RequestMapping("/api/member/curation")
class MemberCurationController(
    private val memberCurationUseCase: MemberCurationUseCase,
    private val memberCharacterService: MemberCharacterService,
    private val memberProfileService: MemberProfileService,
    private val levelService: LevelService,
    private val sseExecutor: Executor,
) {

    /**
     * 챗봇 온보딩 전용 초기 설정 API
     * 캐릭터 생성 + 닉네임 저장만 처리 (관심사/직업 불필요)
     */
    @PostMapping("/chatbot-setup")
    @Transactional
    @Operation(
        summary = "챗봇 온보딩 초기 설정",
        description = "챗봇 플로우에서 캐릭터 생성과 닉네임만 등록합니다."
    )
    fun chatbotSetup(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @RequestBody request: ChatbotSetupRequest
    ): ApiResponse<String> {
        // 캐릭터가 없는 경우에만 생성 (중복 방지)
        if (memberCharacterService.getSelectedCharacter(principal.id) == null) {
            val levelId = levelService.getInitialLevelId()
            memberCharacterService.createInitial(principal.id, request.characterId, levelId)
        }

        // 닉네임만 저장
        val profileDto = MemberProfileDto().apply {
            memberId = principal.id
            nickname = request.nickname
        }
        memberProfileService.curationUpdateProfile(principal.id, profileDto)

        return ApiResponse.success("OK")
    }


    @PostMapping("/initial", produces = ["text/event-stream"])
    @Operation(
        summary = "초기 회원 큐레이션 실행",
        description = """
        회원 가입 직후 실행되는 초기 큐레이션 API입니다.
        - 캐릭터 생성
        - 프로필 설정
        - 관심사 저장
        - 미션 추천
        SSE 방식으로 진행 로그를 스트리밍합니다.
    """
    )
    fun runInitialCuration(
        @AuthenticationPrincipal principal: MemberPrincipal,
        @RequestBody curationDto: CurationRequest
    ): SseEmitter {

        val emitter = SseEmitter(0L)

        sseExecutor.execute {
            runBlocking {
                try {
                    emitter.send(SseEmitter.event().name("connected").data("큐레이션 시작"))

                    val birthDt = LocalDate
                        .parse(curationDto.birthDt)
                        .atStartOfDay()

                    val profileDto = MemberProfileDto().apply {
                        memberId = principal.id
                        nickname = curationDto.nickname
                        this.birthDt = birthDt
                        gender = curationDto.gender
                        jobId = curationDto.jobId
                        jobDetailId = curationDto.jobDetailId
                    }

                    val result = memberCurationUseCase.runInitialCuration(
                        characterId = curationDto.characterId,
                        memberProfileDto = profileDto,
                        interests = curationDto.interests
                    ) {
                        emitter.send(
                            SseEmitter.event()
                                .name("curation-log")
                                .data(it)
                        )
                    }

                    emitter.send(SseEmitter.event().name("done").data(result))
                    emitter.complete()

                } catch (e: Exception) {
                    val errorMessage = e.message ?: "알 수 없는 오류가 발생했습니다."
                    emitter.send(SseEmitter.event().name("error").data(mapOf("errorMessage" to errorMessage)))
                    emitter.complete()
                }
            }
        }

        return emitter
    }


    @Schema(description = "초기 회원 큐레이션 요청")
    data class CurationRequest(

        @Schema(
            description = "선택한 캐릭터 ID",
            example = "1"
        )
        val characterId: Long,

        @Schema(
            description = "닉네임",
            example = "테스트"
        )
        val nickname: String,

        @Schema(
            description = "생년월일 (yyyy-MM-dd)",
            example = "1995-07-30"
        )
        val birthDt: String,

        @Schema(
            description = "성별",
            example = "MALE",
            allowableValues = ["MALE", "FEMALE"]
        )
        val gender: MemberGender,

        @Schema(
            description = "직업 ID",
            example = "1",
            nullable = true
        )
        val jobId: Long?,

        @Schema(
            description = "직업 상세 ID",
            example = "1",
            nullable = true
        )
        val jobDetailId: Long?,

        @Schema(description = "관심사 경로 목록")
        val interests: List<InterestsDto>
    )

    @Schema(description = "챗봇 온보딩 초기 설정 요청")
    data class ChatbotSetupRequest(
        @Schema(description = "선택한 캐릭터 ID", example = "1")
        val characterId: Long,
        @Schema(description = "닉네임", example = "하루")
        val nickname: String
    )
}
