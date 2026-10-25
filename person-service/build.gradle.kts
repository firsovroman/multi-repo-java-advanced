// person-service: в модуле 1 фиксируются только контракт и миграции Flyway.
// Полноценная реализация сервиса — предмет модуля 2.
plugins {
    java
    alias(libs.plugins.openapi.generator)
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

openApiValidate {
    inputSpec = layout.projectDirectory.file("openapi/person-service.yaml")
}

tasks.check {
    dependsOn(tasks.openApiValidate)
}

tasks.test {
    useJUnitPlatform()
}
