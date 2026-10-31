package com.example.person.service;

import com.example.person.api.model.AddressRequest;
import com.example.person.api.model.CreateUserRequest;
import com.example.person.api.model.FieldViolation;
import com.example.person.api.model.IndividualRequest;
import com.example.person.api.model.PersonRegistrationRequest;
import com.example.person.api.model.PersonResponse;
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
import com.example.person.logging.LogContext;
import com.example.person.mapper.UserMapper;
import com.example.person.metrics.UserMetrics;
import com.example.person.repository.CountryRepository;
import com.example.person.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Правила агрегата user + address + individual. Каждая запись — одна транзакция.
 * Транзакции заданы шаблоном, а не аннотацией: таймер и перевод конфликта email должны видеть и ошибки коммита,
 * а счётчики — считать только зафиксированные изменения.
 */
@Service
public class UserService {

    private static final String EMAIL_INDEX = "uk_users_email_lower";
    private static final Pattern ETAG = Pattern.compile("\"\\d+\"");

    private final UserRepository users;
    private final CountryRepository countries;
    private final UserMapper mapper;
    private final UserMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public UserService(UserRepository users, CountryRepository countries, UserMapper mapper, UserMetrics metrics,
                       Clock clock, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.countries = countries;
        this.mapper = mapper;
        this.metrics = metrics;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public UserResponse create(CreateUserRequest request) {
        return insert("create", request.getEmail(), request.getFirstName(), request.getLastName(),
                request.getAddress(), request.getIndividual());
    }

    /**
     * Регистрация из individuals-api: тот же сценарий, что {@link #create}, но без адреса и individual.
     */
    public UUID register(PersonRegistrationRequest request) {
        return insert("register", request.getEmail(), request.getFirstName(), request.getLastName(), null, null).getId();
    }

    public UserResponse getUser(UUID id) {
        return read("get_by_id", () -> findById(id), mapper::toUserResponse);
    }

    public UserResponse getUserByEmail(String email) {
        return read("get_by_email", () -> findByEmail(email), mapper::toUserResponse);
    }

    public PersonResponse getPerson(UUID userUid) {
        return read("get_by_id", () -> findById(userUid), mapper::toPersonResponse);
    }

    public PersonResponse getPersonByEmail(String email) {
        return read("get_by_email", () -> findByEmail(email), mapper::toPersonResponse);
    }

    public UserResponse update(UUID id, UpdateUserRequest request, String ifMatch) {
        LogContext.userUid(id);
        return metrics.record("update", () -> translateEmailConflict(() -> {
            Update update = writeTx.execute(status -> apply(id, request, ifMatch));
            if (update.changed()) {
                metrics.userUpdated();
            }
            return update.response();
        }));
    }

    public void delete(UUID id) {
        LogContext.userUid(id);
        metrics.record("delete", () -> {
            writeTx.executeWithoutResult(status -> users.delete(findById(id)));
            metrics.userDeleted();
            return null;
        });
    }

    private UserResponse insert(String operation, String email, String firstName, String lastName,
                                AddressRequest address, IndividualRequest individual) {
        return metrics.record(operation, () -> translateEmailConflict(() -> {
            UserResponse response = writeTx.execute(status -> {
                UserEntity user = new UserEntity(UUID.randomUUID(), now());
                LogContext.userUid(user.getId());
                user.setEmail(normalizeEmail(email));
                user.setFirstName(firstName);
                user.setLastName(lastName);
                if (address != null) {
                    user.setAddress(newAddress(address));
                }
                if (hasData(individual)) {
                    user.setIndividual(newIndividual(user, individual));
                }
                user.recalculateFilled();
                return mapper.toUserResponse(users.saveAndFlush(user));
            });
            metrics.userCreated();
            return response;
        }));
    }

    private AddressEntity newAddress(AddressRequest request) {
        AddressEntity address = new AddressEntity(UUID.randomUUID(), now());
        address.setCountry(resolveCountry(request.getCountryAlpha3(), request.getCountryAlpha2()));
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setZipCode(request.getZipCode());
        address.setAddressLine(request.getAddressLine());
        return address;
    }

    private static IndividualEntity newIndividual(UserEntity user, IndividualRequest request) {
        IndividualEntity individual = new IndividualEntity(UUID.randomUUID(), user);
        individual.setPassportNumber(request.getPassportNumber());
        individual.setPhoneNumber(request.getPhoneNumber());
        return individual;
    }

    private Update apply(UUID id, UpdateUserRequest request, String ifMatch) {
        UserEntity user = findById(id);
        checkVersion(user, ifMatch);

        boolean changed = applyUserFields(user, request);
        changed |= applyAddress(user, request.getAddress());
        changed |= applyIndividual(user, request.getIndividual());

        if (changed) {
            // updatedAt меняется и при правке вложенных сущностей: так растёт версия, которую клиент видит в ETag
            user.setUpdatedAt(now());
            user.recalculateFilled();
            users.flush();
        }
        return new Update(mapper.toUserResponse(user), changed);
    }

    private static void checkVersion(UserEntity user, String ifMatch) {
        if (ifMatch == null || "*".equals(ifMatch)) {
            return;
        }
        if (!ETAG.matcher(ifMatch).matches()) {
            throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Заголовок If-Match должен иметь вид \"<version>\"");
        }
        if (!ifMatch.equals("\"" + user.getVersion() + "\"")) {
            throw new ApiException(ErrorCode.VERSION_MISMATCH, "Версия в If-Match не совпала с текущей");
        }
    }

    private static boolean applyUserFields(UserEntity user, UpdateUserRequest request) {
        boolean changed = false;
        String email = request.getEmail() != null ? normalizeEmail(request.getEmail()) : null;
        if (isChanged(email, user.getEmail())) {
            user.setEmail(email);
            changed = true;
        }
        if (isChanged(request.getFirstName(), user.getFirstName())) {
            user.setFirstName(request.getFirstName());
            changed = true;
        }
        if (isChanged(request.getLastName(), user.getLastName())) {
            user.setLastName(request.getLastName());
            changed = true;
        }
        return changed;
    }

    private boolean applyAddress(UserEntity user, UpdateAddressRequest request) {
        if (request == null) {
            return false;
        }
        AddressEntity address = user.getAddress();
        if (address == null) {
            user.setAddress(newAddress(completeAddress(request)));
            return true;
        }

        boolean changed = false;
        CountryEntity country = changedCountry(address.getCountry(), request);
        if (country != null) {
            address.setCountry(country);
            changed = true;
        }
        if (isChanged(request.getCity(), address.getCity())) {
            address.setCity(request.getCity());
            changed = true;
        }
        if (isChanged(request.getState(), address.getState())) {
            address.setState(request.getState());
            changed = true;
        }
        if (isChanged(request.getZipCode(), address.getZipCode())) {
            address.setZipCode(request.getZipCode());
            changed = true;
        }
        if (isChanged(request.getAddressLine(), address.getAddressLine())) {
            address.setAddressLine(request.getAddressLine());
            changed = true;
        }
        if (changed) {
            address.setUpdatedAt(now());
        }
        return changed;
    }

    /**
     * Новый адрес создаётся только целиком: у частичного запроса не хватает обязательных полей.
     */
    private static AddressRequest completeAddress(UpdateAddressRequest request) {
        List<FieldViolation> missing = new ArrayList<>();
        requirePresent(request.getCountryAlpha3(), "address.countryAlpha3", missing);
        requirePresent(request.getCountryAlpha2(), "address.countryAlpha2", missing);
        requirePresent(request.getCity(), "address.city", missing);
        requirePresent(request.getAddressLine(), "address.addressLine", missing);
        if (!missing.isEmpty()) {
            throw new RequestValidationException("Для создания адреса нужны все обязательные поля", missing);
        }
        return new AddressRequest(request.getCountryAlpha3(), request.getCountryAlpha2(), request.getCity(),
                request.getAddressLine())
                .state(request.getState())
                .zipCode(request.getZipCode());
    }

    private static void requirePresent(String value, String field, List<FieldViolation> missing) {
        if (value == null) {
            missing.add(new FieldViolation(field, "обязательное поле"));
        }
    }

    /**
     * @return новая страна или {@code null}, если страна не меняется
     */
    private CountryEntity changedCountry(CountryEntity current, UpdateAddressRequest request) {
        if (request.getCountryAlpha3() != null) {
            CountryEntity country = resolveCountry(request.getCountryAlpha3(), request.getCountryAlpha2());
            return country.getId().equals(current.getId()) ? null : country;
        }
        if (request.getCountryAlpha2() != null && !request.getCountryAlpha2().equals(current.getAlpha2())) {
            throw countryMismatch();
        }
        return null;
    }

    private CountryEntity resolveCountry(String alpha3, String alpha2) {
        CountryEntity country = countries.findByAlpha3(alpha3)
                .orElseThrow(() -> new ApiException(ErrorCode.COUNTRY_NOT_FOUND, "Страна не найдена в справочнике"));
        if (alpha2 != null && !alpha2.equals(country.getAlpha2())) {
            throw countryMismatch();
        }
        return country;
    }

    private static ApiException countryMismatch() {
        return new ApiException(ErrorCode.COUNTRY_MISMATCH, "countryAlpha2 не соответствует countryAlpha3");
    }

    private static boolean applyIndividual(UserEntity user, IndividualRequest request) {
        if (!hasData(request)) {
            return false;
        }
        IndividualEntity individual = user.getIndividual();
        if (individual == null) {
            user.setIndividual(newIndividual(user, request));
            return true;
        }

        boolean changed = false;
        if (isChanged(request.getPassportNumber(), individual.getPassportNumber())) {
            individual.setPassportNumber(request.getPassportNumber());
            changed = true;
        }
        if (isChanged(request.getPhoneNumber(), individual.getPhoneNumber())) {
            individual.setPhoneNumber(request.getPhoneNumber());
            changed = true;
        }
        return changed;
    }

    private static boolean hasData(IndividualRequest request) {
        return request != null && (request.getPassportNumber() != null || request.getPhoneNumber() != null);
    }

    /**
     * {@code null} в запросе значит «поле не передано».
     */
    private static boolean isChanged(String requested, String current) {
        return requested != null && !Objects.equals(requested, current);
    }

    private <T> T read(String operation, Supplier<UserEntity> finder, Function<UserEntity, T> mapping) {
        return metrics.record(operation, () -> readTx.execute(status -> {
            UserEntity user = finder.get();
            LogContext.userUid(user.getId());
            return mapping.apply(user);
        }));
    }

    private UserEntity findById(UUID id) {
        return users.findById(id).orElseThrow(UserService::userNotFound);
    }

    private UserEntity findByEmail(String email) {
        return users.findByLowerEmail(normalizeEmail(email)).orElseThrow(UserService::userNotFound);
    }

    private static ApiException userNotFound() {
        return new ApiException(ErrorCode.USER_NOT_FOUND, "Пользователь не найден");
    }

    /**
     * Уникальность email гарантирует индекс БД: предварительная проверка не спасла бы от гонки.
     */
    private <T> T translateEmailConflict(Supplier<T> write) {
        try {
            return write.get();
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && EMAIL_INDEX.equals(violation.getConstraintName())) {
                metrics.emailConflict();
                throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "Пользователь с таким email уже существует");
            }
            throw e;
        }
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * PostgreSQL хранит микросекунды: без округления значение в ответе разошлось бы с прочитанным из БД.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Update(UserResponse response, boolean changed) {
    }
}
