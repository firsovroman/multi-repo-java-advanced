package com.example.individuals.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Внешняя система недоступна: сетевой сбой, таймаут или ответ 5xx.
 */
public class ExternalServiceUnavailableException extends ApiException {

    public ExternalServiceUnavailableException(String service, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.EXTERNAL_SERVICE_UNAVAILABLE,
                "Внешний сервис временно недоступен", List.of("service: " + service), cause);
    }
}
