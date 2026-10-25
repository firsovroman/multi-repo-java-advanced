package com.example.individuals.logging;

import com.example.individuals.error.ErrorCode;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Поля журнала уровня запроса. Попадают в MDC, а значит и в каждую JSON-запись до конца запроса;
 * очищаются в {@link RequestLoggingFilter}.
 */
public final class LogContext {

    public static final String USER_UID = "userUid";
    public static final String ERROR_CODE = "errorCode";

    private LogContext() {
    }

    public static void putUserUid(UUID userUid) {
        if (userUid != null) {
            MDC.put(USER_UID, userUid.toString());
        }
    }

    public static void putErrorCode(String errorCode) {
        MDC.put(ERROR_CODE, errorCode);
    }

    public static void putErrorCode(ErrorCode errorCode) {
        putErrorCode(errorCode.name());
    }

    public static void clear() {
        MDC.remove(USER_UID);
        MDC.remove(ERROR_CODE);
    }
}
