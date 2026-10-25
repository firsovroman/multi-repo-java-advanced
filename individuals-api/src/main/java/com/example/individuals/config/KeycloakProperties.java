package com.example.individuals.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки подключения к Keycloak.
 *
 * @param serverUrl    адрес, по которому individuals-api обращается к Keycloak (backchannel)
 * @param realm        realm платформы
 * @param clientId     confidential client individuals-api
 * @param clientSecret секрет клиента; через его service account вызывается Admin REST API
 */
@Validated
@ConfigurationProperties("app.keycloak")
public record KeycloakProperties(
        @NotBlank String serverUrl,
        @NotBlank String realm,
        @NotBlank String clientId,
        @NotBlank String clientSecret
) {
}
