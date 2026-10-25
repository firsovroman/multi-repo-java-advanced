package com.example.individuals.unit;

import com.example.individuals.config.KeycloakProperties;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.error.InvalidCredentialsException;
import com.example.individuals.error.InvalidRefreshTokenException;
import com.example.individuals.keycloak.KeycloakAdminTokenProvider;
import com.example.individuals.keycloak.KeycloakCalls;
import com.example.individuals.keycloak.KeycloakGateway;
import com.example.individuals.keycloak.KeycloakTokenClient;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import com.example.individuals.metrics.ExternalCallMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Разбор ответов Keycloak и перевод ошибок без реального сервера.
 */
class KeycloakGatewayTest {

    private static final String BASE_URL = "http://keycloak.test";
    private static final String TOKEN_URL = BASE_URL + "/realms/payment-platform/protocol/openid-connect/token";
    private static final String USERS_URL = BASE_URL + "/admin/realms/payment-platform/users";
    private static final String TOKEN_JSON = """
            {"access_token":"%s","refresh_token":"refresh","expires_in":300,"token_type":"Bearer","scope":"openid"}""";

    private MockRestServiceServer server;
    private SimpleMeterRegistry meterRegistry;
    private KeycloakGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        KeycloakProperties properties =
                new KeycloakProperties(BASE_URL, "payment-platform", "individuals-api", "secret");
        meterRegistry = new SimpleMeterRegistry();
        KeycloakCalls calls = new KeycloakCalls(new ExternalCallMetrics(meterRegistry));
        KeycloakTokenClient tokenClient = new KeycloakTokenClient(restClient, properties, calls);
        KeycloakAdminTokenProvider adminTokenProvider = new KeycloakAdminTokenProvider(tokenClient, properties);
        gateway = new KeycloakGateway(restClient, properties, adminTokenProvider, tokenClient, calls);
    }

    @Test
    void createUserSendsUserUidAttributeAndReturnsIdFromLocation() {
        UUID userUid = UUID.randomUUID();
        expectAdminToken();
        server.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(jsonPath("$.username").value("user@example.com"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.attributes.user_uid[0]").value(userUid.toString()))
                .andRespond(withCreatedEntity(URI.create(USERS_URL + "/kc-user-id")));

        String keycloakUserId = gateway.createUser("user@example.com", "Ivan", "Ivanov", userUid);

        assertThat(keycloakUserId).isEqualTo("kc-user-id");
        server.verify();
    }

    @Test
    void adminTokenIsCachedBetweenCalls() {
        expectAdminToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withCreatedEntity(URI.create(USERS_URL + "/kc-user-id")));
        server.expect(requestTo(USERS_URL + "/kc-user-id/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(jsonPath("$.type").value("password"))
                .andExpect(jsonPath("$.value").value("Str@ngPass!"))
                .andExpect(jsonPath("$.temporary").value(false))
                .andRespond(withNoContent());

        String keycloakUserId = gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID());
        gateway.resetPassword(keycloakUserId, "Str@ngPass!");

        server.verify();
    }

    @Test
    void createUserWithoutLocationHeaderFails() {
        expectAdminToken();
        server.expect(requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.CREATED));

        assertThatThrownBy(() -> gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createUserConflictIsExternalServiceError() {
        expectAdminToken();
        server.expect(requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void forbiddenAdminTokenIsExternalServiceError() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void loginUsesPasswordGrantAndParsesTokenResponse() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "password",
                        "client_id", "individuals-api",
                        "client_secret", "secret",
                        "username", "user@example.com",
                        "password", "Str@ngPass!")))
                .andRespond(withSuccess(TOKEN_JSON.formatted("user-token"), MediaType.APPLICATION_JSON));

        KeycloakTokenResponse tokens = gateway.login("user@example.com", "Str@ngPass!");

        assertThat(tokens).isEqualTo(new KeycloakTokenResponse("user-token", "refresh", 300, "Bearer"));
        assertThat(meterRegistry.get(ExternalCallMetrics.KEYCLOAK).tag("operation", "password_grant").timer().count())
                .isEqualTo(1);
    }

    @Test
    void loginWithInvalidCredentialsIsUnauthorized() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}"));

        assertThatThrownBy(() -> gateway.login("user@example.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshUsesRefreshTokenGrant() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "refresh_token",
                        "refresh_token", "old-refresh")))
                .andRespond(withSuccess(TOKEN_JSON.formatted("new-token"), MediaType.APPLICATION_JSON));

        assertThat(gateway.refresh("old-refresh").accessToken()).isEqualTo("new-token");
    }

    @Test
    void invalidRefreshTokenIsRejected() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> gateway.refresh("expired"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void keycloakUnavailableIsTranslated() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> gateway.login("user@example.com", "Str@ngPass!"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void unexpectedTokenEndpointErrorIsExternalServiceError() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> gateway.login("user@example.com", "Str@ngPass!"))
                .isInstanceOf(ExternalServiceException.class);
    }

    private void expectAdminToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().formDataContains(Map.of("grant_type", "client_credentials")))
                .andRespond(withSuccess(TOKEN_JSON.formatted("admin-token"), MediaType.APPLICATION_JSON));
    }
}
