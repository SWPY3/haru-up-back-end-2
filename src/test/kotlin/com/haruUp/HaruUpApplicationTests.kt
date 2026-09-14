package com.haruUp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest

// DB·Redis 등 외부 인프라가 필요하다. CI의 unitTest 태스크에서는 제외된다.
@Tag("integration")
@SpringBootTest
class HaruUpApplicationTests {

    @Test
    fun contextLoads() {
    }

}
