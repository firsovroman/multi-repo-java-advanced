package com.example.individuals.error;

import com.example.individuals.api.model.ErrorResponse;
import com.example.individuals.logging.LogContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Переводит исключения в единый {@link ErrorResponse}. Ошибки Spring WebFlux (404, 405, 415 и т. п.)
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
    Mono<ResponseEntity<ErrorResponse>> handleApiException(ApiException ex, ServerWebExchange exchange) {
        LogContext.putErrorCode(exchange, ex.getErrorCode());
        if (ex.getStatus().is5xxServerError()) {
            log.atWarn().setCause(ex).addKeyValue(LogContext.ERROR_CODE, ex.getErrorCode())
                    .log("Request failed: {}", ex.getMessage());
        } else {
            log.atInfo().addKeyValue(LogContext.ERROR_CODE, ex.getErrorCode())
                    .log("Request rejected: {}", ex.getMessage());
        }
        return respond(exchange, ex.getStatus(), ex.getErrorCode().name(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(Exception.class)
    Mono<ResponseEntity<ErrorResponse>> handleUnexpected(Exception ex, ServerWebExchange exchange) {
        LogContext.putErrorCode(exchange, ErrorCode.INTERNAL_ERROR);
        log.atError().setCause(ex).addKeyValue(LogContext.ERROR_CODE, ErrorCode.INTERNAL_ERROR).log("Unexpected error");
        return respond(exchange, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.name(),
                "Внутренняя ошибка сервиса", List.of());
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleWebExchangeBindException(WebExchangeBindException ex, HttpHeaders headers,
                                                                          HttpStatusCode status, ServerWebExchange exchange) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return validationError(details, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(ServerWebInputException ex, HttpHeaders headers,
                                                                         HttpStatusCode status, ServerWebExchange exchange) {
        return validationError(List.of("Тело запроса отсутствует или не является корректным JSON"), exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                                   @Nullable HttpHeaders headers, HttpStatusCode statusCode,
                                                                   ServerWebExchange exchange) {
        String error = statusCode instanceof HttpStatus status ? status.name() : String.valueOf(statusCode.value());
        LogContext.putErrorCode(exchange, error);
        ErrorResponse response = errorResponseFactory.create(exchange, statusCode, error, ex.getMessage(), List.of());
        return Mono.just(ResponseEntity.status(statusCode).headers(headers).body(response));
    }

    private Mono<ResponseEntity<Object>> validationError(List<String> details, ServerWebExchange exchange) {
        LogContext.putErrorCode(exchange, ErrorCode.VALIDATION_ERROR);
        ErrorResponse response = errorResponseFactory.create(exchange, HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_ERROR.name(), "Ошибка валидации запроса", details);
        return Mono.just(ResponseEntity.badRequest().body(response));
    }

    private Mono<ResponseEntity<ErrorResponse>> respond(ServerWebExchange exchange, HttpStatus status, String error,
                                                        String message, List<String> details) {
        return Mono.just(ResponseEntity.status(status)
                .body(errorResponseFactory.create(exchange, status, error, message, details)));
    }
}
