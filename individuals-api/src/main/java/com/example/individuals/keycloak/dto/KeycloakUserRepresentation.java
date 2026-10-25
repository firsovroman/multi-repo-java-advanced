package com.example.individuals.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Подмножество UserRepresentation Keycloak Admin REST API, нужное для регистрации.
 * {@code attributes} — формат Keycloak: имя атрибута -> список значений.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakUserRepresentation(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        Map<String, List<String>> attributes
) {
}
