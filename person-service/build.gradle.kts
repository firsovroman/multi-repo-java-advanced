plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "person-service: источник доменной истины о пользователе"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val specFile = layout.projectDirectory.file("openapi/person-service.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.hibernate.orm:hibernate-envers")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

openApiValidate {
    inputSpec = specFile
}

openApiGenerate {
    generatorName = "spring"
    library = "spring-boot"
    inputSpec = specFile
    outputDir = generatedDir
    apiPackage = "com.example.person.api"
    modelPackage = "com.example.person.api.model"
    // ApiUtil Ð½ÑÐ¶ÐµÐ½ default-Ð¼ÐµÑÐ¾Ð´Ð°Ð¼ ÑÐ³ÐµÐ½ÐµÑÐ¸ÑÐ¾Ð²Ð°Ð½Ð½ÑÑ *ApiDelegate
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "ApiUtil.java")
    configOptions = mapOf(
        // Контроллер и интерфейс генерируются, рукописный код реализует только *ApiDelegate
        "delegatePattern" to "true",
        "generateJsonSetterNullsAnnotations" to "false",
        "useSpringBoot4" to "true",
        "useJackson3" to "true",
        "useTags" to "true",
        "useBeanValidation" to "true",
        "openApiNullable" to "false",
        "annotationLibrary" to "none",
        "documentationProvider" to "none",
        "generateJsonIncludeAnnotations" to "false",
        "hideGenerationTimestamp" to "true",
        "sourceFolder" to "src/main/java",
    )
}

sourceSets {
    main {
        java.srcDir(generatedDir.map { it.dir("src/main/java") })
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiValidate, tasks.openApiGenerate)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Dockerfile копирует build/libs/person-service.jar
tasks.bootJar {
    archiveFileName = "person-service.jar"
}

tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Покрытие считается по рукописному коду сервиса; сгенерированный код исключён
val coveredClasses = listOf(
    "com/example/person/service/**",
    "com/example/person/mapper/**",
    "com/example/person/error/**",
    "com/example/person/controller/**",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    // classes явно: при -x test зависимость через test пропадает
    dependsOn(tasks.test, tasks.classes)
    classDirectories.setFrom(sourceSets.main.get().output.classesDirs.asFileTree.matching { include(coveredClasses) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
