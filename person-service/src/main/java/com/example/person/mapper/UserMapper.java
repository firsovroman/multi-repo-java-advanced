package com.example.person.mapper;

import com.example.person.api.model.AddressResponse;
import com.example.person.api.model.IndividualResponse;
import com.example.person.api.model.PersonResponse;
import com.example.person.api.model.UserResponse;
import com.example.person.domain.AddressEntity;
import com.example.person.domain.CountryEntity;
import com.example.person.domain.IndividualEntity;
import com.example.person.domain.UserEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Отображение Entity → DTO. Связи ленивые, поэтому вызывается внутри транзакции.
 */
@Component
public class UserMapper {

    public UserResponse toUserResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.isFilled(),
                toUtc(user.getCreatedAt()), toUtc(user.getUpdatedAt()), user.getVersion())
                .address(toAddressResponse(user.getAddress()))
                .individual(toIndividualResponse(user.getIndividual()));
    }

    public PersonResponse toPersonResponse(UserEntity user) {
        return new PersonResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.isFilled())
                .createdAt(toUtc(user.getCreatedAt()));
    }

    private static AddressResponse toAddressResponse(AddressEntity address) {
        if (address == null) {
            return null;
        }
        CountryEntity country = address.getCountry();
        return new AddressResponse(address.getId(), country.getAlpha3(), country.getAlpha2(), address.getCity(),
                address.getAddressLine())
                .state(address.getState())
                .zipCode(address.getZipCode());
    }

    private static IndividualResponse toIndividualResponse(IndividualEntity individual) {
        if (individual == null) {
            return null;
        }
        return new IndividualResponse(individual.getId(), individual.getStatus())
                .passportNumber(individual.getPassportNumber())
                .phoneNumber(individual.getPhoneNumber())
                .verifiedAt(toUtc(individual.getVerifiedAt()))
                .archivedAt(toUtc(individual.getArchivedAt()));
    }

    private static OffsetDateTime toUtc(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
