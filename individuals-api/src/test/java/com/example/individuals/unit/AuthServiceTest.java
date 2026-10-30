package com.example.individuals.unit;

import com.example.individuals.api.model.CurrentUserResponse;
import com.example.individuals.api.model.LoginRequest;
import com.example.individuals.api.model.RefreshTokenRequest;
import com.example.individuals.api.model.RegistrationRequest;
import com.example.individuals.api.model.TokenResponse;
import com.example.individuals.error.EmailAlreadyExistsException;
import com.example.individuals.error.ErrorCode;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.error.InconsistentRegistrationException;
import com.example.individuals.error.InvalidAccessTokenException;
import com.example.individuals.error.InvalidCredentialsException;
import com.example.individuals.error.InvalidRefreshTokenException;
import com.example.individuals.error.PasswordMismatchException;
import com.example.individuals.gateway.PersonGateway;
import com.example.individuals.keycloak.KeycloakGateway;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import com.example.individuals.metrics.AuthMetrics;
import com.example.individuals.service.AuthService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "Str@ngPass!";
    private static final UUID USER_UID = UUID.fromString("3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e");
    private static final String KEYCLOAK_USER_ID = "6d4a6249-75d8-4b8d-aee8-c2f6ddafaa11";
    private static final KeycloakTokenResponse TOKENS =
            new KeycloakTokenResponse("access-token", "refresh-token", 300, "Bearer");

    @Mock
    private PersonGateway personGateway;
    @Mock
    private KeycloakGateway keycloakGateway;
    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    private SimpleMeterRegistry meterRegistry;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        authService = new AuthService(personGateway, keycloakGateway, jwtDecoder, new AuthMetrics(meterRegistry));
    }

    @Test
    void validRegistrationCallsPersonServiceThenKeycloakAndReturnsTokens() {
        // UT-REG-001
        when(personGateway.registerPerson(EMAIL, "Ivan", "Ivanov")).thenReturn(Mono.just(USER_UID));
        when(keycloakGateway.createUser(EMAIL, "Ivan", "Ivanov", USER_UID)).thenReturn(Mono.just(KEYCLOAK_USER_ID));
        when(keycloakGateway.resetPassword(KEYCLOAK_USER_ID, PASSWORD)).thenReturn(Mono.empty());
        when(keycloakGateway.login(EMAIL, PASSWORD)).thenReturn(Mono.just(TOKENS));

        TokenResponse response = authService.register(registration(" User@Example.com ", PASSWORD, PASSWORD)).block();

        InOrder order = inOrder(personGateway, keycloakGateway);
        order.verify(personGateway).registerPerson(EMAIL, "Ivan", "Ivanov");
        order.verify(keycloakGateway).createUser(EMAIL, "Ivan", "Ivanov", USER_UID);
        order.verify(keycloakGateway).resetPassword(KEYCLOAK_USER_ID, PASSWORD);
        order.verify(keycloakGateway).login(EMAIL, PASSWORD);
        order.verifyNoMoreInteractions();

        assertThat(response).isNotNull();
        assertThat(response.getUserId()).isEqualTo(USER_UID);
        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getExpiresIn()).isEqualTo(300);
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(counter("auth.registration")).isEqualTo(1);
        assertThat(counter("auth.registration.success")).isEqualTo(1);
        assertThat(counter("auth.registration.failure")).isZero();
    }

    @Test
    void registrationIsLazyUntilSubscribed() {
        authService.register(registration(EMAIL, PASSWORD, PASSWORD));

        verifyNoInteractions(personGateway, keycloakGateway);
        assertThat(counter("auth.registration")).isZero();
    }

    @Test
    void passwordMismatchIsRejectedWithoutExternalCalls() {
        // UT-REG-002
        StepVerifier.create(authService.register(registration(EMAIL, PASSWORD, "Other@Pass1")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PasswordMismatchException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST)))
                .verify();

        verifyNoInteractions(personGateway, keycloakGateway);
        assertThat(counter("auth.registration.failure")).isEqualTo(1);
    }

    @Test
    void emailConflictInPersonServiceDoesNotReachKeycloak() {
        // UT-REG-003
        when(personGateway.registerPerson(EMAIL, "Ivan", "Ivanov")).thenReturn(Mono.error(new EmailAlreadyExistsException()));

        StepVerifier.create(authService.register(registration(EMAIL, PASSWORD, PASSWORD)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(EmailAlreadyExistsException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT)))
                .verify();

        verifyNoInteractions(keycloakGateway);
        assertThat(counter("auth.registration.failure")).isEqualTo(1);
    }

    @Test
    void keycloakFailureAfterDomainUserCreatedIsReportedAsInconsistentRegistration() {
        // UT-REG-004
        when(personGateway.registerPerson(EMAIL, "Ivan", "Ivanov")).thenReturn(Mono.just(USER_UID));
        ExternalServiceUnavailableException keycloakDown = new ExternalServiceUnavailableException("keycloak", null);
        when(keycloakGateway.createUser(EMAIL, "Ivan", "Ivanov", USER_UID)).thenReturn(Mono.error(keycloakDown));

        StepVerifier.create(authService.register(registration(EMAIL, PASSWORD, PASSWORD)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(InconsistentRegistrationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_INCONSISTENT);
                    assertThat(e.getUserUid()).isEqualTo(USER_UID);
                    assertThat(e.getDetails()).containsExactly("userId: " + USER_UID);
                    assertThat(e.getCause()).isSameAs(keycloakDown);
                }))
                .verify();
        assertThat(counter("auth.registration.failure")).isEqualTo(1);
        assertThat(counter("auth.registration.success")).isZero();
    }

    @Test
    void successfulLoginReturnsTokensAndUserUidFromAccessToken() {
        // UT-LOG-001
        when(keycloakGateway.login(EMAIL, PASSWORD)).thenReturn(Mono.just(TOKENS));
        when(jwtDecoder.decode("access-token")).thenReturn(Mono.just(jwt(Map.of("user_uid", USER_UID.toString()))));

        StepVerifier.create(authService.login(new LoginRequest("USER@example.com", PASSWORD)))
                .assertNext(response -> {
                    assertThat(response.getUserId()).isEqualTo(USER_UID);
                    assertThat(response.getAccessToken()).isEqualTo("access-token");
                    assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
                })
                .verifyComplete();
        assertThat(counter("auth.login")).isEqualTo(1);
        assertThat(counter("auth.login.failure")).isZero();
    }

    @Test
    void wrongPasswordIsUnauthorized() {
        // UT-LOG-002
        when(keycloakGateway.login(EMAIL, "wrong")).thenReturn(Mono.error(new InvalidCredentialsException()));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, "wrong")))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(InvalidCredentialsException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED)))
                .verify();
        assertThat(counter("auth.login")).isEqualTo(1);
        assertThat(counter("auth.login.failure")).isEqualTo(1);
    }

    @Test
    void refreshReturnsNewAccessToken() {
        // UT-REF-001
        KeycloakTokenResponse refreshed = new KeycloakTokenResponse("new-access-token", "new-refresh-token", 300, null);
        when(keycloakGateway.refresh("refresh-token")).thenReturn(Mono.just(refreshed));
        when(jwtDecoder.decode("new-access-token")).thenReturn(Mono.just(jwt(Map.of("user_uid", USER_UID.toString()))));

        StepVerifier.create(authService.refresh(new RefreshTokenRequest("refresh-token")))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("new-access-token");
                    assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
                    assertThat(response.getUserId()).isEqualTo(USER_UID);
                    assertThat(response.getTokenType()).isEqualTo("Bearer");
                })
                .verifyComplete();
        assertThat(counter("auth.refresh")).isEqualTo(1);
    }

    @Test
    void invalidRefreshTokenIsPropagated() {
        when(keycloakGateway.refresh("expired")).thenReturn(Mono.error(new InvalidRefreshTokenException()));

        StepVerifier.create(authService.refresh(new RefreshTokenRequest("expired")))
                .expectError(InvalidRefreshTokenException.class)
                .verify();
    }

    @Test
    void loginWithTokenWithoutUserUidIsExternalServiceError() {
        when(keycloakGateway.login(any(), any())).thenReturn(Mono.just(TOKENS));
        when(jwtDecoder.decode("access-token")).thenReturn(Mono.just(jwt(Map.of("email", EMAIL))));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(ExternalServiceException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY)))
                .verify();
        assertThat(counter("auth.login.failure")).isEqualTo(1);
    }

    @Test
    void refreshWithMalformedUserUidIsExternalServiceError() {
        when(keycloakGateway.refresh("refresh-token")).thenReturn(Mono.just(TOKENS));
        when(jwtDecoder.decode("access-token")).thenReturn(Mono.just(jwt(Map.of("user_uid", "not-a-uuid"))));

        StepVerifier.create(authService.refresh(new RefreshTokenRequest("refresh-token")))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void undecodableAccessTokenFromKeycloakIsExternalServiceError() {
        when(keycloakGateway.login(any(), any())).thenReturn(Mono.just(TOKENS));
        when(jwtDecoder.decode("access-token")).thenReturn(Mono.error(new BadJwtException("malformed")));

        StepVerifier.create(authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void currentUserWithoutUserUidIsUnauthorized() {
        StepVerifier.create(authService.currentUser(jwt(Map.of("email", EMAIL))))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(InvalidAccessTokenException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED)))
                .verify();
    }

    @Test
    void currentUserIsBuiltFromValidBearerToken() {
        // UT-ME-001
        Jwt jwt = jwt(Map.of(
                "user_uid", USER_UID.toString(),
                "email", EMAIL,
                "given_name", "Ivan",
                "family_name", "Ivanov",
                "email_verified", false,
                "realm_access", Map.of("roles", List.of("default-roles-payment-platform", "offline_access",
                        "uma_authorization", "USER"))));

        CurrentUserResponse me = authService.currentUser(jwt).block();

        assertThat(me).isNotNull();
        assertThat(me.getUserId()).isEqualTo(USER_UID);
        assertThat(me.getKeycloakUserId()).isEqualTo(KEYCLOAK_USER_ID);
        assertThat(me.getEmail()).isEqualTo(EMAIL);
        assertThat(me.getFirstName()).isEqualTo("Ivan");
        assertThat(me.getLastName()).isEqualTo("Ivanov");
        assertThat(me.getRoles()).containsExactly("USER");
        assertThat(me.getEmailVerified()).isFalse();
    }

    private static RegistrationRequest registration(String email, String password, String confirmPassword) {
        return new RegistrationRequest(email, password, confirmPassword, "Ivan", "Ivanov");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(KEYCLOAK_USER_ID)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
