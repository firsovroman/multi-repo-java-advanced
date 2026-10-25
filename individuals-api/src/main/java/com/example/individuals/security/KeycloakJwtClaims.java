package com.example.individuals.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Разбор claims access token Keycloak.
 */
public final class KeycloakJwtClaims {

    public static final String USER_UID = "user_uid";

    /** Служебные роли Keycloak, которые не относятся к ролям платформы. */
    private static final Set<String> TECHNICAL_ROLES = Set.of("offline_access", "uma_authorization");

    private KeycloakJwtClaims() {
    }

    public static UUID userUid(Jwt jwt) {
        String value = jwt.getClaimAsString(USER_UID);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    /**
     * Роли realm из claim {@code realm_access.roles} без служебных ролей Keycloak.
     */
    public static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(String::valueOf)
                .filter(role -> !TECHNICAL_ROLES.contains(role) && !role.startsWith("default-roles-"))
                .sorted()
                .toList();
    }
}
