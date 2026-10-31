package com.example.person.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Прикладные метрики: счётчики {@code person_users_*_total} и таймер
 * {@code person_users_operation_seconds} с тегами operation и outcome.
 */
@Component
public class UserMetrics {

    private static final String OPERATION_TIMER = "person.users.operation";

    // ÐÐµ "created": ÑÑÑÑÐ¸ÐºÑ _created Ð·Ð°ÑÐµÐ·ÐµÑÐ²Ð¸ÑÐ¾Ð²Ð°Ð½ OpenMetrics, ÐºÐ»Ð¸ÐµÐ½Ñ Prometheus ÑÑÐµÐ¶ÐµÑ ÐµÐ³Ð¾ Ð¸ Ð¿Ð¾Ð»ÑÑÐ¸ÑÑÑ person_users_total
    private static final String CREATED = "person.users.creations";

    private final MeterRegistry registry;
    private final Counter created;
    private final Counter updated;
    private final Counter deleted;
    private final Counter emailConflicts;

    public UserMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.created = registry.counter(CREATED);
        this.updated = registry.counter("person.users.updated");
        this.deleted = registry.counter("person.users.deleted");
        this.emailConflicts = registry.counter("person.users.email_conflicts");
    }

    public void userCreated() {
        created.increment();
    }

    public void userUpdated() {
        updated.increment();
    }

    public void userDeleted() {
        deleted.increment();
    }

    public void emailConflict() {
        emailConflicts.increment();
    }

    public <T> T record(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } finally {
            sample.stop(Timer.builder(OPERATION_TIMER)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
