package com.example.person.integration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.person.repository.CountryRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * IT-PS-010: формат ошибок для 400, 404, 409, 500.
 */
class ProblemFormatIT extends AbstractIntegrationTest {

    @MockitoSpyBean
    private CountryRepository countries;

    @Test
    void validationError() {
        Response response = post("/api/v1/persons/registration", """
                {"email": "not-an-email", "firstName": "Ivan"}
                """);

        assertProblem(response, 400, "VALIDATION_ERROR", "/api/v1/persons/registration");
        assertThat(response.body().path("type").asString()).isEqualTo("https://example.org/problems/validation-error");
        assertThat(response.body().path("errors").findValuesAsString("field")).contains("email", "lastName");
        assertThat(response.raw().body()).doesNotContain("not-an-email");
    }

    @Test
    void malformedJsonAndPath() {
        Response body = post("/api/v1/users", "{not json");
        Response path = get("/api/v1/users/not-a-uuid");

        assertProblem(body, 400, "MALFORMED_REQUEST", "/api/v1/users");
        assertProblem(path, 400, "MALFORMED_REQUEST", "/api/v1/users/not-a-uuid");
    }

    @Test
    void invalidAndMissingEmailParameter() {
        Response invalid = get("/api/v1/persons/by-email?email=not-an-email");
        Response missing = get("/api/v1/users/by-email");

        assertProblem(invalid, 400, "VALIDATION_ERROR", "/api/v1/persons/by-email");
        assertThat(invalid.body().path("errors").findValuesAsString("field")).containsExactly("email");
        assertProblem(missing, 400, "VALIDATION_ERROR", "/api/v1/users/by-email");
    }

    @Test
    void countryProblems() {
        String unknown = fullUser(uniqueEmail()).replace("\"RUS\"", "\"ZZZ\"");
        String mismatch = fullUser(uniqueEmail()).replace("\"RU\"", "\"KZ\"");

        assertProblem(post("/api/v1/users", unknown), 400, "COUNTRY_NOT_FOUND", "/api/v1/users");
        assertProblem(post("/api/v1/users", mismatch), 400, "COUNTRY_MISMATCH", "/api/v1/users");
    }

    @Test
    void notFound() {
        String path = "/api/v1/users/" + UUID.randomUUID();

        assertProblem(get(path), 404, "USER_NOT_FOUND", path);
    }

    @Test
    void unknownRouteAndMethodAreProblemsToo() {
        assertProblem(get("/api/v1/unknown"), 404, "NOT_FOUND", "/api/v1/unknown");
        assertProblem(send("PUT", "/api/v1/users", "{}"), 405, "METHOD_NOT_ALLOWED", "/api/v1/users");
    }

    @Test
    void emailConflictDoesNotRevealEmail() {
        String email = uniqueEmail();
        post("/api/v1/users", minimalUser(email));

        Response response = post("/api/v1/users", minimalUser(email));

        assertProblem(response, 409, "EMAIL_ALREADY_EXISTS", "/api/v1/users");
        assertThat(response.raw().body()).doesNotContain(email);
    }

    @Test
    void internalErrorHidesDetails() {
        doThrow(new IllegalStateException("SELECT secret FROM person.users")).when(countries).findByAlpha3(any());

        Response response = post("/api/v1/users", fullUser(uniqueEmail()));
        Mockito.reset(countries);

        assertProblem(response, 500, "INTERNAL_ERROR", "/api/v1/users");
        assertThat(response.body().path("detail").asString()).isEqualTo("Внутренняя ошибка сервиса");
        assertThat(response.raw().body()).doesNotContain("SELECT").doesNotContain("person.users");
    }

    private static void assertProblem(Response response, int status, String errorCode, String instance) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/problem+json"));
        assertThat(response.errorCode()).isEqualTo(errorCode);
        assertThat(response.body().path("status").asInt()).isEqualTo(status);
        assertThat(response.body().path("instance").asString()).isEqualTo(instance);
        assertThat(response.body().path("traceId").asString()).matches("[0-9a-f]{32}");
    }
}
