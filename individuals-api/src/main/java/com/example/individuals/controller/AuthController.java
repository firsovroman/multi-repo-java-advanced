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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<TokenResponse> register(RegistrationRequest registrationRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(registrationRequest));
    }

    @Override
    public ResponseEntity<TokenResponse> login(LoginRequest loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }

    @Override
    public ResponseEntity<TokenResponse> refreshToken(RefreshTokenRequest refreshTokenRequest) {
        return ResponseEntity.ok(authService.refresh(refreshTokenRequest));
    }

    @Override
    public ResponseEntity<CurrentUserResponse> getCurrentUser() {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(authService.currentUser(jwt));
    }
}
