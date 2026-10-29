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
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разбор ответов Keycloak и перевод ошибок на заглушке WireMock.
 */
class KeycloakGatewayTest {

    private static final String TOKEN_PATH = "/realms/payment-platform/protocol/openid-connect/token";
    private static final String USERS_PATH = "/admin/realms/payment-platform/users";
    private static final String TOKEN_JSON = """
            {"access_token":"%s","refresh_token":"refresh","expires_in":300,"token_type":"Bearer","scope":"openid"}""";

    private WireMockServer server;
    private SimpleMeterRegistry meterRegistry;
    private KeycloakGateway gateway;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        // Как в приложении: без сжатия ответов (см. HttpClientsConfig)
        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().compress(false)))
                .baseUrl(server.baseUrl())
                .build();
        KeycloakProperties properties =
                new KeycloakProperties(server.baseUrl(), "payment-platform", "individuals-api", "secret");
        meterRegistry = new SimpleMeterRegistry();
        KeycloakCalls calls = new KeycloakCalls(new ExternalCallMetrics(meterRegistry));
        KeycloakTokenClient tokenClient = new KeycloakTokenClient(webClient, properties, calls);
        KeycloakAdminTokenProvider adminTokenProvider = new KeycloakAdminTokenProvider(tokenClient, properties);
        gateway = new KeycloakGateway(webClient, properties, adminTokenProvider, tokenClient, calls);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void createUserSendsUserUidAttributeAndReturnsIdFromLocation() {
        UUID userUid = UUID.randomUUID();
        stubAdminToken();
        server.stubFor(post(USERS_PATH)
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer admin-token"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("user@example.com")))
                .withRequestBody(matchingJsonPath("$.email", equalTo("user@example.com")))
                .withRequestBody(matchingJsonPath("$.enabled", equalTo("true")))
                .withRequestBody(matchingJsonPath("$.attributes.user_uid[0]", equalTo(userUid.toString())))
                .willReturn(created("kc-user-id")));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", userUid))
                .expectNext("kc-user-id")
                .verifyComplete();
    }

    @Test
    void adminTokenIsCachedBetweenCalls() {
        stubAdminToken();
        server.stubFor(post(USERS_PATH).willReturn(created("kc-user-id")));
        server.stubFor(put(USERS_PATH + "/kc-user-id/reset-password")
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer admin-token"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("password")))
                .withRequestBody(matchingJsonPath("$.value", equalTo("Str@ngPass!")))
                .withRequestBody(matchingJsonPath("$.temporary", equalTo("false")))
                .willReturn(aResponse().withStatus(204)));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID())
                        .flatMap(keycloakUserId -> gateway.resetPassword(keycloakUserId, "Str@ngPass!")))
                .verifyComplete();

        server.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        server.verify(1, putRequestedFor(urlEqualTo(USERS_PATH + "/kc-user-id/reset-password")));
    }

    @Test
    void createUserWithoutLocationHeaderFails() {
        stubAdminToken();
        server.stubFor(post(USERS_PATH).willReturn(aResponse().withStatus(201)));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void createUserConflictIsExternalServiceError() {
        stubAdminToken();
        server.stubFor(post(USERS_PATH).willReturn(status(HttpStatus.CONFLICT.value())));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void forbiddenAdminTokenIsExternalServiceError() {
        server.stubFor(tokenRequest().willReturn(status(HttpStatus.FORBIDDEN.value())));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void failedAdminTokenIsNotCached() {
        server.stubFor(tokenRequest().willReturn(serviceUnavailable()));
        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectError(ExternalServiceUnavailableException.class)
                .verify();

        server.resetAll();
        stubAdminToken();
        server.stubFor(post(USERS_PATH).willReturn(created("kc-user-id")));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectNext("kc-user-id")
                .verifyComplete();
    }

    @Test
    void loginUsesPasswordGrantAndParsesTokenResponse() {
        server.stubFor(tokenRequest()
                .withHeader(HttpHeaders.CONTENT_TYPE, containing("application/x-www-form-urlencoded"))
                .withFormParam("grant_type", equalTo("password"))
                .withFormParam("client_id", equalTo("individuals-api"))
                .withFormParam("client_secret", equalTo("secret"))
                .withFormParam("username", equalTo("user@example.com"))
                .withFormParam("password", equalTo("Str@ngPass!"))
                .willReturn(okJson(TOKEN_JSON.formatted("user-token"))));

        StepVerifier.create(gateway.login("user@example.com", "Str@ngPass!"))
                .expectNext(new KeycloakTokenResponse("user-token", "refresh", 300, "Bearer"))
                .verifyComplete();
        assertThat(meterRegistry.get(ExternalCallMetrics.KEYCLOAK).tag("operation", "password_grant").timer().count())
                .isEqualTo(1);
    }

    @Test
    void loginWithInvalidCredentialsIsUnauthorized() {
        server.stubFor(tokenRequest().willReturn(aResponse().withStatus(401)
                .withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}")));

        StepVerifier.create(gateway.login("user@example.com", "wrong"))
                .expectError(InvalidCredentialsException.class)
                .verify();
    }

    @Test
    void refreshUsesRefreshTokenGrant() {
        server.stubFor(tokenRequest()
                .withFormParam("grant_type", equalTo("refresh_token"))
                .withFormParam("refresh_token", equalTo("old-refresh"))
                .willReturn(okJson(TOKEN_JSON.formatted("new-token"))));

        StepVerifier.create(gateway.refresh("old-refresh").map(KeycloakTokenResponse::accessToken))
                .expectNext("new-token")
                .verifyComplete();
    }

    @Test
    void invalidRefreshTokenIsRejected() {
        server.stubFor(tokenRequest().willReturn(status(HttpStatus.BAD_REQUEST.value())));

        StepVerifier.create(gateway.refresh("expired"))
                .expectError(InvalidRefreshTokenException.class)
                .verify();
    }

    @Test
    void keycloakUnavailableIsTranslated() {
        server.stubFor(tokenRequest().willReturn(serviceUnavailable()));

        StepVerifier.create(gateway.login("user@example.com", "Str@ngPass!"))
                .expectError(ExternalServiceUnavailableException.class)
                .verify();
    }

    @Test
    void networkErrorIsTranslatedToUnavailable() {
        server.stop();

        StepVerifier.create(gateway.login("user@example.com", "Str@ngPass!"))
                .expectError(ExternalServiceUnavailableException.class)
                .verify();
    }

    @Test
    void unexpectedTokenEndpointErrorIsExternalServiceError() {
        server.stubFor(tokenRequest().willReturn(status(HttpStatus.TOO_MANY_REQUESTS.value())));

        StepVerifier.create(gateway.login("user@example.com", "Str@ngPass!"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void emptyTokenResponseIsExternalServiceError() {
        server.stubFor(tokenRequest().willReturn(ok()));

        StepVerifier.create(gateway.login("user@example.com", "Str@ngPass!"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"expires_in\":300,\"token_type\":\"Bearer\"}",
            "{\"access_token\":\"\",\"refresh_token\":\"refresh\",\"expires_in\":300}",
            "{\"access_token\":\"user-token\",\"expires_in\":300}",
            "{\"access_token\":\"user-token\",\"refresh_token\":\" \",\"expires_in\":300}",
            "{\"access_token\":\"user-token\",\"refresh_token\":\"refresh\"}",
            "{\"access_token\":\"user-token\",\"refresh_token\":\"refresh\",\"expires_in\":0}",
            "{\"access_token\":\"user-token\",\"refresh_token\":\"refresh\",\"expires_in\":-1}"
    })
    void incompleteUserTokenResponseIsExternalServiceError(String body) {
        server.stubFor(tokenRequest().willReturn(okJson(body)));

        StepVerifier.create(gateway.login("user@example.com", "Str@ngPass!"))
                .expectError(ExternalServiceException.class)
                .verify();
        StepVerifier.create(gateway.refresh("old-refresh"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void adminTokenWithoutRefreshTokenIsAccepted() {
        server.stubFor(tokenRequest()
                .withFormParam("grant_type", equalTo("client_credentials"))
                .willReturn(okJson("{\"access_token\":\"admin-token\",\"expires_in\":300,\"token_type\":\"Bearer\"}")));
        server.stubFor(post(USERS_PATH)
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer admin-token"))
                .willReturn(created("kc-user-id")));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectNext("kc-user-id")
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"expires_in\":300,\"token_type\":\"Bearer\"}",
            "{\"access_token\":\"admin-token\",\"expires_in\":0}"
    })
    void incompleteAdminTokenResponseIsExternalServiceError(String body) {
        server.stubFor(tokenRequest().willReturn(okJson(body)));

        StepVerifier.create(gateway.createUser("user@example.com", "Ivan", "Ivanov", UUID.randomUUID()))
                .expectError(ExternalServiceException.class)
                .verify();
        server.verify(0, postRequestedFor(urlEqualTo(USERS_PATH)));
    }

    private MappingBuilder tokenRequest() {
        return post(TOKEN_PATH);
    }

    private void stubAdminToken() {
        server.stubFor(tokenRequest()
                .withFormParam("grant_type", equalTo("client_credentials"))
                .willReturn(okJson(TOKEN_JSON.formatted("admin-token"))));
    }

    private ResponseDefinitionBuilder created(String keycloakUserId) {
        return aResponse().withStatus(201).withHeader(HttpHeaders.LOCATION, server.baseUrl() + USERS_PATH + "/" + keycloakUserId);
    }
}
