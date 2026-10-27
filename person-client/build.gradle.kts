plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.openapi.generator)
}

group = "com.example"
version = libs.versions.person.client.get()
description = "HTTP-клиент и DTO person-service, сгенерированные из OpenAPI"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

val specFile = layout.projectDirectory.file("../person-service/openapi/person-service.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

dependencies {
    api(platform(libs.spring.boot.bom))
    api("org.springframework:spring-web")
    api("org.springframework:spring-context")
    api("io.projectreactor:reactor-core")
    api("jakarta.validation:jakarta.validation-api")
    api("jakarta.annotation:jakarta.annotation-api")
    api("com.fasterxml.jackson.core:jackson-annotations")
}

openApiValidate {
    inputSpec = specFile
}

openApiGenerate {
    generatorName = "spring"
    library = "spring-http-interface"
    inputSpec = specFile
    outputDir = generatedDir
    apiPackage = "com.example.person.client.api"
    modelPackage = "com.example.person.client.model"
    // Только интерфейсы HTTP Service Client и модели: конфигурацию прокси делает потребитель
    globalProperties = mapOf("apis" to "", "models" to "")
    configOptions = mapOf(
        "useSpringBoot4" to "true",
        // Методы возвращают Mono/Flux: клиент рассчитан на WebClient
        "reactive" to "true",
        "useJackson3" to "true",
        "useTags" to "true",
        "useResponseEntity" to "false",
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

tasks.named("sourcesJar") {
    dependsOn(tasks.openApiGenerate)
}

// Контракт публикуется внутри артефакта: openapi/person-service.yaml
tasks.processResources {
    from(specFile) {
        into("openapi")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        // Nexus подключается только конфигурацией: -PnexusUrl=... или NEXUS_URL (+ логин/пароль)
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
    }
}
