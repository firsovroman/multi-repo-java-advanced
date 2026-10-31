package com.example.person.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * Общая инфраструктура интеграционных тестов: один контейнер PostgreSQL на запуск и реальный Flyway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=1.0")
abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("person")
            .withUsername("person")
            .withPassword("person");

    static {
        POSTGRES.start();
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected Response get(String path) {
        return send("GET", path, null);
    }

    protected Response post(String path, String body) {
        return send("POST", path, body);
    }

    protected Response patch(String path, String body, String... headers) {
        return send("PATCH", path, body, headers);
    }

    protected Response delete(String path) {
        return send("DELETE", path, null);
    }

    protected Response send(String method, String path, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body != null ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        try {
            return new Response(HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected static String fullUser(String email) {
        return """
                {
                  "email": "%s",
                  "firstName": "Ivan",
                  "lastName": "Ivanov",
                  "address": {
                    "countryAlpha3": "RUS",
                    "countryAlpha2": "RU",
                    "city": "Moscow",
                    "state": "Moscow",
                    "zipCode": "101000",
                    "addressLine": "Tverskaya 1"
                  },
                  "individual": {
                    "passportNumber": "4510123456",
                    "phoneNumber": "+79991234567"
                  }
                }
                """.formatted(email);
    }

    protected static String minimalUser(String email) {
        return """
                {"email": "%s", "firstName": "Ivan", "lastName": "Ivanov"}
                """.formatted(email);
    }

    protected String createFullUser() {
        Response created = post("/api/v1/users", fullUser(uniqueEmail()));
        return created.body().path("id").asString();
    }

    protected int count(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM person." + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    protected record Response(HttpResponse<String> raw) {

        int status() {
            return raw.statusCode();
        }

        JsonNode body() {
            return JSON.readTree(raw.body());
        }

        Optional<String> header(String name) {
            return raw.headers().firstValue(name);
        }

        String errorCode() {
            return body().path("errorCode").asString();
        }
    }
}
