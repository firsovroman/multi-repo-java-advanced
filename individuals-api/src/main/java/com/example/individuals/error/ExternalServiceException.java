package com.example.individuals.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Внешняя система ответила ошибкой, которую individuals-api не может трактовать для клиента.
 */
public class ExternalServiceException extends ApiException {

    public ExternalServiceException(String service, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Внешний сервис вернул ошибку", List.of("service: " + service), cause);
    }
}
