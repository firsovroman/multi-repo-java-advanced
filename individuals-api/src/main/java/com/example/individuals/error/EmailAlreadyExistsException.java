package com.example.individuals.error;

import org.springframework.http.HttpStatus;

public class EmailAlreadyExistsException extends ApiException {

    public EmailAlreadyExistsException() {
        super(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Пользователь с таким email уже существует");
    }
}
