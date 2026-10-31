package com.example.person.logging;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Поля журнала, известные только внутри обработки запроса. Живут в MDC до конца запроса,
 * их снимает {@link RequestLoggingFilter}. Имена совпадают с individuals-api.
 */
public final class LogContext {

    public static final String USER_UID = "userUid";
    public static final String ERROR_CODE = "errorCode";

    private LogContext() {
    }

    public static void userUid(UUID userUid) {
        MDC.put(USER_UID, userUid.toString());
    }

    public static void errorCode(String errorCode) {
        MDC.put(ERROR_CODE, errorCode);
    }

    static void clear() {
        MDC.remove(USER_UID);
        MDC.remove(ERROR_CODE);
    }
}
