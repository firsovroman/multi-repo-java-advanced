package com.example.individuals.keycloak;

import com.example.individuals.config.KeycloakProperties;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.InvalidCredentialsException;
import com.example.individuals.error.InvalidRefreshTokenException;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Вызовы OIDC token endpoint: {@code /realms/{realm}/protocol/openid-connect/token}.
 */
@Component
public class KeycloakTokenClient {

    private static final String TOKEN_PATH = "/realms/{realm}/protocol/openid-connect/token";

    private final WebClient webClient;
    private final KeycloakProperties properties;
    private final KeycloakCalls calls;

    public KeycloakTokenClient(@Qualifier("keycloakWebClient") WebClient webClient, KeycloakProperties properties,
                               KeycloakCalls calls) {
        this.webClient = webClient;
        this.properties = properties;
        this.calls = calls;
    }

    public Mono<KeycloakTokenResponse> password(String username, String password) {
        MultiValueMap<String, String> form = clientForm("password");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid");
        return requestToken("password_grant", form, rejectedAs(InvalidCredentialsException::new));
    }

    public Mono<KeycloakTokenResponse> refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken("refresh_token", form, rejectedAs(InvalidRefreshTokenException::new));
    }

    public Mono<KeycloakTokenResponse> clientCredentials(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return requestToken("admin_token", form, e -> new ExternalServiceException(KeycloakCalls.SERVICE, e));
    }

    private Mono<KeycloakTokenResponse> requestToken(String operation, MultiValueMap<String, String> form,
                                                     Function<WebClientResponseException, RuntimeException> onClientError) {
        return calls.execute(operation, webClient.post()
                        .uri(TOKEN_PATH, properties.realm())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(BodyInserters.fromFormData(form))
                        .retrieve()
                        .bodyToMono(KeycloakTokenResponse.class), onClientError)
                .switchIfEmpty(Mono.error(() -> new ExternalServiceException(KeycloakCalls.SERVICE,
                        new IllegalStateException("Keycloak returned empty token response"))));
    }

    private MultiValueMap<String, String> clientForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        return form;
    }

    /**
     * Keycloak отвечает 401 на неверные учётные данные и 400 (invalid_grant) на недействительный refresh token
     * или неактивную учётную запись — для клиента это одинаково «отказ в аутентификации».
     */
    private static Function<WebClientResponseException, RuntimeException> rejectedAs(
            Supplier<RuntimeException> rejection) {
        return e -> e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED) || e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)
                ? rejection.get()
                : new ExternalServiceException(KeycloakCalls.SERVICE, e);
    }
}
