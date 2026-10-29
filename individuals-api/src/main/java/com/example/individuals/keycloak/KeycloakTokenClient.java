package com.example.individuals.keycloak;

import com.example.individuals.config.KeycloakProperties;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.InvalidCredentialsException;
import com.example.individuals.error.InvalidRefreshTokenException;
import com.example.individuals.keycloak.dto.KeycloakErrorResponse;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
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
    private static final String INVALID_GRANT = "invalid_grant";

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
        return requestToken("password_grant", form, true, rejectedAs(InvalidCredentialsException::new));
    }

    public Mono<KeycloakTokenResponse> refresh(String refreshToken) {
        MultiValueMap<String, String> form = clientForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken("refresh_token", form, true, rejectedAs(InvalidRefreshTokenException::new));
    }

    public Mono<KeycloakTokenResponse> clientCredentials(String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        // По RFC 6749 §4.4.3 refresh token для client_credentials выдавать не следует
        return requestToken("admin_token", form, false, e -> new ExternalServiceException(KeycloakCalls.SERVICE, e));
    }

    private Mono<KeycloakTokenResponse> requestToken(String operation, MultiValueMap<String, String> form,
                                                     boolean refreshTokenRequired,
                                                     Function<WebClientResponseException, RuntimeException> onClientError) {
        return calls.execute(operation, webClient.post()
                        .uri(TOKEN_PATH, properties.realm())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(BodyInserters.fromFormData(form))
                        .retrieve()
                        .bodyToMono(KeycloakTokenResponse.class), onClientError)
                .switchIfEmpty(Mono.error(() -> invalidResponse("empty token response")))
                .flatMap(response -> validate(response, refreshTokenRequired));
    }

    /**
     * Keycloak может ответить 200 с неполным телом — такой ответ нельзя отдавать клиенту как успешный.
     */
    private static Mono<KeycloakTokenResponse> validate(KeycloakTokenResponse response, boolean refreshTokenRequired) {
        if (!StringUtils.hasText(response.accessToken())) {
            return Mono.error(invalidResponse("token response without access_token"));
        }
        if (refreshTokenRequired && !StringUtils.hasText(response.refreshToken())) {
            return Mono.error(invalidResponse("token response without refresh_token"));
        }
        if (response.expiresIn() <= 0) {
            return Mono.error(invalidResponse("token response with non-positive expires_in"));
        }
        return Mono.just(response);
    }

    private static ExternalServiceException invalidResponse(String reason) {
        return new ExternalServiceException(KeycloakCalls.SERVICE,
                new IllegalStateException("Keycloak returned " + reason));
    }

    private MultiValueMap<String, String> clientForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        return form;
    }

    /**
     * Отказ пользователю — только {@code invalid_grant}: Keycloak возвращает его (401 или 400) на неверные
     * учётные данные, недействительный refresh token и неактивную учётную запись. Остальные коды
     * ({@code invalid_client}, {@code unauthorized_client}, {@code invalid_request} и т.п.) означают
     * ошибку конфигурации самого individuals-api как OAuth-клиента — это ошибка интеграции, а не пользователя.
     */
    private static Function<WebClientResponseException, RuntimeException> rejectedAs(
            Supplier<RuntimeException> rejection) {
        return e -> isRejection(e) && INVALID_GRANT.equals(oauthError(e))
                ? rejection.get()
                : new ExternalServiceException(KeycloakCalls.SERVICE, e);
    }

    private static boolean isRejection(WebClientResponseException e) {
        return e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED) || e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST);
    }

    private static String oauthError(WebClientResponseException e) {
        try {
            KeycloakErrorResponse body = e.getResponseBodyAs(KeycloakErrorResponse.class);
            return body != null ? body.error() : null;
        } catch (RuntimeException decodingFailure) {
            return null;
        }
    }
}
