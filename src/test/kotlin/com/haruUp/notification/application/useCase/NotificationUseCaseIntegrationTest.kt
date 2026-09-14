package com.haruUp.notification.application.useCase

import com.google.firebase.messaging.Message
import com.haruUp.notification.application.PushClient
import com.haruUp.notification.domain.NotificationDeviceToken
import com.haruUp.notification.domain.PushPlatform
import com.haruUp.notification.infrastructure.NotificationDeviceTokenRepository
import com.haruUp.notification.infrastructure.PushClientApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// DB·Redis 등 외부 인프라가 필요하다. CI의 unitTest 태스크에서는 제외된다.
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationUseCaseIntegrationTest {

    @Autowired
    lateinit var notificationUseCase: NotificationUseCase

    @Autowired
    lateinit var notificationRepository: NotificationDeviceTokenRepository

    // 🔥 외부 시스템은 반드시 MockBean
    @MockBean
    lateinit var pushClient: PushClient

    @MockBean
    lateinit var pushClientApplication: PushClientApplication

    @Test
    fun `회원에게 등록된 토큰 개수만큼 푸시 전송이 수행된다`() {
        // given: 실제 DB에 토큰 2개 저장
        notificationRepository.save(
            NotificationDeviceToken(
                memberId = 1L,
                deviceId = "device-1",
                platform = PushPlatform.IOS,
                token = "token-1"
            )
        )

        notificationRepository.save(
            NotificationDeviceToken(
                memberId = 1L,
                deviceId = "device-2",
                platform = PushPlatform.IOS,
                token = "token-2"
            )
        )

        // ⚠️ Firebase Message는 반드시 token/topic/condition 중 하나 필요
        val mockMessage = Message.builder()
            .setToken("test-fcm-token")
            .build()

        whenever(pushClient.createMessage(any(), any(), any()))
            .thenReturn(mockMessage)

        // when
        notificationUseCase.sendToMember(
            memberId = 1L,
            title = "테스트 제목",
            body = "테스트 내용"
        )

        // then
        // 토큰 2개 → 메시지 생성 2번
        verify(pushClient, times(2))
            .createMessage(any(), any(), any())

        // 토큰 2개 → 전송 2번
        verify(pushClientApplication, times(2))
            .send(any(), any())
    }
}