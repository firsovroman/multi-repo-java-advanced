package com.example.person.unit;

import com.example.person.api.model.AddressRequest;
import com.example.person.api.model.CreateUserRequest;
import com.example.person.api.model.IndividualRequest;
import com.example.person.api.model.PersonRegistrationRequest;
import com.example.person.api.model.UpdateAddressRequest;
import com.example.person.api.model.UpdateUserRequest;
import com.example.person.api.model.UserResponse;
import com.example.person.domain.AddressEntity;
import com.example.person.domain.CountryEntity;
import com.example.person.domain.IndividualEntity;
import com.example.person.domain.UserEntity;
import com.example.person.error.ApiException;
import com.example.person.error.ErrorCode;
import com.example.person.error.RequestValidationException;
import com.example.person.mapper.UserMapper;
import com.example.person.metrics.UserMetrics;
import com.example.person.repository.CountryRepository;
import com.example.person.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.example.person.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UT-PS-001…008: правила агрегата без БД.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-08T10:15:30Z");
    private static final CountryEntity RUSSIA = new CountryEntity(1, "Russia", "RU", "RUS");
    private static final CountryEntity KAZAKHSTAN = new CountryEntity(3, "Kazakhstan", "KZ", "KAZ");

    @Mock
    private UserRepository users;
    @Mock
    private CountryRepository countries;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, countries, new UserMapper(), new UserMetrics(registry),
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
        when(users.saveAndFlush(any())).thenAnswer(returnsFirstArg());
        when(countries.findByAlpha3("RUS")).thenReturn(Optional.of(RUSSIA));
        when(countries.findByAlpha3("KAZ")).thenReturn(Optional.of(KAZAKHSTAN));
    }

    @Test
    void createsFilledUserWithAddressAndIndividual() {
        // UT-PS-001
        UserResponse response = service.create(fullRequest("user@example.com"));

        UserEntity saved = savedUser();
        assertThat(saved.getAddress().getCity()).isEqualTo("Moscow");
        assertThat(saved.getAddress().getCountry()).isEqualTo(RUSSIA);
        assertThat(saved.getIndividual().getUser()).isSameAs(saved);
        assertThat(saved.isFilled()).isTrue();
        assertThat(response.getFilled()).isTrue();
        assertThat(response.getIndividual().getStatus()).isEqualTo("NEW");
        assertThat(response.getIndividual().getVerifiedAt()).isNull();
        assertThat(response.getIndividual().getArchivedAt()).isNull();
        assertThat(registry.counter("person.users.creations").count()).isEqualTo(1);
    }

    @Test
    void userIsNotFilledWithoutPassportOrPhone() {
        CreateUserRequest request = fullRequest("user@example.com").individual(new IndividualRequest().phoneNumber("+79991234567"));

        assertThat(service.create(request).getFilled()).isFalse();
    }

    @Test
    void emptyIndividualIsNotCreated() {
        CreateUserRequest request = fullRequest("user@example.com").individual(new IndividualRequest());

        assertThat(service.create(request).getIndividual()).isNull();
    }

    @Test
    void registersBareUser() {
        // UT-PS-002
        UUID userId = service.register(new PersonRegistrationRequest("user@example.com", "Ivan", "Ivanov"));

        UserEntity saved = savedUser();
        assertThat(userId).isEqualTo(saved.getId());
        assertThat(saved.isFilled()).isFalse();
        assertThat(saved.getAddress()).isNull();
        assertThat(saved.getIndividual()).isNull();
    }

    @Test
    void storesEmailStrippedInLowerCase() {
        // UT-PS-003
        service.register(new PersonRegistrationRequest("  User@Example.COM ", "Ivan", "Ivanov"));

        assertThat(savedUser().getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void translatesUniqueIndexViolation() {
        // UT-PS-004
        when(users.saveAndFlush(any())).thenThrow(uniqueViolation("uk_users_email_lower"));

        assertError(() -> service.register(registration()), ErrorCode.EMAIL_ALREADY_EXISTS);
        assertThat(registry.counter("person.users.email_conflicts").count()).isEqualTo(1);
        assertThat(registry.counter("person.users.creations").count()).isZero();
    }

    @Test
    void otherIntegrityViolationsStayInternal() {
        when(users.saveAndFlush(any())).thenThrow(uniqueViolation("some_other_constraint"));

        assertThatThrownBy(() -> service.register(registration())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownCountry() {
        // UT-PS-005
        when(countries.findByAlpha3("ZZZ")).thenReturn(Optional.empty());
        CreateUserRequest request = fullRequest("user@example.com")
                .address(new AddressRequest("ZZZ", "ZZ", "City", "Line"));

        assertError(() -> service.create(request), ErrorCode.COUNTRY_NOT_FOUND);
    }

    @Test
    void rejectsAlpha2NotMatchingAlpha3() {
        // UT-PS-005
        CreateUserRequest request = fullRequest("user@example.com")
                .address(new AddressRequest("RUS", "KZ", "City", "Line"));

        assertError(() -> service.create(request), ErrorCode.COUNTRY_MISMATCH);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void readOfMissingUserIsNotFound() {
        // UT-PS-006
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        when(users.findByLowerEmail("user@example.com")).thenReturn(Optional.empty());

        assertError(() -> service.getUser(id), ErrorCode.USER_NOT_FOUND);
        assertError(() -> service.getPerson(id), ErrorCode.USER_NOT_FOUND);
        assertError(() -> service.getUserByEmail("User@Example.com"), ErrorCode.USER_NOT_FOUND);
        assertError(() -> service.getPersonByEmail("user@example.com"), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void readsPersonByEmailCaseInsensitively() {
        UserEntity user = existingUser();
        when(users.findByLowerEmail("user@example.com")).thenReturn(Optional.of(user));

        assertThat(service.getPersonByEmail("USER@example.com").getUserId()).isEqualTo(user.getId());
    }

    @Test
    void patchKeepsFieldsThatWereNotPassed() {
        // UT-PS-007
        UserEntity user = existingUser();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        UserResponse response = service.update(user.getId(), new UpdateUserRequest().firstName("Petr"), null);

        assertThat(response.getFirstName()).isEqualTo("Petr");
        assertThat(response.getLastName()).isEqualTo("Ivanov");
        assertThat(response.getEmail()).isEqualTo("user@example.com");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        verify(users).flush();
        assertThat(registry.counter("person.users.updated").count()).isEqualTo(1);
    }

    @Test
    void patchWithoutChangesDoesNotTouchAggregate() {
        // UT-PS-007
        UserEntity user = existingUser();
        Instant updatedAt = user.getUpdatedAt();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        UpdateUserRequest request = new UpdateUserRequest().email("USER@example.com").firstName("Ivan")
                .address(new UpdateAddressRequest().countryAlpha3("RUS").city("Moscow"))
                .individual(new IndividualRequest());

        service.update(user.getId(), request, null);

        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
        verify(users, never()).flush();
        assertThat(registry.counter("person.users.updated").count()).isZero();
    }

    @Test
    void patchChangesCountryOfExistingAddress() {
        UserEntity user = existingUser();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        UpdateUserRequest request = new UpdateUserRequest()
                .address(new UpdateAddressRequest().countryAlpha3("KAZ").countryAlpha2("KZ"));

        UserResponse response = service.update(user.getId(), request, null);

        assertThat(response.getAddress().getCountryAlpha3()).isEqualTo("KAZ");
    }

    @Test
    void patchWithPartialAddressCannotCreateOne() {
        // UT-PS-007
        UserEntity user = new UserEntity(UUID.randomUUID(), NOW);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        UpdateUserRequest request = new UpdateUserRequest().address(new UpdateAddressRequest().city("Kazan"));

        assertThatThrownBy(() -> service.update(user.getId(), request, null))
                .isInstanceOfSatisfying(RequestValidationException.class, e -> assertThat(e.violations()).hasSize(3));
    }

    @Test
    void patchRejectsStaleIfMatch() {
        // UT-PS-008: у новой сущности version == null, поэтому любое конкретное значение не совпадает
        UserEntity user = existingUser();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        UpdateUserRequest request = new UpdateUserRequest().firstName("Petr");

        assertError(() -> service.update(user.getId(), request, "\"3\""), ErrorCode.VERSION_MISMATCH);
        assertThat(user.getFirstName()).isEqualTo("Ivan");
    }

    @Test
    void patchRejectsMalformedIfMatch() {
        UserEntity user = existingUser();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        assertError(() -> service.update(user.getId(), new UpdateUserRequest(), "W/\"3\""), ErrorCode.MALFORMED_REQUEST);
    }

    @Test
    void deleteRemovesAggregateAndCounts() {
        UserEntity user = existingUser();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        service.delete(user.getId());

        verify(users).delete(user);
        assertThat(registry.counter("person.users.deleted").count()).isEqualTo(1);
        assertThat(registry.get("person.users.operation").tag("operation", "delete").tag("outcome", "success").timer().count())
                .isEqualTo(1);
    }

    @Test
    void failedDeleteIsNotCounted() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertError(() -> service.delete(id), ErrorCode.USER_NOT_FOUND);

        assertThat(registry.counter("person.users.deleted").count()).isZero();
        assertThat(registry.get("person.users.operation").tag("operation", "delete").tag("outcome", "error").timer().count())
                .isEqualTo(1);
    }

    private UserEntity savedUser() {
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private static CreateUserRequest fullRequest(String email) {
        return new CreateUserRequest(email, "Ivan", "Ivanov")
                .address(new AddressRequest("RUS", "RU", "Moscow", "Tverskaya 1").state("Moscow").zipCode("101000"))
                .individual(new IndividualRequest().passportNumber("4510123456").phoneNumber("+79991234567"));
    }

    private static PersonRegistrationRequest registration() {
        return new PersonRegistrationRequest("user@example.com", "Ivan", "Ivanov");
    }

    private static UserEntity existingUser() {
        UserEntity user = new UserEntity(UUID.randomUUID(), NOW.minusSeconds(60));
        user.setEmail("user@example.com");
        user.setFirstName("Ivan");
        user.setLastName("Ivanov");
        AddressEntity address = new AddressEntity(UUID.randomUUID(), NOW.minusSeconds(60));
        address.setCountry(RUSSIA);
        address.setCity("Moscow");
        address.setAddressLine("Tverskaya 1");
        user.setAddress(address);
        IndividualEntity individual = new IndividualEntity(UUID.randomUUID(), user);
        individual.setPassportNumber("4510123456");
        individual.setPhoneNumber("+79991234567");
        user.setIndividual(individual);
        user.recalculateFilled();
        return user;
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", new SQLException("duplicate"), constraint));
    }

    private static void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, hasCode(code));
    }

    private static Consumer<ApiException> hasCode(ErrorCode code) {
        return e -> assertThat(e.errorCode()).isEqualTo(code);
    }
}
