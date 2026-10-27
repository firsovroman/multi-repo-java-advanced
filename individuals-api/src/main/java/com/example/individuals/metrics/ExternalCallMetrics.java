package com.example.individuals.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Таймеры вызовов внешних систем: {@code external_keycloak_requests_seconds}
 * и {@code external_person_service_requests_seconds} с тегами operation и outcome.
 */
@Component
public class ExternalCallMetrics {

    public static final String KEYCLOAK = "external.keycloak.requests";
    public static final String PERSON_SERVICE = "external.person.service.requests";

    private final MeterRegistry registry;

    public ExternalCallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Замер начинается при подписке и заканчивается завершением или ошибкой вызова.
     */
    public <T> Mono<T> record(String timerName, String operation, Mono<T> call) {
        return Mono.defer(() -> {
            Timer.Sample sample = Timer.start(registry);
            return call
                    .doOnSuccess(result -> stop(sample, timerName, operation, "success"))
                    .doOnError(e -> stop(sample, timerName, operation, "error"));
        });
    }

    private void stop(Timer.Sample sample, String timerName, String operation, String outcome) {
        sample.stop(Timer.builder(timerName)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry));
    }
}
