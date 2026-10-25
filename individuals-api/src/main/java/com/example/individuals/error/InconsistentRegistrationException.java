package com.example.individuals.error;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Доменный пользователь создан в person-service, но регистрация в Keycloak не завершилась.
 * Частичный сбой не скрывается: клиент получает явный код ошибки, инцидент пишется в журнал.
 */
public class InconsistentRegistrationException extends ApiException {

    private final UUID userUid;

    public InconsistentRegistrationException(UUID userUid, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.REGISTRATION_INCONSISTENT,
                "Регистрация завершилась частично, обратитесь в поддержку", List.of("userUid: " + userUid), cause);
        this.userUid = userUid;
    }

    public UUID getUserUid() {
        return userUid;
    }
}
