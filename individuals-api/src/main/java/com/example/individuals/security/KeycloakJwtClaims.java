package com.example.individuals.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Доменный идентификатор из claim {@code user_uid}; пусто, если claim отсутствует или не является UUID
     * в канонической записи.
     */
    public static Optional<UUID> userUid(Jwt jwt) {
        String value = jwt.getClaimAsString(USER_UID);
        if (value == null) {
            return Optional.empty();
        }
        try {
            UUID userUid = UUID.fromString(value);
            // UUID.fromString принимает и неканонические записи вида "1-2-3-4-5"
            return userUid.toString().equalsIgnoreCase(value) ? Optional.of(userUid) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
