package com.example.person.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Журнал запросов: одна JSON-запись на запрос с путём, методом, кодом ответа и длительностью.
 * Фильтр стоит внутри наблюдения HTTP-запроса, поэтому traceId/spanId к моменту записи ещё в MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.atInfo()
                    .addKeyValue("http.path", request.getRequestURI())
                    .addKeyValue("http.method", request.getMethod())
                    .addKeyValue("http.status", response.getStatus())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .log("HTTP {} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
            LogContext.clear();
        }
    }
}
