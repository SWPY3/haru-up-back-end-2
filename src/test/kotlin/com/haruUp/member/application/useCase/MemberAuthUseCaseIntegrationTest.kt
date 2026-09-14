package com.haruUp.member.application.useCase

import com.haruUp.auth.infrastructure.RefreshTokenRepository
import com.haruUp.global.security.JwtTokenProvider
import com.haruUp.member.domain.dto.MemberDto
import com.haruUp.member.domain.type.LoginType
import com.haruUp.member.infrastructure.MemberRepository
import com.haruUp.member.infrastructure.MemberSettingRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

// DB·Redis 등 외부 인프라가 필요하다. CI의 unitTest 태스크에서는 제외된다.
@Tag("integration")
@SpringBootTest
@Transactional               // 각 테스트마다 롤백
class MemberAuthUseCaseIntegrationTest

@Autowired constructor(
    private val memberAuthUseCase: MemberAuthUseCase,
    private val memberRepository: MemberRepository,
    private val memberSettingRepository: MemberSettingRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val stringRedisTemplate: StringRedisTemplate
) {

    @BeforeEach
    fun cleanUp() {
        // 트랜잭션 롤백으로도 정리되지만, 명시적으로도 한 번 정리
        refreshTokenRepository.deleteAll()
        memberSettingRepository.deleteAll()
        memberRepository.deleteAll()
    }

    // =========================================
    // 1) SNS 로그인 - 신규 회원 플로우
    // =========================================
    @Test
    fun `SNS 로그인 - 신규 회원이면 Member, Setting, RefreshToken 이 생성된다`() {
        // given
        val snsId = "KAKAO_123"
        val email = "kakao@test.com"
        val name = "카카오유저"

        val request = MemberDto(
            id = null,
            email = email,
            password = null,
            name = name,
            loginType = LoginType.KAKAO,   // 실제 enum 값에 맞게 사용
            snsId = snsId
        )

        // when
        val result = memberAuthUseCase.login(request)

        // then - 반환 DTO 검증
        assertNotNull(result.id)
        val memberId = requireNotNull(result.id)
        assertEquals(LoginType.KAKAO, result.loginType)

        // 토큰이 실제로 발급되었는지
        assertNotNull(result.accessToken)
        assertNotNull(result.refreshToken)

        val accessToken = requireNotNull(result.accessToken)
        val refreshToken = requireNotNull(result.refreshToken)

        // JWT 토큰이 실제로 유효한지, memberId가 맞는지 확인
        assertTrue(jwtTokenProvider.validateToken(accessToken))
        assertTrue(jwtTokenProvider.validateToken(refreshToken))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(accessToken))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(refreshToken))

        // DB: Member 가 실제로 저장되었는지
        val savedMember = memberRepository
            .findByLoginTypeAndSnsIdAndDeletedFalse(LoginType.KAKAO, snsId)
        assertNotNull(savedMember)
        assertEquals(memberId, savedMember!!.id)

        // DB: MemberSetting 이 생성되었는지
        val setting = memberSettingRepository.getByMemberId(memberId)
        assertNotNull(setting)

        // DB: RefreshToken 이 저장되었는지
        val refreshEntity = refreshTokenRepository.findByToken(refreshToken)
        assertNotNull(refreshEntity)
        assertEquals(memberId, refreshEntity!!.memberId)
        assertFalse(refreshEntity.revoked)

        // Redis: 세션 키가 생성되었는지 (MemberAuthUseCase 에서 쓰는 prefix에 맞춰야 함)
        val redisKey = "auth:refresh:$refreshToken"
        val redisVal = stringRedisTemplate.opsForValue().get(redisKey)
        assertEquals(memberId.toString(), redisVal)
    }

    // =========================================
    // 2) SNS 로그인 - 동일 snsId로 두 번 로그인해도 회원은 1명만 존재
    // =========================================
    @Test
    fun `SNS 로그인 - 기존 회원이 있으면 새로 생성하지 않고 동일 회원으로 로그인한다`() {
        // given
        val snsId = "KAKAO_DUP"
        val email = "dup@test.com"
        val name = "중복유저"

        val request = MemberDto(
            id = null,
            email = email,
            password = null,
            name = name,
            loginType = LoginType.KAKAO,
            snsId = snsId
        )

        // when 1) 첫 로그인 (자동 회원가입 + 기본 설정)
        val first = memberAuthUseCase.login(request)

        val memberCountAfterFirst = memberRepository.count()
        val settingCountAfterFirst = memberSettingRepository.count()
        val firstMemberId = requireNotNull(first.id)

        // when 2) 같은 snsId로 다시 로그인
        val second = memberAuthUseCase.login(request)

        val memberCountAfterSecond = memberRepository.count()
        val settingCountAfterSecond = memberSettingRepository.count()
        val secondMemberId = requireNotNull(second.id)

        // then
        assertEquals(memberCountAfterFirst, memberCountAfterSecond, "회원 수는 그대로여야 한다")
        assertEquals(1, memberCountAfterSecond, "SNS 로그인으로 생성된 회원은 1명이어야 한다")

        assertEquals(settingCountAfterFirst, settingCountAfterSecond, "기본 설정도 한 번만 생성되어야 한다")
        assertEquals(1, settingCountAfterSecond, "기본 설정 레코드도 1개여야 한다")

        // 두 번 로그인 결과가 같은 memberId 여야 함
        assertEquals(firstMemberId, secondMemberId)

        // 두 번째 로그인에서도 토큰이 정상 발급되는지만 간단히 확인
        assertNotNull(second.accessToken)
        assertNotNull(second.refreshToken)
    }

    // =========================================
    // 3) COMMON 회원가입 + COMMON 로그인 통합 플로우
    //    (signUp → login 까지 실제 DB/JPA 흐름 확인)
    // =========================================
    @Test
    fun `COMMON 회원가입 후 COMMON 로그인까지 전체 플로우가 동작한다`() {
        // given
        val rawPassword = "Password1!"
        val email = "common@test.com"
        val name = "일반회원"

        val signUpRequest = MemberDto(
            id = null,
            email = email,
            password = rawPassword,
            name = name,
            loginType = LoginType.COMMON,
            snsId = null
        )

        // when 1) 회원가입
        val saved = memberAuthUseCase.signUp(signUpRequest)

        // then 1) DB에 회원 + 기본 설정이 실제로 존재
        assertNotNull(saved.id)
        val memberId = requireNotNull(saved.id)

        val memberEntity = memberRepository.findById(memberId).orElse(null)
        assertNotNull(memberEntity)

        val settingEntity = memberSettingRepository.getByMemberId(memberId)
        assertNotNull(settingEntity)

        // when 2) COMMON 로그인
        val loginRequest = MemberDto(
            id = null,
            email = email,
            password = rawPassword,
            name = null,
            loginType = LoginType.COMMON,
            snsId = null
        )

        val loginResult = memberAuthUseCase.login(loginRequest)

        // then 2) 로그인 결과에 토큰이 세팅되고, 같은 회원으로 로그인됨
        assertEquals(memberId, loginResult.id)
        assertNotNull(loginResult.accessToken)
        assertNotNull(loginResult.refreshToken)

        val accessToken = requireNotNull(loginResult.accessToken)
        val refreshToken = requireNotNull(loginResult.refreshToken)

        // 토큰 유효성 및 memberId 일치 확인
        assertTrue(jwtTokenProvider.validateToken(accessToken))
        assertTrue(jwtTokenProvider.validateToken(refreshToken))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(accessToken))
        assertEquals(memberId, jwtTokenProvider.getMemberIdFromToken(refreshToken))

        // RefreshToken 이 DB에 저장되었는지
        val refreshEntity = refreshTokenRepository.findByToken(refreshToken)
        assertNotNull(refreshEntity)
        assertEquals(memberId, refreshEntity!!.memberId)

        // Redis에 세션이 존재하는지
        val redisKey = "auth:refresh:$refreshToken"
        val redisVal = stringRedisTemplate.opsForValue().get(redisKey)
        assertEquals(memberId.toString(), redisVal)
    }
}