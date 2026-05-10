package com.haruUp.global.config

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.springframework.context.annotation.Configuration
import javax.annotation.PostConstruct

@Configuration
class FirebaseConfig {

    @PostConstruct
    fun init(){
        if(FirebaseApp.getApps().isNotEmpty()) return

        // 환경변수에서 Firebase JSON 읽기 (Railway 배포용)
        val firebaseJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")

        val credentials = if (!firebaseJson.isNullOrBlank()) {
            GoogleCredentials.fromStream(firebaseJson.byteInputStream())
        } else {
            // 로컬 환경: 파일에서 읽기
            val serviceAccount = this::class.java.getResourceAsStream("/firebase-service-account.json")
                ?: throw IllegalStateException("Firebase service account file not found")
            GoogleCredentials.fromStream(serviceAccount)
        }

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .build()

        FirebaseApp.initializeApp(options)
    }
}