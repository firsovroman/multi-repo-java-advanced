package com.example.individuals.keycloak;

import com.example.individuals.config.KeycloakProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Административный токен Keycloak, полученный через client_credentials service account клиента individuals-api.
 * Кешируется до истечения срока жизни (с запасом); параллельные запросы ждут один вызов token endpoint,
 * ошибки не кешируются.
 */
@Component
public class KeycloakAdminTokenProvider {

    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final Mono<CachedToken> token;

    @Autowired
    public KeycloakAdminTokenProvider(KeycloakTokenClient tokenClient, KeycloakProperties properties) {
        this(tokenClient, properties, Clock.systemUTC());
    }

    KeycloakAdminTokenProvider(KeycloakTokenClient tokenClient, KeycloakProperties properties, Clock clock) {
        this.token = Mono.defer(() -> tokenClient.clientCredentials(properties.clientId(), properties.clientSecret()))
                .map(response -> new CachedToken(response.accessToken(),
                        clock.instant().plusSeconds(response.expiresIn()).minus(EXPIRY_MARGIN)))
                .cacheInvalidateIf(cached -> !cached.isValidAt(clock.instant()));
    }

    public Mono<String> getToken() {
        return token.map(CachedToken::value);
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
