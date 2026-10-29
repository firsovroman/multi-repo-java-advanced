package com.example.individuals.unit;

import com.example.individuals.error.EmailAlreadyExistsException;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.gateway.PersonGateway;
import com.example.individuals.metrics.ExternalCallMetrics;
import com.example.person.client.api.PersonsApi;
import com.example.person.client.model.PersonRegistrationRequest;
import com.example.person.client.model.PersonRegistrationResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonGatewayTest {

    @Mock
    private PersonsApi personsApi;

    private SimpleMeterRegistry meterRegistry;
    private PersonGateway gateway;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        gateway = new PersonGateway(personsApi, new ExternalCallMetrics(meterRegistry));
    }

    @Test
    void registerPersonReturnsUserUidAndRecordsTimer() {
        UUID userUid = UUID.randomUUID();
        PersonRegistrationRequest expected = new PersonRegistrationRequest("user@example.com", "Ivan", "Ivanov");
        when(personsApi.registerPerson(any())).thenAnswer(invocation -> invocation.<Mono<PersonRegistrationRequest>>getArgument(0)
                .filter(expected::equals)
                .map(request -> new PersonRegistrationResponse(userUid)));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectNext(userUid)
                .verifyComplete();
        assertThat(meterRegistry.get(ExternalCallMetrics.PERSON_SERVICE)
                .tag("operation", "register_person").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void conflictIsTranslatedToEmailAlreadyExists() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.error(responseError(HttpStatus.CONFLICT)));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(EmailAlreadyExistsException.class)
                .verify();
        assertThat(meterRegistry.get(ExternalCallMetrics.PERSON_SERVICE).tag("outcome", "error").timer().count())
                .isEqualTo(1);
    }

    @Test
    void networkErrorIsTranslatedToUnavailable() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.error(new WebClientRequestException(
                new ConnectException("Connection refused"), HttpMethod.POST, URI.create("http://person-service"),
                HttpHeaders.EMPTY)));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(ExternalServiceUnavailableException.class)
                .verify();
    }

    @Test
    void serverErrorIsTranslatedToUnavailable() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.error(responseError(HttpStatus.INTERNAL_SERVER_ERROR)));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(ExternalServiceUnavailableException.class)
                .verify();
    }

    @Test
    void unexpectedClientErrorIsTranslatedToExternalServiceError() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.error(responseError(HttpStatus.BAD_REQUEST)));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void responseWithoutUserUidIsExternalServiceError() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.just(new PersonRegistrationResponse()));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void malformedResponseBodyIsExternalServiceError() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.error(new DecodingException("JSON decoding error")));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    @Test
    void unexpectedContentTypeIsExternalServiceError() {
        when(personsApi.registerPerson(any())).thenReturn(Mono.error(new UnsupportedMediaTypeException(
                MediaType.TEXT_HTML, List.of(MediaType.APPLICATION_JSON))));

        StepVerifier.create(gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .expectError(ExternalServiceException.class)
                .verify();
    }

    private static WebClientResponseException responseError(HttpStatus status) {
        return WebClientResponseException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], null, null);
    }
}
