package com.example.individuals.keycloak.dto;

/**
 * CredentialRepresentation Keycloak Admin REST API для установки пароля.
 */
public record KeycloakCredentialRepresentation(String type, String value, boolean temporary) {

    public static KeycloakCredentialRepresentation password(String value) {
        return new KeycloakCredentialRepresentation("password", value, false);
    }
}
