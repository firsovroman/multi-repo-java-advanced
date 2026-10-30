package com.example.individuals.gateway;

import com.example.individuals.error.EmailAlreadyExistsException;
import com.example.individuals.error.ExternalServiceException;
import com.example.individuals.error.ExternalServiceUnavailableException;
import com.example.individuals.metrics.ExternalCallMetrics;
import com.example.person.client.api.PersonsApi;
import com.example.person.client.model.PersonRegistrationRequest;
import com.example.person.client.model.PersonRegistrationResponse;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.UUID;

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
     * Создаёт доменного пользователя и возвращает его {@code userId}.
     * Ошибка {@link EmailAlreadyExistsException} — если пользователь с таким email уже есть.
     */
    public Mono<UUID> registerPerson(String email, String firstName, String lastName) {
        PersonRegistrationRequest request = new PersonRegistrationRequest(email, firstName, lastName);
        return call("register_person", Mono.defer(() -> personsApi.registerPerson(Mono.just(request))))
                .mapNotNull(PersonRegistrationResponse::getUserId)
                .switchIfEmpty(Mono.error(() -> new ExternalServiceException(SERVICE,
                        new IllegalStateException("person-service returned no userId"))));
    }

    private <T> Mono<T> call(String operation, Mono<T> call) {
        return metrics.record(ExternalCallMetrics.PERSON_SERVICE, operation, call)
                .onErrorMap(WebClientResponseException.Conflict.class, e -> new EmailAlreadyExistsException())
                .onErrorMap(PersonGateway::isUnavailable, e -> new ExternalServiceUnavailableException(SERVICE, e))
                .onErrorMap(WebClientResponseException.class, e -> new ExternalServiceException(SERVICE, e))
                .onErrorMap(PersonGateway::isUnreadableResponse, e -> new ExternalServiceException(SERVICE, e));
    }

    /**
     * Ответ получен, но тело не разбирается: невалидный JSON или неожиданный Content-Type.
     */
    private static boolean isUnreadableResponse(Throwable e) {
        return e instanceof DecodingException || e instanceof UnsupportedMediaTypeException;
    }

    private static boolean isUnavailable(Throwable e) {
        return e instanceof WebClientRequestException
                || e instanceof WebClientResponseException response && response.getStatusCode().is5xxServerError();
    }
}
