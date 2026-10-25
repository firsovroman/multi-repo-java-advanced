package com.example.individuals.keycloak;

import com.example.individuals.config.KeycloakProperties;
import com.example.individuals.keycloak.dto.KeycloakTokenResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Административный токен Keycloak, полученный через client_credentials service account клиента individuals-api.
 * Кешируется до истечения срока жизни (с запасом).
 */
@Component
public class KeycloakAdminTokenProvider {

    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final KeycloakTokenClient tokenClient;
    private final KeycloakProperties properties;
    private final Clock clock;

    private volatile CachedToken cached;

    @Autowired
    public KeycloakAdminTokenProvider(KeycloakTokenClient tokenClient, KeycloakProperties properties) {
        this(tokenClient, properties, Clock.systemUTC());
    }

    KeycloakAdminTokenProvider(KeycloakTokenClient tokenClient, KeycloakProperties properties, Clock clock) {
        this.tokenClient = tokenClient;
        this.properties = properties;
        this.clock = clock;
    }

    public String getToken() {
        CachedToken current = cached;
        if (current != null && current.isValidAt(clock.instant())) {
            return current.value();
        }
        synchronized (this) {
            current = cached;
            if (current == null || !current.isValidAt(clock.instant())) {
                KeycloakTokenResponse response = tokenClient.clientCredentials(properties.clientId(), properties.clientSecret());
                current = new CachedToken(response.accessToken(),
                        clock.instant().plusSeconds(response.expiresIn()).minus(EXPIRY_MARGIN));
                cached = current;
            }
            return current.value();
        }
    }

    public void invalidate() {
        cached = null;
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
