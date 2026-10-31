package com.example.person.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * IT-PS-011 (Actuator и метрики), IT-PS-012 (журнал запроса).
 */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void actuatorExposesHealthAndApplicationMetrics() {
        // IT-PS-011
        String id = createFullUser();
        String email = uniqueEmail();
        post("/api/v1/users", minimalUser(email));
        post("/api/v1/users", minimalUser(email));
        patch("/api/v1/users/" + id, """
                {"firstName": "Petr"}
                """);
        delete("/api/v1/users/" + id);

        assertThat(get("/actuator/health").status()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").body().path("status").asString()).isEqualTo("UP");
        assertThat(get("/actuator/health/liveness").status()).isEqualTo(200);

        Response metrics = get("/actuator/prometheus");
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.raw().body())
                .contains("person_users_creations_total")
                .contains("person_users_updated_total")
                .contains("person_users_deleted_total")
                .contains("person_users_email_conflicts_total")
                .contains("person_users_operation_seconds_count{application=\"person-service\",operation=\"create\",outcome=\"success\"")
                .contains("person_users_operation_seconds_count{application=\"person-service\",operation=\"create\",outcome=\"error\"")
                .contains("http_server_requests_seconds_count");
    }

    @Test
    void requestLogHasServiceAndTraceFields(CapturedOutput output) {
        // IT-PS-012
        UUID id = UUID.randomUUID();
        Response notFound = get("/api/v1/users/" + id);
        String traceId = notFound.body().path("traceId").asString();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut()).contains(traceId));

        JsonNode requestLog = output.getOut().lines()
                .filter(line -> line.startsWith("{") && line.contains(traceId))
                .map(JSON::readTree)
                .filter(json -> json.path("http").path("status").asInt() == 404)
                .findFirst()
                .orElseThrow();
        assertThat(requestLog.path("service").path("name").asString()).isEqualTo("person-service");
        assertThat(requestLog.path("traceId").asString()).isEqualTo(traceId);
        assertThat(requestLog.path("spanId").asString()).matches("[0-9a-f]{16}");
        assertThat(requestLog.path("http").path("path").asString()).isEqualTo("/api/v1/users/" + id);
        assertThat(requestLog.path("http").path("method").asString()).isEqualTo("GET");
        assertThat(requestLog.path("errorCode").asString()).isEqualTo("USER_NOT_FOUND");
        assertThat(requestLog.path("userUid").asString()).isEmpty();
    }

    @Test
    void requestLogCarriesUserUidButNoPersonalData(CapturedOutput output) {
        String email = uniqueEmail();

        Response created = post("/api/v1/users", fullUser(email));
        String id = created.body().path("id").asString();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut()).contains(id));

        JsonNode requestLog = output.getOut().lines()
                .filter(line -> line.startsWith("{") && line.contains(id))
                .map(JSON::readTree)
                .filter(json -> json.path("http").path("status").asInt() == 201)
                .findFirst()
                .orElseThrow();
        assertThat(requestLog.path("userUid").asString()).isEqualTo(id);
        assertThat(output.getOut()).doesNotContain(email).doesNotContain("4510123456").doesNotContain("+79991234567");
    }
}
