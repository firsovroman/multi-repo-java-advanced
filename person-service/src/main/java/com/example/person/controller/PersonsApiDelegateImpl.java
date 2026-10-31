package com.example.person.controller;

import com.example.person.api.PersonsApiDelegate;
import com.example.person.api.model.PersonRegistrationRequest;
import com.example.person.api.model.PersonRegistrationResponse;
import com.example.person.api.model.PersonResponse;
import com.example.person.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Внутренний контракт для individuals-api.
 */
@Component
public class PersonsApiDelegateImpl implements PersonsApiDelegate {

    private final UserService userService;

    public PersonsApiDelegateImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ResponseEntity<PersonRegistrationResponse> registerPerson(PersonRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new PersonRegistrationResponse(userService.register(request)));
    }

    @Override
    public ResponseEntity<PersonResponse> getPersonByUserUid(UUID userUid) {
        return ResponseEntity.ok(userService.getPerson(userUid));
    }

    @Override
    public ResponseEntity<PersonResponse> getPersonByEmail(String email) {
        return ResponseEntity.ok(userService.getPersonByEmail(email));
    }
}
