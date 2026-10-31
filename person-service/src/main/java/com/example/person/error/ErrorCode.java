package com.example.person.error;

import org.springframework.http.HttpStatus;

import java.net.URI;

/**
 * Каталог ошибок person-service. Значение {@link #name()} уходит клиенту в поле {@code errorCode}.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "validation-error", "Ошибка валидации"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed-request", "Некорректный запрос"),
    COUNTRY_NOT_FOUND(HttpStatus.BAD_REQUEST, "country-not-found", "Некорректный запрос"),
    COUNTRY_MISMATCH(HttpStatus.BAD_REQUEST, "country-mismatch", "Некорректный запрос"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user-not-found", "Не найдено"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "email-already-exists", "Конфликт данных"),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "concurrent-update", "Конфликт данных"),
    VERSION_MISMATCH(HttpStatus.PRECONDITION_FAILED, "version-mismatch", "Версия не совпала"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Внутренняя ошибка");

    private static final String TYPE_PREFIX = "https://example.org/problems/";

    private final HttpStatus status;
    private final String slug;
    private final String title;

    ErrorCode(HttpStatus status, String slug, String title) {
        this.status = status;
        this.slug = slug;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return URI.create(TYPE_PREFIX + slug);
    }

    public String title() {
        return title;
    }
}
