package com.example.person.error;

import com.example.person.api.model.FieldViolation;
import com.example.person.logging.LogContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.InvalidNullException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Все ошибки сервиса — {@code application/problem+json} с расширениями errorCode и traceId.
 * Единственное место записи ошибки в журнал: 4xx — WARN без стека, 5xx — ERROR со стеком.
 * В {@code detail} попадают только заранее заданные тексты: значения из запроса и внутренности остаются в журнале.
 */
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private static final String VALIDATION_DETAIL = "Запрос не прошёл проверку";
    private static final String MALFORMED_DETAIL = "Запрос не удалось разобрать";
    private static final String INTERNAL_DETAIL = "Внутренняя ошибка сервиса";

    private final Tracer tracer;

    public ProblemExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<Object> handleRequestValidation(RequestValidationException ex, WebRequest request) {
        return problem(ex, ex.errorCode(), ex.getMessage(), ex.violations(), request);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
        return problem(ex, ex.errorCode(), ex.getMessage(), null, request);
    }

    /**
     * Сгенерированные интерфейсы помечены {@code @Validated}, поэтому параметры запроса проверяет AOP-прокси,
     * а не сам Spring MVC.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(parameterName(violation), violation.getMessage()))
                .toList();
        return problem(ex, ErrorCode.VALIDATION_ERROR, VALIDATION_DETAIL, violations, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, WebRequest request) {
        return problem(ex, ErrorCode.CONCURRENT_UPDATE, "Пользователь был изменён параллельно, повторите запрос",
                null, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        return problem(ex, ErrorCode.INTERNAL_ERROR, INTERNAL_DETAIL, null, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(ProblemExceptionHandler::violation)
                .toList();
        return problem(ex, ErrorCode.VALIDATION_ERROR, VALIDATION_DETAIL, violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(), message(error))))
                .toList();
        return problem(ex, ErrorCode.VALIDATION_ERROR, VALIDATION_DETAIL, violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        List<FieldViolation> violations = List.of(new FieldViolation(ex.getParameterName(), "обязательный параметр"));
        return problem(ex, ErrorCode.VALIDATION_ERROR, VALIDATION_DETAIL, violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        if (ex.getCause() instanceof InvalidNullException nullField) {
            String field = nullField.getPath().stream()
                    .map(reference -> reference.getPropertyName())
                    .collect(Collectors.joining("."));
            return problem(ex, ErrorCode.VALIDATION_ERROR, VALIDATION_DETAIL,
                    List.of(new FieldViolation(field, "не может быть null")), request);
        }
        return problem(ex, ErrorCode.MALFORMED_REQUEST, MALFORMED_DETAIL, null, request);
    }

    /**
     * Сюда приходят все остальные исключения Spring MVC. Ошибки из каталога получают его код; статусы вне каталога
     * (неизвестный путь, неподдерживаемый метод) — имя статуса, чтобы errorCode был у каждого ответа.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            return problem(ex, ErrorCode.INTERNAL_ERROR, INTERNAL_DETAIL, null, request);
        }
        if (status.value() == HttpStatus.BAD_REQUEST.value()) {
            return problem(ex, ErrorCode.MALFORMED_REQUEST, MALFORMED_DETAIL, null, request);
        }
        ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(status);
        String errorCode = HttpStatus.valueOf(status.value()).name();
        decorate(problem, errorCode);
        logHandled(ex, errorCode, status);
        return createResponseEntity(problem, headers, status, request);
    }

    private ResponseEntity<Object> problem(Exception ex, ErrorCode code, String detail,
                                           @Nullable List<FieldViolation> violations, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        if (violations != null) {
            problem.setProperty("errors", violations);
        }
        decorate(problem, code.name());
        logHandled(ex, code.name(), code.status());
        return createResponseEntity(problem, new HttpHeaders(), code.status(), request);
    }

    private void decorate(ProblemDetail problem, String errorCode) {
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("traceId", currentTraceId());
        LogContext.errorCode(errorCode);
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span != null ? span.context().traceId() : "";
    }

    private static void logHandled(Exception ex, String errorCode, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            log.error("Request failed: {}", errorCode, ex);
        } else {
            log.warn("Request rejected: {} ({})", errorCode, ex.getClass().getSimpleName());
        }
    }

    private static String parameterName(ConstraintViolation<?> violation) {
        String name = null;
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }

    private static FieldViolation violation(FieldError error) {
        return new FieldViolation(error.getField(), message(error));
    }

    private static String message(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "недопустимое значение";
    }
}
