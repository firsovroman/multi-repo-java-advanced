package com.example.individuals.error;

import com.example.individuals.api.model.ErrorResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Собирает единый {@link ErrorResponse} с путём и traceId текущего запроса.
 */
@Component
public class ErrorResponseFactory {

    private final Tracer tracer;

    public ErrorResponseFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    public ErrorResponse create(ServerWebExchange exchange, HttpStatusCode status, String error, String message,
                                List<String> details) {
        return new ErrorResponse(OffsetDateTime.now(ZoneOffset.UTC), exchange.getRequest().getPath().value(),
                status.value(), error, message, currentTraceId(exchange))
                .details(details);
    }

    /**
     * Спан HTTP-запроса берётся из наблюдения, привязанного к exchange: в реактивном конвейере
     * ThreadLocal трейсера на текущем потоке может быть не установлен.
     */
    private String currentTraceId(ServerWebExchange exchange) {
        Span span = ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .<TracingContext>map(context -> context.get(TracingContext.class))
                .map(TracingContext::getSpan)
                .or(() -> Optional.ofNullable(tracer.currentSpan()))
                .orElse(null);
        return span != null ? span.context().traceId() : "";
    }
}
