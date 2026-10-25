package com.example.individuals.integration;

import com.example.individuals.api.model.ErrorResponse;
import com.example.individuals.api.model.RegistrationRequest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Наблюдаемость: IT-OBS-001 (Prometheus), IT-OBS-002 (трассы и передача trace context), IT-OBS-003 (корреляция журналов).
 */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private InMemorySpanExporter spanExporter;

    @Test
    void prometheusEndpointExposesApplicationMetrics() {
        // IT-OBS-001
        String email = uniqueEmail();
        stubPersonRegistration(email);
        register(email);

        ResponseEntity<String> response = http.get().uri("/actuator/prometheus").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("auth_registration_total")
                .contains("auth_registration_success_total")
                .contains("auth_registration_failure_total")
                .contains("auth_login_total")
                .contains("auth_login_failure_total")
                .contains("auth_refresh_total")
                .contains("external_keycloak_requests_seconds_count")
                .contains("external_person_service_requests_seconds_count");
    }

    @Test
    void healthEndpointIsUp() {
        ResponseEntity<String> response = http.get().uri("/actuator/health").retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void requestCreatesTraceAndPropagatesContextToPersonService() {
        // IT-OBS-002
        String traceId = registerWithConflict(uniqueEmail());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<SpanData> spans = spanExporter.getFinishedSpanItems().stream()
                    .filter(span -> span.getTraceId().equals(traceId))
                    .toList();
            assertThat(spans).extracting(SpanData::getName)
                    .anyMatch(name -> name.contains("/api/v1/auth/registration"));
            assertThat(spans).hasSizeGreaterThan(1);
        });

        List<LoggedRequest> personRequests = PERSON_SERVICE.findAll(postRequestedFor(urlEqualTo("/api/v1/persons/registration")));
        assertThat(personRequests).anyMatch(request -> request.containsHeader("traceparent")
                && request.getHeader("traceparent").contains(traceId));
    }

    @Test
    void logsContainTraceAndSpanIds(CapturedOutput output) {
        // IT-OBS-003
        String traceId = registerWithConflict(uniqueEmail());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut()).contains(traceId));

        JsonNode requestLog = output.getOut().lines()
                .filter(line -> line.startsWith("{") && line.contains(traceId))
                .map(JSON::readTree)
                .filter(json -> json.path("http").path("status").asInt() == 409)
                .findFirst()
                .orElseThrow();
        assertThat(requestLog.path("service").path("name").asString()).isEqualTo("individuals-api");
        assertThat(requestLog.path("traceId").asString()).isEqualTo(traceId);
        assertThat(requestLog.path("spanId").asString()).matches("[0-9a-f]{16}");
        assertThat(requestLog.path("http").path("path").asString()).isEqualTo("/api/v1/auth/registration");
        assertThat(requestLog.path("http").path("method").asString()).isEqualTo("POST");
        assertThat(requestLog.path("errorCode").asString()).isEqualTo("CONFLICT");
    }

    /**
     * Регистрация, на которую person-service отвечает 409: ответ содержит traceId запроса.
     */
    private String registerWithConflict(String email) {
        PERSON_SERVICE.stubFor(post(urlEqualTo("/api/v1/persons/registration"))
                .withRequestBody(containing(email))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":409,\"error\":\"CONFLICT\"}")));

        ResponseEntity<ErrorResponse> response = http.post().uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RegistrationRequest(email, "Str@ngPass!", "Str@ngPass!", "Ivan", "Ivanov"))
                .retrieve()
                .toEntity(ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        String traceId = response.getBody().getTraceId();
        assertThat(traceId).matches("[0-9a-f]{32}");
        return traceId;
    }

    private void register(String email) {
        ResponseEntity<String> response = http.post().uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RegistrationRequest(email, "Str@ngPass!", "Str@ngPass!", "Ivan", "Ivanov"))
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
