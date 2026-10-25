package com.example.individuals.unit;

import com.example.individuals.security.KeycloakJwtClaims;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtClaimsTest {

    @Test
    void realmRolesExcludeKeycloakTechnicalRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles",
                List.of("default-roles-payment-platform", "offline_access", "uma_authorization", "USER", "ADMIN"))));

        assertThat(KeycloakJwtClaims.realmRoles(jwt)).containsExactly("ADMIN", "USER");
    }

    @Test
    void missingRealmAccessGivesNoRoles() {
        assertThat(KeycloakJwtClaims.realmRoles(jwt(Map.of("email", "user@example.com")))).isEmpty();
    }

    @Test
    void userUidIsParsedFromClaim() {
        UUID userUid = UUID.randomUUID();

        assertThat(KeycloakJwtClaims.userUid(jwt(Map.of("user_uid", userUid.toString())))).isEqualTo(userUid);
        assertThat(KeycloakJwtClaims.userUid(jwt(Map.of("user_uid", " ")))).isNull();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("kc-id");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
