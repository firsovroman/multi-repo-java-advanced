package com.example.individuals.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Тело ошибки OAuth 2.0 token endpoint (RFC 6749 §5.2).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakErrorResponse(
        @JsonProperty("error") String error,
        @JsonProperty("error_description") String errorDescription
) {
}
