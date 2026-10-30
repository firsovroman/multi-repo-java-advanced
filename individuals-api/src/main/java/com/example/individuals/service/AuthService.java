package com.example.individuals.service;

import com.example.individuals.api.model.CurrentUserResponse;
import com.example.individuals.api.model.LoginRequest;
import com.example.individuals.api.model.RefreshTokenRequest;
import com.example.individuals.api.model.RegistrationRequest;
import com.example.individuals.api.model.TokenResponse;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.InconsistentRegistrationException;
import com.example.individuals.error.InvalidAccessTokenException;
import com.example.individuals.error.PasswordMismatchException;
import com.example.individuals.gateway.PersonGateway;
import com.example.individuals.keycloak.KeycloakCalls;
import com.example.individuals.keycloak.KeycloakGateway;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import com.example.individuals.logging.LogContext;
import com.example.individuals.metrics.AuthMetrics;
import com.example.individuals.security.KeycloakJwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Оркестрация регистрации и аутентификации.
 * <p>
 * Регистрация: person-service (доменный пользователь, {@code userUid}) -> Keycloak (учётная запись,
 * пароль, атрибут {@code user_uid}) -> вход через token endpoint. В бизнес-слое используется только
 * доменный {@code userUid}; идентификатор Keycloak остаётся технической связкой.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final PersonGateway personGateway;
    private final KeycloakGateway keycloakGateway;
    private final ReactiveJwtDecoder jwtDecoder;
    private final AuthMetrics metrics;

    public AuthService(PersonGateway personGateway, KeycloakGateway keycloakGateway, ReactiveJwtDecoder jwtDecoder,
                       AuthMetrics metrics) {
        this.personGateway = personGateway;
        this.keycloakGateway = keycloakGateway;
        this.jwtDecoder = jwtDecoder;
        this.metrics = metrics;
    }

    public Mono<TokenResponse> register(RegistrationRequest request) {
        return Mono.defer(() -> {
                    metrics.registrationAttempt();
                    return doRegister(request);
                })
                .doOnSuccess(response -> metrics.registrationSuccess())
                .doOnError(e -> metrics.registrationFailure());
    }

    public Mono<TokenResponse> login(LoginRequest request) {
        return Mono.defer(() -> {
                    metrics.loginAttempt();
                    return keycloakGateway.login(normalizeEmail(request.getEmail()), request.getPassword());
                })
                .flatMap(this::toTokenResponse)
                .doOnError(e -> metrics.loginFailure());
    }

    public Mono<TokenResponse> refresh(RefreshTokenRequest request) {
        return Mono.defer(() -> {
                    metrics.refresh();
                    return keycloakGateway.refresh(request.getRefreshToken());
                })
                .flatMap(this::toTokenResponse);
    }

    /**
     * Токен без корректного {@code user_uid} отклоняется ещё в {@code SecurityConfig}; проверка здесь
     * защищает от вызова в обход этого конвертера.
     */
    public Mono<CurrentUserResponse> currentUser(Jwt jwt) {
        UUID userUid = KeycloakJwtClaims.userUid(jwt).orElse(null);
        if (userUid == null) {
            return Mono.error(new InvalidAccessTokenException());
        }
        CurrentUserResponse response = new CurrentUserResponse()
                .userId(userUid)
                .keycloakUserId(jwt.getSubject())
                .email(jwt.getClaimAsString("email"))
                .firstName(jwt.getClaimAsString("given_name"))
                .lastName(jwt.getClaimAsString("family_name"))
                .roles(KeycloakJwtClaims.realmRoles(jwt))
                .emailVerified(Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
        return LogContext.putUserUid(userUid).thenReturn(response);
    }

    private Mono<TokenResponse> doRegister(RegistrationRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            return Mono.error(new PasswordMismatchException());
        }
        String email = normalizeEmail(request.getEmail());
        return personGateway.registerPerson(email, request.getFirstName(), request.getLastName())
                .flatMap(userUid -> LogContext.putUserUid(userUid)
                        .then(registerInKeycloak(email, request, userUid)));
    }

    private Mono<TokenResponse> registerInKeycloak(String email, RegistrationRequest request, UUID userUid) {
        return keycloakGateway.createUser(email, request.getFirstName(), request.getLastName(), userUid)
                .flatMap(keycloakUserId -> keycloakGateway.resetPassword(keycloakUserId, request.getPassword())
                        .then(Mono.defer(() -> keycloakGateway.login(email, request.getPassword())))
                        .doOnNext(tokens -> log.atInfo()
                                .addKeyValue(LogContext.USER_UID, userUid)
                                .addKeyValue("keycloakUserId", keycloakUserId)
                                .log("User registered")))
                .map(tokens -> toTokenResponse(userUid, tokens))
                .onErrorMap(e -> {
                    log.atError()
                            .setCause(e)
                            .addKeyValue("event", "INCONSISTENT_REGISTRATION")
                            .addKeyValue(LogContext.USER_UID, userUid)
                            .addKeyValue("email", email)
                            .log("Domain user {} created in person-service, but Keycloak registration failed", userUid);
                    return new InconsistentRegistrationException(userUid, e);
                });
    }

    /**
     * Токен от Keycloak без корректного {@code user_uid} — некорректный ответ внешнего сервиса
     * (не настроен mapper атрибута или атрибут повреждён), а не ошибка клиента.
     */
    private Mono<TokenResponse> toTokenResponse(KeycloakTokenResponse tokens) {
        return jwtDecoder.decode(tokens.accessToken())
                .onErrorMap(JwtException.class, e -> new ExternalServiceException(KeycloakCalls.SERVICE, e))
                .flatMap(jwt -> KeycloakJwtClaims.userUid(jwt)
                        .map(userUid -> LogContext.putUserUid(userUid).thenReturn(toTokenResponse(userUid, tokens)))
                        .orElseGet(() -> Mono.error(new ExternalServiceException(KeycloakCalls.SERVICE,
                                new IllegalStateException("Keycloak access token has missing or malformed "
                                        + KeycloakJwtClaims.USER_UID + " claim")))));
    }

    private static TokenResponse toTokenResponse(UUID userUid, KeycloakTokenResponse tokens) {
        return new TokenResponse(userUid, tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(),
                tokens.tokenType() != null ? tokens.tokenType() : "Bearer");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
