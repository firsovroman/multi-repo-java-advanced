package com.example.individuals.security;

import com.example.individuals.api.model.ErrorResponse;
import com.example.individuals.error.ErrorCode;
import com.example.individuals.error.ErrorResponseFactory;
import com.example.individuals.logging.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

/**
 * Ответы 401/403 из Spring Security в формате {@link ErrorResponse}.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ErrorResponseFactory errorResponseFactory;
    private final JsonMapper jsonMapper;

    public JsonSecurityErrorHandler(ErrorResponseFactory errorResponseFactory, JsonMapper jsonMapper) {
        this.errorResponseFactory = errorResponseFactory;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Требуется аутентификация");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Доступ запрещён");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, ErrorCode code,
                       String message) throws IOException {
        LogContext.putErrorCode(code);
        ErrorResponse body = errorResponseFactory.create(status, code.name(), message, request.getRequestURI(), List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
