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
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(personsApi.registerPerson(new PersonRegistrationRequest("user@example.com", "Ivan", "Ivanov")))
                .thenReturn(new PersonRegistrationResponse(userUid));

        assertThat(gateway.registerPerson("user@example.com", "Ivan", "Ivanov")).isEqualTo(userUid);
        assertThat(meterRegistry.get(ExternalCallMetrics.PERSON_SERVICE)
                .tag("operation", "register_person").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void conflictIsTranslatedToEmailAlreadyExists() {
        when(personsApi.registerPerson(any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, null, null));

        assertThatThrownBy(() -> gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .isInstanceOf(EmailAlreadyExistsException.class);
        assertThat(meterRegistry.get(ExternalCallMetrics.PERSON_SERVICE).tag("outcome", "error").timer().count())
                .isEqualTo(1);
    }

    @Test
    void networkErrorIsTranslatedToUnavailable() {
        when(personsApi.registerPerson(any())).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void serverErrorIsTranslatedToUnavailable() {
        when(personsApi.registerPerson(any()))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Error", null, null, null));

        assertThatThrownBy(() -> gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .isInstanceOf(ExternalServiceUnavailableException.class);
    }

    @Test
    void unexpectedClientErrorIsTranslatedToExternalServiceError() {
        when(personsApi.registerPerson(any()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        assertThatThrownBy(() -> gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void responseWithoutUserUidIsExternalServiceError() {
        when(personsApi.registerPerson(any())).thenReturn(new PersonRegistrationResponse());

        assertThatThrownBy(() -> gateway.registerPerson("user@example.com", "Ivan", "Ivanov"))
                .isInstanceOf(ExternalServiceException.class);
    }
}
