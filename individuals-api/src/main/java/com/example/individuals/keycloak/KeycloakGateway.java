package com.example.individuals.keycloak;

import com.example.individuals.config.KeycloakProperties;
import com.example.individuals.keycloak.dto.KeycloakCredentialRepresentation;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import com.example.individuals.keycloak.dto.KeycloakUserRepresentation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private final RestClient restClient;
    private final KeycloakProperties properties;
    private final KeycloakAdminTokenProvider adminTokenProvider;
    private final KeycloakTokenClient tokenClient;
    private final KeycloakCalls calls;

    public KeycloakGateway(@Qualifier("keycloakRestClient") RestClient restClient, KeycloakProperties properties,
                           KeycloakAdminTokenProvider adminTokenProvider, KeycloakTokenClient tokenClient, KeycloakCalls calls) {
        this.restClient = restClient;
        this.properties = properties;
        this.adminTokenProvider = adminTokenProvider;
        this.tokenClient = tokenClient;
        this.calls = calls;
    }

    /**
     * Создаёт учётную запись с атрибутом {@code user_uid} и возвращает её идентификатор в Keycloak.
     */
    public String createUser(String email, String firstName, String lastName, UUID userUid) {
        KeycloakUserRepresentation user = new KeycloakUserRepresentation(null, email, email, firstName, lastName,
                true, false, Map.of(USER_UID_ATTRIBUTE, List.of(userUid.toString())));
        ResponseEntity<Void> response = calls.execute("create_user", () -> restClient.post()
                .uri(USERS_PATH, properties.realm())
                .headers(headers -> headers.setBearerAuth(adminTokenProvider.getToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(user)
                .retrieve()
                .toBodilessEntity());
        return userIdFromLocation(response.getHeaders().getLocation());
    }

    public void resetPassword(String keycloakUserId, String password) {
        calls.execute("reset_password", () -> restClient.put()
                .uri(RESET_PASSWORD_PATH, properties.realm(), keycloakUserId)
                .headers(headers -> headers.setBearerAuth(adminTokenProvider.getToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(KeycloakCredentialRepresentation.password(password))
                .retrieve()
                .toBodilessEntity());
    }

    public KeycloakTokenResponse login(String email, String password) {
        return tokenClient.password(email, password);
    }

    public KeycloakTokenResponse refresh(String refreshToken) {
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
