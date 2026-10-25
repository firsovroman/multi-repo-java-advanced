package com.example.individuals.service;

import com.example.individuals.api.model.CurrentUserResponse;
import com.example.individuals.api.model.LoginRequest;
import com.example.individuals.api.model.RefreshTokenRequest;
import com.example.individuals.api.model.RegistrationRequest;
import com.example.individuals.api.model.TokenResponse;
import com.example.individuals.error.ErrorCode;
import com.example.individuals.error.InconsistentRegistrationException;
import com.example.individuals.error.PasswordMismatchException;
import com.example.individuals.gateway.PersonGateway;
import com.example.individuals.keycloak.KeycloakGateway;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import com.example.individuals.logging.LogContext;
import com.example.individuals.metrics.AuthMetrics;
import com.example.individuals.security.KeycloakJwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

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
    private final JwtDecoder jwtDecoder;
    private final AuthMetrics metrics;

    public AuthService(PersonGateway personGateway, KeycloakGateway keycloakGateway, JwtDecoder jwtDecoder,
                       AuthMetrics metrics) {
        this.personGateway = personGateway;
        this.keycloakGateway = keycloakGateway;
        this.jwtDecoder = jwtDecoder;
        this.metrics = metrics;
    }

    public TokenResponse register(RegistrationRequest request) {
        metrics.registrationAttempt();
        TokenResponse response = countingFailures(() -> doRegister(request), metrics::registrationFailure);
        metrics.registrationSuccess();
        return response;
    }

    public TokenResponse login(LoginRequest request) {
        metrics.loginAttempt();
        return countingFailures(() -> {
            KeycloakTokenResponse tokens = keycloakGateway.login(normalizeEmail(request.getEmail()), request.getPassword());
            return toTokenResponse(userUidFrom(tokens), tokens);
        }, metrics::loginFailure);
    }

    public TokenResponse refresh(RefreshTokenRequest request) {
        metrics.refresh();
        KeycloakTokenResponse tokens = keycloakGateway.refresh(request.getRefreshToken());
        return toTokenResponse(userUidFrom(tokens), tokens);
    }

    public CurrentUserResponse currentUser(Jwt jwt) {
        UUID userUid = KeycloakJwtClaims.userUid(jwt);
        LogContext.putUserUid(userUid);
        return new CurrentUserResponse()
                .userUid(userUid)
                .keycloakUserId(jwt.getSubject())
                .email(jwt.getClaimAsString("email"))
                .firstName(jwt.getClaimAsString("given_name"))
                .lastName(jwt.getClaimAsString("family_name"))
                .roles(KeycloakJwtClaims.realmRoles(jwt))
                .emailVerified(Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
    }

    private TokenResponse doRegister(RegistrationRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new PasswordMismatchException();
        }
        String email = normalizeEmail(request.getEmail());

        UUID userUid = personGateway.registerPerson(email, request.getFirstName(), request.getLastName());
        LogContext.putUserUid(userUid);

        try {
            String keycloakUserId = keycloakGateway.createUser(email, request.getFirstName(), request.getLastName(), userUid);
            keycloakGateway.resetPassword(keycloakUserId, request.getPassword());
            KeycloakTokenResponse tokens = keycloakGateway.login(email, request.getPassword());
            log.atInfo().addKeyValue("keycloakUserId", keycloakUserId).log("User registered");
            return toTokenResponse(userUid, tokens);
        } catch (RuntimeException e) {
            LogContext.putErrorCode(ErrorCode.REGISTRATION_INCONSISTENT);
            log.atError()
                    .setCause(e)
                    .addKeyValue("event", "INCONSISTENT_REGISTRATION")
                    .addKeyValue("email", email)
                    .log("Domain user {} created in person-service, but Keycloak registration failed", userUid);
            throw new InconsistentRegistrationException(userUid, e);
        }
    }

    private UUID userUidFrom(KeycloakTokenResponse tokens) {
        UUID userUid = KeycloakJwtClaims.userUid(jwtDecoder.decode(tokens.accessToken()));
        LogContext.putUserUid(userUid);
        if (userUid == null) {
            log.warn("Access token has no {} claim", KeycloakJwtClaims.USER_UID);
        }
        return userUid;
    }

    private static TokenResponse toTokenResponse(UUID userUid, KeycloakTokenResponse tokens) {
        return new TokenResponse(userUid, tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(),
                tokens.tokenType() != null ? tokens.tokenType() : "Bearer");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> T countingFailures(Supplier<T> action, Runnable onFailure) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            onFailure.run();
            throw e;
        }
    }
}
