package com.haruUp.global.schema

import jakarta.persistence.Entity
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JPA 엔티티에서 PostgreSQL DDL을 뽑아내는 유틸.
 *
 * Flyway 초기 마이그레이션(V1)을 만들 때 한 번 쓰기 위한 것이며, 평소 테스트에서는 제외된다.
 * DB 연결 없이 Hibernate 메타데이터만으로 동작한다.
 *
 * 실행:
 *   ./gradlew generateSchemaDdl
 *
 * 결과: build/generated-schema.sql
 */
@Tag("schema-gen")
class SchemaDdlGenerator {

    @Test
    fun `엔티티에서 DDL을 생성한다`() {
        val entityClasses = findEntityClasses()
        check(entityClasses.isNotEmpty()) { "엔티티를 찾지 못했습니다. 먼저 컴파일이 필요합니다." }

        val output = File("build/generated-schema.sql").apply {
            parentFile.mkdirs()
            delete()
        }

        // DB 연결 없이 스크립트만 뽑는다. dialect 를 직접 지정해 JDBC 메타데이터 조회를 막는다.
        val registry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
            .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
            // Spring Boot 가 기본으로 적용하는 네이밍 전략. 없으면 테이블명이 MemberCharacter,
            // 컬럼명이 createdAt 처럼 나와 실제 운영 스키마와 어긋난다.
            .applySetting(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
            )
            .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
            .applySetting("jakarta.persistence.schema-generation.scripts.create-target", output.path)
            .applySetting("hibernate.hbm2ddl.delimiter", ";")
            .applySetting("hibernate.format_sql", "true")
            .build()

        try {
            val sources = MetadataSources(registry)
            entityClasses.forEach { sources.addAnnotatedClass(it) }
            sources.buildMetadata().buildSessionFactory().close()
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }

        println("엔티티 ${entityClasses.size}개에서 DDL 생성 완료: ${output.absolutePath}")
    }

    /** 컴파일된 클래스 디렉터리를 뒤져 @Entity 클래스를 모은다. */
    private fun findEntityClasses(): List<Class<*>> {
        val roots = listOf(File("build/classes/kotlin/main"), File("build/classes/java/main"))
            .filter { it.isDirectory }

        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
                .mapNotNull { file ->
                    val className = file.relativeTo(root).path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                    runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()
                }
                .filter { it.isAnnotationPresent(Entity::class.java) }
                .toList()
        }.distinct().sortedBy { it.name }
    }
}
