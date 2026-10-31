package com.example.person.error;

/**
 * Ожидаемая ошибка предметной области. Текст {@code detail} уходит клиенту, поэтому персональных данных в нём нет.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
