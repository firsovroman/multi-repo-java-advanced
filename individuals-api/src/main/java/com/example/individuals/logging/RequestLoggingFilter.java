package com.example.individuals.logging;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.context.ContextView;

/**
 * Журнал запросов: одна JSON-запись на запрос с путём, методом, кодом ответа, длительностью и полями
 * {@link LogContext}.
 * <p>
 * Наблюдение HTTP-запроса открывается до всех WebFilter, а фильтр стоит до Spring Security,
 * поэтому видит и ответы 401/403. traceId/spanId попадают в MDC из Reactor Context на время записи.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        long start = System.nanoTime();
        LogContext logContext = LogContext.attach(exchange);
        return Mono.deferContextual(context -> chain.filter(exchange)
                        .doFinally(signal -> logRequest(exchange, logContext, context, signal, start)))
                .contextWrite(logContext::writeTo);
    }

    private static void logRequest(ServerWebExchange exchange, LogContext logContext, ContextView context,
                                   SignalType signal, long start) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        int status = status(exchange.getResponse().getStatusCode(), signal);
        try (ContextSnapshot.Scope ignored = SNAPSHOTS.setThreadLocalsFrom(context)) {
            LoggingEventBuilder event = log.atInfo()
                    .addKeyValue("http.path", path)
                    .addKeyValue("http.method", method)
                    .addKeyValue("http.status", status)
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000);
            if (logContext.userUid() != null) {
                event = event.addKeyValue(LogContext.USER_UID, logContext.userUid());
            }
            if (logContext.errorCode() != null) {
                event = event.addKeyValue(LogContext.ERROR_CODE, logContext.errorCode());
            }
            event.log("HTTP {} {} -> {}", method, path, status);
        }
    }

    /**
     * Ошибка, не обработанная внутри цепочки, превращается в ответ уже после фильтра — считаем её 500.
     */
    private static int status(HttpStatusCode statusCode, SignalType signal) {
        if (statusCode != null) {
            return statusCode.value();
        }
        return signal == SignalType.ON_ERROR ? HttpStatus.INTERNAL_SERVER_ERROR.value() : HttpStatus.OK.value();
    }
}
