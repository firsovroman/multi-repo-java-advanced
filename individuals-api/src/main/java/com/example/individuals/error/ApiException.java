package com.example.individuals.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Базовое прикладное исключение: несёт HTTP-статус, код бизнес-ошибки и детали для клиента.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode errorCode;
    private final List<String> details;

    protected ApiException(HttpStatus status, ErrorCode errorCode, String message) {
        this(status, errorCode, message, List.of(), null);
    }

    protected ApiException(HttpStatus status, ErrorCode errorCode, String message, List<String> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }
}
