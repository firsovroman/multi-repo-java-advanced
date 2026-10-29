package com.example.individuals.error;

import org.springframework.http.HttpStatus;

/**
 * Access token не содержит обязательного доменного идентификатора {@code user_uid} в корректном формате.
 */
public class InvalidAccessTokenException extends ApiException {

    public InvalidAccessTokenException() {
        super(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Access token не содержит корректный user_uid");
    }
}
