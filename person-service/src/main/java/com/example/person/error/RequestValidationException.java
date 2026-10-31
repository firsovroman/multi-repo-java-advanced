package com.example.person.error;

import com.example.person.api.model.FieldViolation;

import java.util.List;

/**
 * Запрос корректен по контракту, но не подходит текущему состоянию агрегата.
 */
public class RequestValidationException extends ApiException {

    private final transient List<FieldViolation> violations;

    public RequestValidationException(String detail, List<FieldViolation> violations) {
        super(ErrorCode.VALIDATION_ERROR, detail);
        this.violations = violations;
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
