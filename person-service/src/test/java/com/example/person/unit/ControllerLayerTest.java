package com.example.person.unit;

import com.example.person.api.PersonsApiController;
import com.example.person.api.UsersApiController;
import com.example.person.api.model.PersonResponse;
import com.example.person.api.model.UserResponse;
import com.example.person.controller.PersonsApiDelegateImpl;
import com.example.person.controller.UsersApiDelegateImpl;
import com.example.person.error.ApiException;
import com.example.person.error.ErrorCode;
import com.example.person.service.UserService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UT-PS-010 и HTTP-уровень без БД: контроллеры, делегаты, разбор тела и {@code @ControllerAdvice}.
 */
@WebMvcTest({PersonsApiController.class, UsersApiController.class})
@Import({PersonsApiDelegateImpl.class, UsersApiDelegateImpl.class})
class ControllerLayerTest {

    private static final String TRACE_ID = "803b448a0489f84084905d3093480352";
    private static final UUID ID = UUID.fromString("3d6f3532-8b97-4c7b-8a2a-608b6aeb3a7e");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 6, 8, 10, 15, 30, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
    }

    @Test
    void createReturnsLocationAndEtag() throws Exception {
        when(userService.create(any())).thenReturn(user(0));

        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"firstName\":\"Ivan\",\"lastName\":\"Ivanov\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/users/" + ID))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.createdAt").value("2026-06-08T10:15:30Z"));
    }

    @Test
    void readsAndUpdatesWithEtag() throws Exception {
        when(userService.getUser(ID)).thenReturn(user(2));
        when(userService.getUserByEmail("user@example.com")).thenReturn(user(2));
        when(userService.update(eq(ID), any(), eq("\"2\""))).thenReturn(user(3));

        mvc.perform(get("/api/v1/users/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        mvc.perform(get("/api/v1/users/by-email").param("email", "user@example.com"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/users/{id}", ID).header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"Petr\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mvc.perform(delete("/api/v1/users/{id}", ID)).andExpect(status().isNoContent());

        verify(userService).delete(ID);
    }

    @Test
    void personsOperations() throws Exception {
        PersonResponse person = new PersonResponse(ID, "user@example.com", "Ivan", "Ivanov", false).createdAt(NOW);
        when(userService.register(any())).thenReturn(ID);
        when(userService.getPerson(ID)).thenReturn(person);
        when(userService.getPersonByEmail("user@example.com")).thenReturn(person);

        mvc.perform(post("/api/v1/persons/registration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"firstName\":\"Ivan\",\"lastName\":\"Ivanov\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(ID.toString()));
        mvc.perform(get("/api/v1/persons/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filled").value(false));
        mvc.perform(get("/api/v1/persons/by-email").param("email", "user@example.com"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"COUNTRY_NOT_FOUND", "COUNTRY_MISMATCH", "USER_NOT_FOUND",
            "EMAIL_ALREADY_EXISTS", "VERSION_MISMATCH", "MALFORMED_REQUEST"})
    void everyDomainErrorIsProblemDetail(ErrorCode code) throws Exception {
        when(userService.getUser(ID)).thenThrow(new ApiException(code, "текст ошибки"));

        mvc.perform(get("/api/v1/users/{id}", ID))
                .andExpect(status().is(code.status().value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(code.type().toString()))
                .andExpect(jsonPath("$.title").value(code.title()))
                .andExpect(jsonPath("$.detail").value("текст ошибки"))
                .andExpect(jsonPath("$.instance").value("/api/v1/users/" + ID))
                .andExpect(jsonPath("$.errorCode").value(code.name()))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void optimisticLockIsConcurrentUpdate() throws Exception {
        when(userService.update(eq(ID), any(), any())).thenThrow(new OptimisticLockingFailureException("stale"));

        mvc.perform(patch("/api/v1/users/{id}", ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_UPDATE"));
    }

    @Test
    void unexpectedErrorHidesDetails() throws Exception {
        when(userService.getUser(ID)).thenThrow(new IllegalStateException("insert into person.users"));

        mvc.perform(get("/api/v1/users/{id}", ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Внутренняя ошибка сервиса"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void traceIdIsEmptyWithoutCurrentSpan() throws Exception {
        when(tracer.currentSpan()).thenReturn(null);
        when(userService.getUser(ID)).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND, "нет"));

        mvc.perform(get("/api/v1/users/{id}", ID)).andExpect(jsonPath("$.traceId").value(""));
    }

    @Test
    void bodyConstraintsGiveFieldViolations() throws Exception {
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\",\"firstName\":\"Ivan\",\"lastName\":\"Ivanov\","
                                + "\"individual\":{\"phoneNumber\":\"8999\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'individual.phoneNumber')]").exists());

        verify(userService, never()).create(any());
    }

    @Test
    void explicitNullInRequiredPatchFieldIsRejected() throws Exception {
        mvc.perform(patch("/api/v1/users/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":{\"addressLine\":null}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("address.addressLine"));

        verify(userService, never()).update(any(), any(), any());
    }

    @Test
    void explicitNullInOptionalPatchFieldIsAccepted() throws Exception {
        when(userService.update(eq(ID), any(), any())).thenReturn(user(1));

        mvc.perform(patch("/api/v1/users/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":{\"state\":null}}"))
                .andExpect(status().isOk());
    }

    @Test
    void unreadableBodyAndBadPath() throws Exception {
        mvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("[1,"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        mvc.perform(get("/api/v1/persons/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongEmailParameter() throws Exception {
        mvc.perform(get("/api/v1/persons/by-email").param("email", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        mvc.perform(get("/api/v1/persons/by-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void unsupportedMethodAndMediaType() throws Exception {
        mvc.perform(delete("/api/v1/users"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/users").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    private static UserResponse user(long version) {
        return new UserResponse(ID, "user@example.com", "Ivan", "Ivanov", false, NOW, NOW, version);
    }
}
