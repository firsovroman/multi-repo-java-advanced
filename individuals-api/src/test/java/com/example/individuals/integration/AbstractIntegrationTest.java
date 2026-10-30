package com.example.individuals.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Общая инфраструктура интеграционных тестов: реальный Keycloak с realm-export.json
 * и WireMock на месте person-service. Контейнер и заглушка общие для всех тестов (singleton).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AbstractIntegrationTest.TracingTestConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final String REALM = "payment-platform";

    protected static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8.1")
            .withRealmImportFile("/realm/realm-export.json");

    protected static final WireMockServer PERSON_SERVICE = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));

    static {
        KEYCLOAK.start();
        PERSON_SERVICE.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String keycloakUrl = KEYCLOAK.getAuthServerUrl().replaceAll("/$", "");
        registry.add("app.keycloak.server-url", () -> keycloakUrl);
        registry.add("app.keycloak.issuer-uri", () -> keycloakUrl + "/realms/" + REALM);
        registry.add("spring.http.serviceclient.person-service.base-url", PERSON_SERVICE::baseUrl);
        registry.add("management.tracing.export.otlp.enabled", () -> "false");
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "50ms");
    }

    @LocalServerPort
    protected int port;

    protected WebClient http;

    @BeforeEach
    void setUpClient() {
        http = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                // ошибки проверяются по статусу и телу в самих тестах
                .defaultStatusHandler(HttpStatusCode::isError, response -> Mono.empty())
                .build();
    }

    /**
     * Заглушка person-service: регистрация email возвращает новый userUid.
     */
    protected static UUID stubPersonRegistration(String email) {
        UUID userUid = UUID.randomUUID();
        PERSON_SERVICE.stubFor(post(urlEqualTo("/api/v1/persons/registration"))
                .withRequestBody(equalToJson("{\"email\":\"" + email + "\"}", true, true))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"userId\":\"" + userUid + "\"}")));
        return userUid;
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracingTestConfig {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }
}
