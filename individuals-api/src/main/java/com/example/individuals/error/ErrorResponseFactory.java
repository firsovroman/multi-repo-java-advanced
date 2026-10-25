package com.example.individuals.error;

import com.example.individuals.api.model.ErrorResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Собирает единый {@link ErrorResponse} с traceId текущего запроса.
 */
@Component
public class ErrorResponseFactory {

    private final Tracer tracer;

    public ErrorResponseFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    public ErrorResponse create(HttpStatusCode status, String error, String message, String path, List<String> details) {
        return new ErrorResponse(OffsetDateTime.now(ZoneOffset.UTC), path, status.value(), error, message, currentTraceId())
                .details(details);
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span != null ? span.context().traceId() : "";
    }
}
