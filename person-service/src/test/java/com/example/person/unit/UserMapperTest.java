package com.example.person.unit;

import com.example.person.api.model.PersonResponse;
import com.example.person.api.model.UserResponse;
import com.example.person.domain.AddressEntity;
import com.example.person.domain.CountryEntity;
import com.example.person.domain.IndividualEntity;
import com.example.person.domain.UserEntity;
import com.example.person.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UT-PS-009: все поля переносятся, null-даты сохраняются.
 */
class UserMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-08T10:15:30Z");

    private final UserMapper mapper = new UserMapper();

    @Test
    void mapsWholeAggregate() {
        UserEntity user = user();
        AddressEntity address = new AddressEntity(UUID.randomUUID(), CREATED_AT);
        address.setCountry(new CountryEntity(1, "Russia", "RU", "RUS"));
        address.setCity("Moscow");
        address.setState("Moscow");
        address.setZipCode("101000");
        address.setAddressLine("Tverskaya 1");
        user.setAddress(address);
        IndividualEntity individual = new IndividualEntity(UUID.randomUUID(), user);
        individual.setPassportNumber("4510123456");
        individual.setPhoneNumber("+79991234567");
        user.setIndividual(individual);

        UserResponse response = mapper.toUserResponse(user);

        assertThat(response.getId()).isEqualTo(user.getId());
        assertThat(response.getEmail()).isEqualTo("user@example.com");
        assertThat(response.getFirstName()).isEqualTo("Ivan");
        assertThat(response.getLastName()).isEqualTo("Ivanov");
        assertThat(response.getCreatedAt().toInstant()).isEqualTo(CREATED_AT);
        assertThat(response.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.getUpdatedAt().toInstant()).isEqualTo(CREATED_AT);

        assertThat(response.getAddress().getId()).isEqualTo(address.getId());
        assertThat(response.getAddress().getCountryAlpha3()).isEqualTo("RUS");
        assertThat(response.getAddress().getCountryAlpha2()).isEqualTo("RU");
        assertThat(response.getAddress().getCity()).isEqualTo("Moscow");
        assertThat(response.getAddress().getState()).isEqualTo("Moscow");
        assertThat(response.getAddress().getZipCode()).isEqualTo("101000");
        assertThat(response.getAddress().getAddressLine()).isEqualTo("Tverskaya 1");

        assertThat(response.getIndividual().getId()).isEqualTo(individual.getId());
        assertThat(response.getIndividual().getPassportNumber()).isEqualTo("4510123456");
        assertThat(response.getIndividual().getPhoneNumber()).isEqualTo("+79991234567");
        assertThat(response.getIndividual().getStatus()).isEqualTo("NEW");
        assertThat(response.getIndividual().getVerifiedAt()).isNull();
        assertThat(response.getIndividual().getArchivedAt()).isNull();
    }

    @Test
    void userWithoutNestedEntities() {
        UserResponse response = mapper.toUserResponse(user());

        assertThat(response.getAddress()).isNull();
        assertThat(response.getIndividual()).isNull();
    }

    @Test
    void mapsCompactPerson() {
        UserEntity user = user();

        PersonResponse response = mapper.toPersonResponse(user);

        assertThat(response.getUserId()).isEqualTo(user.getId());
        assertThat(response.getEmail()).isEqualTo("user@example.com");
        assertThat(response.getFilled()).isFalse();
        assertThat(response.getCreatedAt().toInstant()).isEqualTo(CREATED_AT);
    }

    private static UserEntity user() {
        UserEntity user = new UserEntity(UUID.randomUUID(), CREATED_AT);
        user.setEmail("user@example.com");
        user.setFirstName("Ivan");
        user.setLastName("Ivanov");
        return user;
    }
}
