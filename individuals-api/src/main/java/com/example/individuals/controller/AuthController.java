package com.example.individuals.controller;

import com.example.individuals.api.AuthApi;
import com.example.individuals.api.model.CurrentUserResponse;
import com.example.individuals.api.model.LoginRequest;
import com.example.individuals.api.model.RefreshTokenRequest;
import com.example.individuals.api.model.RegistrationRequest;
import com.example.individuals.api.model.TokenResponse;
import com.example.individuals.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Реализация контракта individuals-api.yaml. Только принимает и возвращает DTO, логика — в {@link AuthService}.
 */
@RestController
public class AuthController implements AuthApi {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> register(Mono<RegistrationRequest> registrationRequest,
                                                        ServerWebExchange exchange) {
        return registrationRequest.flatMap(authService::register)
                .map(tokens -> ResponseEntity.status(HttpStatus.CREATED).body(tokens));
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> login(Mono<LoginRequest> loginRequest, ServerWebExchange exchange) {
        return loginRequest.flatMap(authService::login).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TokenResponse>> refreshToken(Mono<RefreshTokenRequest> refreshTokenRequest,
                                                            ServerWebExchange exchange) {
        return refreshTokenRequest.flatMap(authService::refresh).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<CurrentUserResponse>> getCurrentUser(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .flatMap(authService::currentUser)
                .map(ResponseEntity::ok);
    }
}
