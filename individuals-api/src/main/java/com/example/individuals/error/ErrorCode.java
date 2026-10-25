package com.example.individuals.error;

/**
 * Коды бизнес-ошибок: возвращаются в поле {@code error} ответа и пишутся в журнал.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    PASSWORD_MISMATCH,
    UNAUTHORIZED,
    FORBIDDEN,
    INVALID_CREDENTIALS,
    INVALID_REFRESH_TOKEN,
    CONFLICT,
    REGISTRATION_INCONSISTENT,
    EXTERNAL_SERVICE_ERROR,
    EXTERNAL_SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
