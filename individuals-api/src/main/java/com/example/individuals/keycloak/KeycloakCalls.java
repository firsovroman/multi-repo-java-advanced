package com.example.individuals.keycloak;

import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.metrics.ExternalCallMetrics;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Общая обёртка вызовов Keycloak: таймер {@code external.keycloak.requests} и перевод
 * сетевых ошибок и 5xx в {@link ExternalServiceUnavailableException}, а неожиданных 4xx —
 * в {@link ExternalServiceException}. Ожидаемые 4xx вызывающий код обрабатывает сам в {@code onClientError}.
 */
@Component
public class KeycloakCalls {

    public static final String SERVICE = "keycloak";

    private final ExternalCallMetrics metrics;

    public KeycloakCalls(ExternalCallMetrics metrics) {
        this.metrics = metrics;
    }

    <T> Mono<T> execute(String operation, Mono<T> call) {
        return execute(operation, call, e -> new ExternalServiceException(SERVICE, e));
    }

    <T> Mono<T> execute(String operation, Mono<T> call,
                        Function<WebClientResponseException, RuntimeException> onClientError) {
        return metrics.record(ExternalCallMetrics.KEYCLOAK, operation, call)
                .onErrorMap(WebClientRequestException.class, e -> new ExternalServiceUnavailableException(SERVICE, e))
                .onErrorMap(WebClientResponseException.class, e -> {
                    if (e.getStatusCode().is4xxClientError()) {
                        return onClientError.apply(e);
                    }
                    return e.getStatusCode().is5xxServerError()
                            ? new ExternalServiceUnavailableException(SERVICE, e)
                            : new ExternalServiceException(SERVICE, e);
                });
    }
}
