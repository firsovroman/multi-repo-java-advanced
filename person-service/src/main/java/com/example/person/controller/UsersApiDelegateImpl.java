package com.example.person.controller;

import com.example.person.api.UsersApi;
import com.example.person.api.UsersApiDelegate;
import com.example.person.api.model.CreateUserRequest;
import com.example.person.api.model.UpdateUserRequest;
import com.example.person.api.model.UserResponse;
import com.example.person.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.UUID;

/**
 * CRUD агрегата пользователя.
 */
@Component
public class UsersApiDelegateImpl implements UsersApiDelegate {

    private final UserService userService;

    public UsersApiDelegateImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ResponseEntity<UserResponse> createUser(CreateUserRequest request) {
        UserResponse user = userService.create(request);
        return ResponseEntity.created(URI.create(UsersApi.PATH_CREATE_USER + "/" + user.getId()))
                .eTag(user.getVersion().toString())
                .body(user);
    }

    @Override
    public ResponseEntity<UserResponse> getUserById(UUID id) {
        return withVersion(userService.getUser(id));
    }

    @Override
    public ResponseEntity<UserResponse> getUserByEmail(String email) {
        return withVersion(userService.getUserByEmail(email));
    }

    @Override
    public ResponseEntity<UserResponse> updateUser(UUID id, UpdateUserRequest request, String ifMatch) {
        return withVersion(userService.update(id, request, ifMatch));
    }

    @Override
    public ResponseEntity<Void> deleteUser(UUID id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<UserResponse> withVersion(UserResponse user) {
        return ResponseEntity.ok().eTag(user.getVersion().toString()).body(user);
    }
}
