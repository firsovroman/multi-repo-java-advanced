package com.example.individuals.logging;

import com.example.individuals.error.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * Поля журнала уровня запроса: {@code userUid} и {@code errorCode}.
 * <p>
 * В реактивном конвейере запрос не привязан к потоку, поэтому MDC для них не подходит: объект создаётся
 * в {@link RequestLoggingFilter} и доступен через атрибут exchange (обработчики ошибок) и Reactor Context
 * (сервисный слой). Поля попадают в итоговую запись журнала запроса.
 */
public final class LogContext {

    public static final String USER_UID = "userUid";
    public static final String ERROR_CODE = "errorCode";

    private static final String KEY = LogContext.class.getName();

    private volatile @Nullable UUID userUid;
    private volatile @Nullable String errorCode;

    private LogContext() {
    }

    /**
     * Создаёт контекст журнала для запроса и сохраняет его в атрибутах exchange.
     */
    public static LogContext attach(ServerWebExchange exchange) {
        LogContext logContext = new LogContext();
        exchange.getAttributes().put(KEY, logContext);
        return logContext;
    }

    /**
     * Делает контекст доступным для {@link #putUserUid(UUID)} в Reactor Context.
     */
    public Context writeTo(Context context) {
        return context.put(KEY, this);
    }

    /**
     * Записывает {@code userUid} в контекст журнала текущего запроса (если он есть).
     */
    public static Mono<Void> putUserUid(@Nullable UUID userUid) {
        return Mono.deferContextual(context -> {
            if (userUid != null) {
                context.<LogContext>getOrEmpty(KEY).ifPresent(logContext -> logContext.userUid = userUid);
            }
            return Mono.empty();
        });
    }

    public static void putErrorCode(ServerWebExchange exchange, String errorCode) {
        LogContext logContext = exchange.getAttribute(KEY);
        if (logContext != null) {
            logContext.errorCode = errorCode;
        }
    }

    public static void putErrorCode(ServerWebExchange exchange, ErrorCode errorCode) {
        putErrorCode(exchange, errorCode.name());
    }

    public @Nullable UUID userUid() {
        return userUid;
    }

    public @Nullable String errorCode() {
        return errorCode;
    }
}
