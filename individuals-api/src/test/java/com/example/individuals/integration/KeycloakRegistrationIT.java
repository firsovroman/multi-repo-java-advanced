package com.example.individuals.integration;

import com.example.individuals.api.model.CurrentUserResponse;
import com.example.individuals.api.model.ErrorResponse;
import com.example.individuals.api.model.LoginRequest;
import com.example.individuals.api.model.RefreshTokenRequest;
import com.example.individuals.api.model.RegistrationRequest;
import com.example.individuals.api.model.TokenResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозные сценарии с реальным Keycloak: IT-KC-001, IT-KC-002, IT-KC-003, а также refresh и /me.
 */
class KeycloakRegistrationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Str@ngPass!";

    private static Keycloak keycloakAdmin;

    @Autowired
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeAll
    static void setUpAdminClient() {
        keycloakAdmin = KEYCLOAK.getKeycloakAdminClient();
    }

    @AfterAll
    static void closeAdminClient() {
        keycloakAdmin.close();
    }

    @Test
    void registrationCreatesUserInRealmWithUserUidAttribute() {
        // IT-KC-001, IT-KC-002
        String email = uniqueEmail();
        UUID userUid = stubPersonRegistration(email);

        ResponseEntity<TokenResponse> response = register(email);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualTo(userUid);

        List<UserRepresentation> users = keycloakAdmin.realm(REALM).users().searchByEmail(email, true);
        assertThat(users).hasSize(1);
        UserRepresentation user = users.getFirst();
        assertThat(user.getFirstName()).isEqualTo("Ivan");
        assertThat(user.getAttributes()).containsEntry("user_uid", List.of(userUid.toString()));
    }

    @Test
    void loginReturnsRealJwtFromTokenEndpoint() {
        // IT-KC-003
        String email = uniqueEmail();
        UUID userUid = stubPersonRegistration(email);
        register(email);

        ResponseEntity<TokenResponse> response = http.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest(email, PASSWORD))
                .retrieve()
                .toEntity(TokenResponse.class).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TokenResponse tokens = response.getBody();
        assertThat(tokens).isNotNull();
        assertThat(tokens.getUserId()).isEqualTo(userUid);
        assertThat(tokens.getTokenType()).isEqualToIgnoringCase("Bearer");
        assertThat(tokens.getExpiresIn()).isEqualTo(300);

        Jwt jwt = jwtDecoder.decode(tokens.getAccessToken()).block();
        assertThat(jwt.getIssuer().toString()).endsWith("/realms/" + REALM);
        assertThat(jwt.getClaimAsString("user_uid")).isEqualTo(userUid.toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo(email);
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        String email = uniqueEmail();
        stubPersonRegistration(email);
        register(email);

        ResponseEntity<ErrorResponse> response = http.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new LoginRequest(email, "wrong-password"))
                .retrieve()
                .toEntity(ErrorResponse.class).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(response.getBody().getTraceId()).isNotBlank();
    }

    @Test
    void refreshTokenReturnsNewAccessToken() {
        String email = uniqueEmail();
        UUID userUid = stubPersonRegistration(email);
        TokenResponse registered = register(email).getBody();
        assertThat(registered).isNotNull();

        ResponseEntity<TokenResponse> response = http.post().uri("/api/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RefreshTokenRequest(registered.getRefreshToken()))
                .retrieve()
                .toEntity(TokenResponse.class).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualTo(userUid);
        assertThat(response.getBody().getAccessToken()).isNotBlank().isNotEqualTo(registered.getAccessToken());
    }

    @Test
    void meReturnsCurrentUserForValidBearerToken() {
        String email = uniqueEmail();
        UUID userUid = stubPersonRegistration(email);
        TokenResponse tokens = register(email).getBody();
        assertThat(tokens).isNotNull();

        ResponseEntity<CurrentUserResponse> response = http.get().uri("/api/v1/auth/me")
                .headers(headers -> headers.setBearerAuth(tokens.getAccessToken()))
                .retrieve()
                .toEntity(CurrentUserResponse.class).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        CurrentUserResponse me = response.getBody();
        assertThat(me).isNotNull();
        assertThat(me.getUserId()).isEqualTo(userUid);
        assertThat(me.getEmail()).isEqualTo(email);
        assertThat(me.getFirstName()).isEqualTo("Ivan");
        assertThat(me.getLastName()).isEqualTo("Ivanov");
        assertThat(me.getRoles()).containsExactly("USER");
        assertThat(me.getEmailVerified()).isFalse();
        String keycloakUserId = keycloakAdmin.realm(REALM).users().searchByEmail(email, true).getFirst().getId();
        assertThat(me.getKeycloakUserId()).isEqualTo(keycloakUserId);
    }

    @Test
    void meWithoutTokenReturns401InErrorFormat() {
        ResponseEntity<ErrorResponse> response = http.get().uri("/api/v1/auth/me")
                .retrieve()
                .toEntity(ErrorResponse.class).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("UNAUTHORIZED");
        assertThat(response.getBody().getPath()).isEqualTo("/api/v1/auth/me");
    }

    @Test
    void registrationWithTakenEmailReturns409AndKeycloakUntouched() {
        String email = uniqueEmail();
        PERSON_SERVICE.stubFor(post(urlEqualTo("/api/v1/persons/registration"))
                .withRequestBody(containing(email))
                .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":409,\"error\":\"CONFLICT\"}")));

        ResponseEntity<ErrorResponse> response = http.post().uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationRequest(email))
                .retrieve()
                .toEntity(ErrorResponse.class).block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("CONFLICT");
        assertThat(keycloakAdmin.realm(REALM).users().searchByEmail(email, true)).isEmpty();
    }

    private ResponseEntity<TokenResponse> register(String email) {
        return http.post().uri("/api/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationRequest(email))
                .retrieve()
                .toEntity(TokenResponse.class).block();
    }

    private static RegistrationRequest registrationRequest(String email) {
        return new RegistrationRequest(email, PASSWORD, PASSWORD, "Ivan", "Ivanov");
    }
}
