package com.example.individuals.keycloak;

import com.example.individuals.config.KeycloakProperties;
import com.example.individuals.keycloak.dto.KeycloakCredentialRepresentation;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import com.example.individuals.keycloak.dto.KeycloakUserRepresentation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Шлюз к Keycloak: учётные записи (Admin REST API) и токены (OIDC token endpoint).
 * Внутренний идентификатор Keycloak наружу шлюза уходит только как техническая связка.
 */
@Component
public class KeycloakGateway {

    public static final String USER_UID_ATTRIBUTE = "user_uid";

    private static final String USERS_PATH = "/admin/realms/{realm}/users";
    private static final String RESET_PASSWORD_PATH = "/admin/realms/{realm}/users/{id}/reset-password";

    private final WebClient webClient;
    private final KeycloakProperties properties;
    private final KeycloakAdminTokenProvider adminTokenProvider;
    private final KeycloakTokenClient tokenClient;
    private final KeycloakCalls calls;

    public KeycloakGateway(@Qualifier("keycloakWebClient") WebClient webClient, KeycloakProperties properties,
                           KeycloakAdminTokenProvider adminTokenProvider, KeycloakTokenClient tokenClient, KeycloakCalls calls) {
        this.webClient = webClient;
        this.properties = properties;
        this.adminTokenProvider = adminTokenProvider;
        this.tokenClient = tokenClient;
        this.calls = calls;
    }

    /**
     * Создаёт учётную запись с атрибутом {@code user_uid} и возвращает её идентификатор в Keycloak.
     */
    public Mono<String> createUser(String email, String firstName, String lastName, UUID userUid) {
        KeycloakUserRepresentation user = new KeycloakUserRepresentation(null, email, email, firstName, lastName,
                true, false, Map.of(USER_UID_ATTRIBUTE, List.of(userUid.toString())));
        return calls.execute("create_user", adminTokenProvider.getToken().flatMap(token -> webClient.post()
                        .uri(USERS_PATH, properties.realm())
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(user)
                        .retrieve()
                        .toBodilessEntity()))
                .map(response -> userIdFromLocation(response.getHeaders().getLocation()));
    }

    public Mono<Void> resetPassword(String keycloakUserId, String password) {
        return calls.execute("reset_password", adminTokenProvider.getToken().flatMap(token -> webClient.put()
                        .uri(RESET_PASSWORD_PATH, properties.realm(), keycloakUserId)
                        .headers(headers -> headers.setBearerAuth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(KeycloakCredentialRepresentation.password(password))
                        .retrieve()
                        .toBodilessEntity()))
                .then();
    }

    public Mono<KeycloakTokenResponse> login(String email, String password) {
        return tokenClient.password(email, password);
    }

    public Mono<KeycloakTokenResponse> refresh(String refreshToken) {
        return tokenClient.refresh(refreshToken);
    }

    private static String userIdFromLocation(URI location) {
        if (location == null) {
            throw new IllegalStateException("Keycloak did not return Location header for created user");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
