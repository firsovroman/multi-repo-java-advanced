package com.example.individuals.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

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

    public <T> T record(String timerName, String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder(timerName)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
