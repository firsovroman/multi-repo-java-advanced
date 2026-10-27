package com.example.individuals.unit;

import com.example.individuals.api.model.CurrentUserResponse;
import com.example.individuals.api.model.TokenResponse;
import com.example.individuals.controller.AuthController;
import com.example.individuals.error.EmailAlreadyExistsException;
import com.example.individuals.error.ErrorResponseFactory;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.error.GlobalExceptionHandler;
import com.example.individuals.error.InconsistentRegistrationException;
import com.example.individuals.error.InvalidCredentialsException;
import com.example.individuals.error.PasswordMismatchException;
import com.example.individuals.security.JsonSecurityErrorHandler;
import com.example.individuals.security.SecurityConfig;
import com.example.individuals.service.AuthService;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * HTTP-контракт контроллера: коды ответов, формат ошибок и защита /me.
 */
@WebFluxTest(AuthController.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, ErrorResponseFactory.class, GlobalExceptionHandler.class,
        AuthControllerTest.Config.class})
class AuthControllerTest {

    private static final UUID USER_UID = UUID.fromString("3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e");
    private static final String REGISTRATION = """
            {"email":"user@example.com","password":"Str@ngPass!","confirmPassword":"Str@ngPass!",
             "firstName":"Ivan","lastName":"Ivanov"}""";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @Test
    void registrationReturns201WithTokens() {
        when(authService.register(any())).thenReturn(Mono.just(new TokenResponse(USER_UID, "access", "refresh", 300L, "Bearer")));

        post("/api/v1/auth/registration", REGISTRATION)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(USER_UID.toString())
                .jsonPath("$.accessToken").isEqualTo("access")
                .jsonPath("$.refreshToken").isEqualTo("refresh")
                .jsonPath("$.expiresIn").isEqualTo(300)
                .jsonPath("$.tokenType").isEqualTo("Bearer");
    }

    @Test
    void passwordMismatchReturns400() {
        // UT-REG-002 на уровне HTTP
        when(authService.register(any())).thenReturn(Mono.error(new PasswordMismatchException()));

        post("/api/v1/auth/registration", REGISTRATION)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("PASSWORD_MISMATCH")
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.path").isEqualTo("/api/v1/auth/registration")
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.traceId").exists();
    }

    @Test
    void invalidRequestReturns400WithValidationDetails() {
        post("/api/v1/auth/registration", "{\"email\":\"not-an-email\",\"password\":\"short\"}")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details").isNotEmpty();

        verify(authService, never()).register(any());
    }

    @Test
    void malformedJsonReturns400() {
        post("/api/v1/auth/login", "{")
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void emailConflictReturns409() {
        // UT-REG-003 на уровне HTTP
        when(authService.register(any())).thenReturn(Mono.error(new EmailAlreadyExistsException()));

        post("/api/v1/auth/registration", REGISTRATION)
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("CONFLICT")
                .jsonPath("$.message").isEqualTo("Пользователь с таким email уже существует");
    }

    @Test
    void inconsistentRegistrationReturns502WithUserUid() {
        // UT-REG-004 на уровне HTTP
        when(authService.register(any())).thenReturn(Mono.error(new InconsistentRegistrationException(USER_UID,
                new ExternalServiceUnavailableException("keycloak", null))));

        post("/api/v1/auth/registration", REGISTRATION)
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error").isEqualTo("REGISTRATION_INCONSISTENT")
                .jsonPath("$.details[0]").isEqualTo("userUid: " + USER_UID);
    }

    @Test
    void invalidCredentialsReturn401() {
        // UT-LOG-002 на уровне HTTP
        when(authService.login(any())).thenReturn(Mono.error(new InvalidCredentialsException()));

        post("/api/v1/auth/login", "{\"email\":\"user@example.com\",\"password\":\"wrong\"}")
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void refreshReturns200() {
        when(authService.refresh(any()))
                .thenReturn(Mono.just(new TokenResponse(USER_UID, "new-access", "new-refresh", 300L, "Bearer")));

        post("/api/v1/auth/refresh-token", "{\"refreshToken\":\"refresh\"}")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("new-access");
    }

    @Test
    void unexpectedErrorReturns500() {
        when(authService.refresh(any())).thenReturn(Mono.error(new IllegalStateException("boom")));

        post("/api/v1/auth/refresh-token", "{\"refreshToken\":\"refresh\"}")
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.error").isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void meWithValidTokenReturnsCurrentUser() {
        // UT-ME-001 на уровне HTTP
        when(authService.currentUser(any(Jwt.class))).thenReturn(Mono.just(new CurrentUserResponse()
                .userUid(USER_UID).keycloakUserId("kc-id").email("user@example.com")
                .firstName("Ivan").lastName("Ivanov").roles(List.of("USER")).emailVerified(false)));

        webTestClient.mutateWith(mockJwt().jwt(token -> token.subject("kc-id")))
                .get().uri("/api/v1/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userUid").isEqualTo(USER_UID.toString())
                .jsonPath("$.keycloakUserId").isEqualTo("kc-id")
                .jsonPath("$.roles[0]").isEqualTo("USER");
    }

    @Test
    void meWithoutTokenReturns401() {
        webTestClient.get().uri("/api/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.error").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void unknownPathReturnsErrorResponse() {
        webTestClient.mutateWith(mockJwt())
                .get().uri("/api/v1/unknown")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").isEqualTo("NOT_FOUND");
    }

    private WebTestClient.ResponseSpec post(String uri, String body) {
        return webTestClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        Tracer tracer() {
            return Tracer.NOOP;
        }
    }
}
