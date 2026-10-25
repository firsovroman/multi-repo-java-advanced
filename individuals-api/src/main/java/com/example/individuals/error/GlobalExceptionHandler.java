package com.example.individuals.error;

import com.example.individuals.api.model.ErrorResponse;
import com.example.individuals.logging.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Переводит исключения в единый {@link ErrorResponse}. Ошибки Spring MVC (404, 405, 415 и т. п.)
 * тоже возвращаются в этом формате вместо ProblemDetail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorResponseFactory errorResponseFactory;

    public GlobalExceptionHandler(ErrorResponseFactory errorResponseFactory) {
        this.errorResponseFactory = errorResponseFactory;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        LogContext.putErrorCode(ex.getErrorCode());
        if (ex.getStatus().is5xxServerError()) {
            log.atWarn().setCause(ex).log("Request failed: {}", ex.getMessage());
        } else {
            log.atInfo().log("Request rejected: {}", ex.getMessage());
        }
        return respond(ex.getStatus(), ex.getErrorCode().name(), ex.getMessage(), request.getRequestURI(), ex.getDetails());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        LogContext.putErrorCode(ErrorCode.INTERNAL_ERROR);
        log.atError().setCause(ex).log("Unexpected error");
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "Внутренняя ошибка сервиса", request.getRequestURI(), List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return validationError(details, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return validationError(List.of("Тело запроса отсутствует или не является корректным JSON"), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String error = statusCode instanceof HttpStatus status ? status.name() : String.valueOf(statusCode.value());
        LogContext.putErrorCode(error);
        ErrorResponse response = errorResponseFactory.create(statusCode, error, ex.getMessage(), path(request), List.of());
        return ResponseEntity.status(statusCode).headers(headers).body(response);
    }

    private ResponseEntity<Object> validationError(List<String> details, WebRequest request) {
        LogContext.putErrorCode(ErrorCode.VALIDATION_ERROR);
        ErrorResponse response = errorResponseFactory.create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.name(),
                "Ошибка валидации запроса", path(request), details);
        return ResponseEntity.badRequest().body(response);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String error, String message, String path,
                                                  List<String> details) {
        return ResponseEntity.status(status).body(errorResponseFactory.create(status, error, message, path, details));
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest ? servletRequest.getRequest().getRequestURI() : "";
    }
}
