package com.example.individuals.gateway;

import com.example.individuals.error.EmailAlreadyExistsException;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.metrics.ExternalCallMetrics;
import com.example.person.client.api.PersonsApi;
import com.example.person.client.model.PersonRegistrationRequest;
import com.example.person.client.model.PersonRegistrationResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Шлюз к person-service — источнику доменной истины о пользователе.
 */
@Component
public class PersonGateway {

    static final String SERVICE = "person-service";

    private final PersonsApi personsApi;
    private final ExternalCallMetrics metrics;

    public PersonGateway(PersonsApi personsApi, ExternalCallMetrics metrics) {
        this.personsApi = personsApi;
        this.metrics = metrics;
    }

    /**
     * Создаёт доменного пользователя и возвращает его {@code userUid}.
     *
     * @throws EmailAlreadyExistsException если пользователь с таким email уже есть
     */
    public UUID registerPerson(String email, String firstName, String lastName) {
        PersonRegistrationRequest request = new PersonRegistrationRequest(email, firstName, lastName);
        PersonRegistrationResponse response = call("register_person", () -> personsApi.registerPerson(request));
        if (response == null || response.getUserUid() == null) {
            throw new ExternalServiceException(SERVICE, new IllegalStateException("person-service returned no userUid"));
        }
        return response.getUserUid();
    }

    private <T> T call(String operation, Supplier<T> call) {
        try {
            return metrics.record(ExternalCallMetrics.PERSON_SERVICE, operation, call);
        } catch (HttpClientErrorException.Conflict e) {
            throw new EmailAlreadyExistsException();
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new ExternalServiceUnavailableException(SERVICE, e);
        } catch (RestClientResponseException e) {
            throw new ExternalServiceException(SERVICE, e);
        }
    }
}
