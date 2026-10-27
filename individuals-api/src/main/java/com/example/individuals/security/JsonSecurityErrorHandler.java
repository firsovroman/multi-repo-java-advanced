package com.example.individuals.security;

import com.example.individuals.api.model.ErrorResponse;
import com.example.individuals.error.ErrorCode;
import com.example.individuals.error.ErrorResponseFactory;
import com.example.individuals.logging.LogContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Ответы 401/403 из Spring Security в формате {@link ErrorResponse}.
 */
@Component
public class JsonSecurityErrorHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ErrorResponseFactory errorResponseFactory;
    private final JsonMapper jsonMapper;

    public JsonSecurityErrorHandler(ErrorResponseFactory errorResponseFactory, JsonMapper jsonMapper) {
        this.errorResponseFactory = errorResponseFactory;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return write(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Требуется аутентификация");
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return write(exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Доступ запрещён");
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, ErrorCode code, String message) {
        LogContext.putErrorCode(exchange, code);
        ErrorResponse body = errorResponseFactory.create(exchange, status, code.name(), message, List.of());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.writeWith(Mono.fromSupplier(
                () -> response.bufferFactory().wrap(jsonMapper.writeValueAsBytes(body))));
    }
}
