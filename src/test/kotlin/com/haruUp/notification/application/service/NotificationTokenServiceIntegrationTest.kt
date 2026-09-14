package com.haruUp.notification.application.service

import com.haruUp.member.domain.MemberSetting
import com.haruUp.member.domain.type.ThemeType
import com.haruUp.member.infrastructure.MemberSettingRepository
import com.haruUp.notification.domain.NotificationDeviceToken
import com.haruUp.notification.domain.PushPlatform
import com.haruUp.notification.infrastructure.NotificationDeviceTokenRepository
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

// DB·Redis 등 외부 인프라가 필요하다. CI의 unitTest 태스크에서는 제외된다.
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Transactional
/**
 * Notification 토큰 등록/갱신 흐름이 DB와 QueryDSL 커스텀 리포지토리에서
 * 의도한 대로 동작하는지 검증한다.
 */
class NotificationTokenServiceIntegrationTest {

    @Autowired
    lateinit var notificationRepository: NotificationDeviceTokenRepository

    @Autowired
    lateinit var memberSettingRepository: MemberSettingRepository

    @Autowired
    lateinit var notificationTokenService: NotificationTokenService

    // 목적: 기존 토큰이 있을 때 registerToken이 insert가 아니라 update 경로를 타는지 확인
    @Test
    fun `기존 토큰이 있으면 DB에서 token과 platform이 갱신된다`() {
        // given
        val memberId = 92001L
        enablePush(memberId)
        notificationRepository.save(
            NotificationDeviceToken(
                memberId = memberId,
                deviceId = "device123",
                platform = PushPlatform.IOS,
                token = "old_token"
            )
        )

        // when
        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "device123",
            platform = PushPlatform.ANDROID,
            token = "new_token"
        )

        // then
        val result = notificationRepository.findByMemberIdAndDeviceId(memberId, "device123")
        assertEquals("new_token", result?.token)
        assertEquals(PushPlatform.ANDROID, result?.platform)
    }

    // 목적: 기존 토큰이 없을 때 registerToken이 신규 row를 생성하는지 확인
    @Test
    fun `기존 토큰이 없으면 DB에 새 row가 저장된다`() {
        // given
        val memberId = 92002L
        enablePush(memberId)

        notificationTokenService.registerToken(
            memberId = memberId,
            deviceId = "device456",
            platform = PushPlatform.IOS,
            token = "fresh_token"
        )

        // when
        val result = notificationRepository.findByMemberIdAndDeviceId(memberId, "device456")

        // then
        assertNotNull(result)
        assertEquals("fresh_token", result?.token)
        assertEquals(PushPlatform.IOS, result?.platform)
    }

    // 목적: 커스텀 QueryDSL 메서드(updateToken)가 실제 DB 값을 변경하는지 직접 확인
    @Test
    fun `updateToken 호출 시 토큰이 갱신된다`() {
        // given
        val memberId = 92003L
        enablePush(memberId)
        notificationRepository.save(
            NotificationDeviceToken(
                memberId = memberId,
                deviceId = "device789",
                platform = PushPlatform.IOS,
                token = "before_token"
            )
        )

        // when
        notificationRepository.updateToken(
            memberId = memberId,
            token = "after_token",
            deviceId = "device789"
        )

        // then
        val result = notificationRepository.findByMemberIdAndDeviceId(memberId, "device789")
        assertNotNull(result)
        assertEquals("after_token", result?.token)
    }

    private fun enablePush(memberId: Long) {
        memberSettingRepository.save(
            MemberSetting(
                memberId = memberId,
                pushEnabled = true,
                emailEnabled = true,
                marketingConsent = true,
                theme = ThemeType.DARK
            )
        )
    }
}
