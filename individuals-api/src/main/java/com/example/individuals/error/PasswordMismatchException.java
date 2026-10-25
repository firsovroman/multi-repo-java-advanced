package com.example.individuals.error;

import org.springframework.http.HttpStatus;

public class PasswordMismatchException extends ApiException {

    public PasswordMismatchException() {
        super(HttpStatus.BAD_REQUEST, ErrorCode.PASSWORD_MISMATCH, "Пароль и подтверждение не совпадают");
    }
}
