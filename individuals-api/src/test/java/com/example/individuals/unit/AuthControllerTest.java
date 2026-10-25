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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-контракт контроллера: коды ответов, формат ошибок и защита /me.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JsonSecurityErrorHandler.class, ErrorResponseFactory.class, GlobalExceptionHandler.class,
        AuthControllerTest.Config.class})
class AuthControllerTest {

    private static final UUID USER_UID = UUID.fromString("3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e");
    private static final String REGISTRATION = """
            {"email":"user@example.com","password":"Str@ngPass!","confirmPassword":"Str@ngPass!",
             "firstName":"Ivan","lastName":"Ivanov"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void registrationReturns201WithTokens() throws Exception {
        when(authService.register(any())).thenReturn(new TokenResponse(USER_UID, "access", "refresh", 300L, "Bearer"));

        mockMvc.perform(post("/api/v1/auth/registration").contentType(MediaType.APPLICATION_JSON).content(REGISTRATION))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER_UID.toString()))
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void passwordMismatchReturns400() throws Exception {
        // UT-REG-002 на уровне HTTP
        when(authService.register(any())).thenThrow(new PasswordMismatchException());

        mockMvc.perform(post("/api/v1/auth/registration").contentType(MediaType.APPLICATION_JSON).content(REGISTRATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PASSWORD_MISMATCH"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/v1/auth/registration"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void invalidRequestReturns400WithValidationDetails() throws Exception {
        mockMvc.perform(post("/api/v1/auth/registration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isNotEmpty());

        verify(authService, never()).register(any());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void emailConflictReturns409() throws Exception {
        // UT-REG-003 на уровне HTTP
        when(authService.register(any())).thenThrow(new EmailAlreadyExistsException());

        mockMvc.perform(post("/api/v1/auth/registration").contentType(MediaType.APPLICATION_JSON).content(REGISTRATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Пользователь с таким email уже существует"));
    }

    @Test
    void inconsistentRegistrationReturns502WithUserUid() throws Exception {
        // UT-REG-004 на уровне HTTP
        when(authService.register(any())).thenThrow(new InconsistentRegistrationException(USER_UID,
                new ExternalServiceUnavailableException("keycloak", null)));

        mockMvc.perform(post("/api/v1/auth/registration").contentType(MediaType.APPLICATION_JSON).content(REGISTRATION))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("REGISTRATION_INCONSISTENT"))
                .andExpect(jsonPath("$.details[0]").value("userUid: " + USER_UID));
    }

    @Test
    void invalidCredentialsReturn401() throws Exception {
        // UT-LOG-002 на уровне HTTP
        when(authService.login(any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshReturns200() throws Exception {
        when(authService.refresh(any())).thenReturn(new TokenResponse(USER_UID, "new-access", "new-refresh", 300L, "Bearer"));

        mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"));
    }

    @Test
    void unexpectedErrorReturns500() throws Exception {
        when(authService.refresh(any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/v1/auth/refresh-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));
    }

    @Test
    void meWithValidTokenReturnsCurrentUser() throws Exception {
        // UT-ME-001 на уровне HTTP
        when(authService.currentUser(any(Jwt.class))).thenReturn(new CurrentUserResponse()
                .userUid(USER_UID).keycloakUserId("kc-id").email("user@example.com")
                .firstName("Ivan").lastName("Ivanov").roles(List.of("USER")).emailVerified(false));

        mockMvc.perform(get("/api/v1/auth/me").with(jwt().jwt(token -> token.subject("kc-id"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userUid").value(USER_UID.toString()))
                .andExpect(jsonPath("$.keycloakUserId").value("kc-id"))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void unknownPathReturnsErrorResponse() throws Exception {
        mockMvc.perform(get("/api/v1/unknown").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        Tracer tracer() {
            return Tracer.NOOP;
        }
    }
}
