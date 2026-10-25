package com.example.individuals.keycloak;

import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.metrics.ExternalCallMetrics;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Общая обёртка вызовов Keycloak: таймер {@code external.keycloak.requests} и перевод
 * сетевых ошибок и 5xx в {@link ExternalServiceUnavailableException}, а неожиданных 4xx —
 * в {@link ExternalServiceException}. Ожидаемые 4xx вызывающий код обрабатывает сам в {@code onClientError}.
 */
@Component
public class KeycloakCalls {

    static final String SERVICE = "keycloak";

    private final ExternalCallMetrics metrics;

    public KeycloakCalls(ExternalCallMetrics metrics) {
        this.metrics = metrics;
    }

    <T> T execute(String operation, Supplier<T> call) {
        return execute(operation, call, e -> new ExternalServiceException(SERVICE, e));
    }

    <T> T execute(String operation, Supplier<T> call,
                  Function<HttpClientErrorException, RuntimeException> onClientError) {
        try {
            return metrics.record(ExternalCallMetrics.KEYCLOAK, operation, call);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new ExternalServiceUnavailableException(SERVICE, e);
        } catch (HttpClientErrorException e) {
            throw onClientError.apply(e);
        }
    }
}
