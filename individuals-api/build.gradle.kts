plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "individuals-api: внешний оркестратор регистрации и аутентификации"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // person-client и другие артефакты платформы. Сейчас — локальный Maven-репозиторий,
    // Nexus подключается конфигурацией: -PnexusUrl=... или NEXUS_URL
    val nexusUrl = providers.gradleProperty("nexusUrl").orElse(providers.environmentVariable("NEXUS_URL"))
    if (nexusUrl.isPresent) {
        maven {
            name = "nexus"
            url = uri(nexusUrl.get())
            isAllowInsecureProtocol = true
            credentials {
                username = providers.gradleProperty("nexusUsername").orElse(providers.environmentVariable("NEXUS_USERNAME")).orNull
                password = providers.gradleProperty("nexusPassword").orElse(providers.environmentVariable("NEXUS_PASSWORD")).orNull
            }
        }
    }
    mavenLocal {
        content {
            includeGroup("com.example")
        }
    }
}

val specFile = layout.projectDirectory.file("openapi/individuals-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

dependencies {
    implementation(libs.person.client)

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-json")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation(libs.testcontainers.keycloak)
    testImplementation(libs.wiremock.standalone)
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
    apiPackage = "com.example.individuals.api"
    modelPackage = "com.example.individuals.api.model"
    globalProperties = mapOf("apis" to "", "models" to "")
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "skipDefaultInterface" to "true",
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

// Dockerfile копирует build/libs/individuals-api.jar
tasks.bootJar {
    archiveFileName = "individuals-api.jar"
}

tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Покрытие считается по ключевым классам оркестрации и шлюзов; сгенерированный код исключён
val coveredClasses = listOf(
    "com/example/individuals/service/**",
    "com/example/individuals/gateway/**",
    "com/example/individuals/keycloak/**",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { include(coveredClasses) } }))
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
