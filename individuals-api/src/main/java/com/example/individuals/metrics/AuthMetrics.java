package com.example.individuals.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Прикладные счётчики аутентификации. В Prometheus экспортируются с суффиксом {@code _total}:
 * {@code auth_registration_total}, {@code auth_login_failure_total} и т. д.
 */
@Component
public class AuthMetrics {

    private final Counter registration;
    private final Counter registrationSuccess;
    private final Counter registrationFailure;
    private final Counter login;
    private final Counter loginFailure;
    private final Counter refresh;

    public AuthMetrics(MeterRegistry registry) {
        this.registration = counter(registry, "auth.registration", "Попытки регистрации");
        this.registrationSuccess = counter(registry, "auth.registration.success", "Успешные регистрации");
        this.registrationFailure = counter(registry, "auth.registration.failure", "Неуспешные регистрации");
        this.login = counter(registry, "auth.login", "Попытки входа");
        this.loginFailure = counter(registry, "auth.login.failure", "Неуспешные входы");
        this.refresh = counter(registry, "auth.refresh", "Обновления токена");
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public void registrationAttempt() {
        registration.increment();
    }

    public void registrationSuccess() {
        registrationSuccess.increment();
    }

    public void registrationFailure() {
        registrationFailure.increment();
    }

    public void loginAttempt() {
        login.increment();
    }

    public void loginFailure() {
        loginFailure.increment();
    }

    public void refresh() {
        refresh.increment();
    }
}
