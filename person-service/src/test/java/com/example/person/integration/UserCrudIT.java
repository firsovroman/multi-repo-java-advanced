package com.example.person.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-PS-002…005, IT-PS-007: HTTP-операции /users и /persons поверх реальной БД.
 */
class UserCrudIT extends AbstractIntegrationTest {

    @Test
    void createsWholeAggregate() {
        // IT-PS-002
        String email = uniqueEmail();

        Response response = post("/api/v1/users", fullUser(email));

        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.body();
        UUID id = UUID.fromString(body.path("id").asString());
        assertThat(response.header("Location")).contains("/api/v1/users/" + id);
        assertThat(response.header("ETag")).contains("\"0\"");
        assertThat(body.path("filled").asBoolean()).isTrue();
        assertThat(body.path("individual").path("status").asString()).isEqualTo("NEW");
        assertThat(body.path("individual").path("verifiedAt").isNull()).isTrue();
        assertThat(body.path("address").path("countryAlpha3").asString()).isEqualTo("RUS");

        assertThat(count("users", "id", id)).isEqualTo(1);
        assertThat(count("individuals", "user_id", id)).isEqualTo(1);
        UUID addressId = jdbc.queryForObject("SELECT address_id FROM person.users WHERE id = ?", UUID.class, id);
        assertThat(count("addresses", "id", addressId)).isEqualTo(1);
    }

    @Test
    void userWithoutAddressAndIndividualIsNotFilled() {
        Response response = post("/api/v1/users", minimalUser(uniqueEmail()));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().path("filled").asBoolean()).isFalse();
    }

    @Test
    void registrationReturnsOnlyUserIdAndCreatesBareUser() {
        String email = uniqueEmail();

        Response response = post("/api/v1/persons/registration", minimalUser(email));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().propertyNames()).containsExactly("userId");
        String userId = response.body().path("userId").asString();

        Response person = get("/api/v1/persons/" + userId);
        assertThat(person.status()).isEqualTo(200);
        assertThat(person.body().path("email").asString()).isEqualTo(email);
        assertThat(person.body().path("filled").asBoolean()).isFalse();
        assertThat(get("/api/v1/users/" + userId).body().path("id").asString()).isEqualTo(userId);
    }

    @Test
    void rejectsDuplicateEmailInAnyCase() {
        // IT-PS-003
        String email = uniqueEmail();
        assertThat(post("/api/v1/users", minimalUser(email)).status()).isEqualTo(201);

        Response sameCase = post("/api/v1/users", minimalUser(email));
        Response upperCase = post("/api/v1/persons/registration", minimalUser(email.toUpperCase()));

        assertThat(sameCase.status()).isEqualTo(409);
        assertThat(sameCase.errorCode()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(upperCase.status()).isEqualTo(409);
    }

    @Test
    void parallelCreationYieldsExactlyOneUser() {
        // IT-PS-003: защиту от гонки даёт только индекс БД
        String email = uniqueEmail();

        List<Integer> statuses = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> post("/api/v1/users", minimalUser(email)).status()))
                .toList().stream()
                .map(CompletableFuture::join)
                .toList();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
    }

    @Test
    void readsAggregateByIdAndByEmailInAnyCase() {
        // IT-PS-004
        String email = uniqueEmail();
        Response created = post("/api/v1/users", fullUser(email));
        String id = created.body().path("id").asString();

        Response byId = get("/api/v1/users/" + id);
        Response byEmail = get("/api/v1/users/by-email?email=" + email.toUpperCase());
        Response personByEmail = get("/api/v1/persons/by-email?email=" + email.toUpperCase());

        assertThat(byId.status()).isEqualTo(200);
        assertThat(byId.body()).isEqualTo(created.body());
        assertThat(byEmail.status()).isEqualTo(200);
        assertThat(byEmail.body()).isEqualTo(created.body());
        assertThat(personByEmail.body().path("userId").asString()).isEqualTo(id);
    }

    @Test
    void unknownUserIsNotFound() {
        // IT-PS-004
        String unknownId = UUID.randomUUID().toString();

        assertThat(get("/api/v1/users/" + unknownId).status()).isEqualTo(404);
        assertThat(get("/api/v1/persons/" + unknownId).status()).isEqualTo(404);
        assertThat(get("/api/v1/users/by-email?email=" + uniqueEmail()).status()).isEqualTo(404);
        assertThat(get("/api/v1/persons/by-email?email=" + uniqueEmail()).errorCode()).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void patchUpdatesWholeAggregate() throws Exception {
        // IT-PS-005
        Response created = post("/api/v1/users", fullUser(uniqueEmail()));
        String id = created.body().path("id").asString();
        Thread.sleep(5);

        Response response = patch("/api/v1/users/" + id, """
                {
                  "firstName": "Petr",
                  "address": {"city": "Kazan"},
                  "individual": {"phoneNumber": "+79990000000"}
                }
                """);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.body();
        assertThat(body.path("firstName").asString()).isEqualTo("Petr");
        assertThat(body.path("lastName").asString()).isEqualTo("Ivanov");
        assertThat(body.path("address").path("city").asString()).isEqualTo("Kazan");
        assertThat(body.path("address").path("state").asString()).isEqualTo("Moscow");
        assertThat(body.path("individual").path("phoneNumber").asString()).isEqualTo("+79990000000");
        assertThat(body.path("individual").path("passportNumber").asString()).isEqualTo("4510123456");
        assertThat(body.path("updatedAt").asString()).isNotEqualTo(created.body().path("updatedAt").asString());
        assertThat(body.path("version").asLong()).isEqualTo(1);
        assertThat(response.header("ETag")).contains("\"1\"");
        assertThat(get("/api/v1/users/" + id).body()).isEqualTo(body);
    }

    @Test
    void patchOfNestedEntityAloneRaisesUserVersion() {
        Response created = post("/api/v1/users", fullUser(uniqueEmail()));
        String id = created.body().path("id").asString();

        Response response = patch("/api/v1/users/" + id, """
                {"address": {"zipCode": "420000"}}
                """);

        assertThat(response.body().path("version").asLong()).isEqualTo(1);
    }

    @Test
    void patchCreatesMissingAddressAndIndividualAndRecalculatesFilled() {
        String id = post("/api/v1/users", minimalUser(uniqueEmail())).body().path("id").asString();

        Response response = patch("/api/v1/users/" + id, """
                {
                  "address": {"countryAlpha3": "KAZ", "countryAlpha2": "KZ", "city": "Almaty", "addressLine": "Abay 1"},
                  "individual": {"passportNumber": "N123", "phoneNumber": "+77011234567"}
                }
                """);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("filled").asBoolean()).isTrue();
        assertThat(response.body().path("address").path("countryAlpha2").asString()).isEqualTo("KZ");
        assertThat(response.body().path("individual").path("status").asString()).isEqualTo("NEW");
    }

    @Test
    void patchNeedsAllRequiredFieldsToCreateAddress() {
        String id = post("/api/v1/users", minimalUser(uniqueEmail())).body().path("id").asString();

        Response response = patch("/api/v1/users/" + id, """
                {"address": {"city": "Almaty"}}
                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.body().path("errors").findValuesAsString("field"))
                .containsExactlyInAnyOrder("address.countryAlpha3", "address.countryAlpha2", "address.addressLine");
    }

    @Test
    void patchWithoutChangesKeepsVersionAndUpdatedAt() {
        Response created = post("/api/v1/users", fullUser(uniqueEmail()));
        String id = created.body().path("id").asString();
        String email = created.body().path("email").asString();

        Response response = patch("/api/v1/users/" + id, """
                {"email": "%s", "firstName": "Ivan", "address": {"city": "Moscow"}}
                """.formatted(email.toUpperCase()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(created.body());
    }

    @Test
    void patchRejectsExplicitNullInRequiredFields() {
        String id = createFullUser();

        Response top = patch("/api/v1/users/" + id, """
                {"firstName": null}
                """);
        Response nested = patch("/api/v1/users/" + id, """
                {"address": {"city": null}}
                """);
        Response blank = patch("/api/v1/users/" + id, """
                {"lastName": ""}
                """);

        assertThat(top.status()).isEqualTo(400);
        assertThat(top.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(top.body().path("errors").findValuesAsString("field")).containsExactly("firstName");
        assertThat(nested.status()).isEqualTo(400);
        assertThat(nested.body().path("errors").findValuesAsString("field")).containsExactly("address.city");
        assertThat(blank.status()).isEqualTo(400);
    }

    @Test
    void patchEmailConflictAndCountryRules() {
        String takenEmail = uniqueEmail();
        post("/api/v1/users", minimalUser(takenEmail));
        String id = createFullUser();

        Response conflict = patch("/api/v1/users/" + id, """
                {"email": "%s"}
                """.formatted(takenEmail.toUpperCase()));
        Response unknownCountry = patch("/api/v1/users/" + id, """
                {"address": {"countryAlpha3": "ZZZ"}}
                """);
        Response mismatch = patch("/api/v1/users/" + id, """
                {"address": {"countryAlpha3": "RUS", "countryAlpha2": "KZ"}}
                """);
        Response alpha2Only = patch("/api/v1/users/" + id, """
                {"address": {"countryAlpha2": "KZ"}}
                """);

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.errorCode()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(unknownCountry.errorCode()).isEqualTo("COUNTRY_NOT_FOUND");
        assertThat(mismatch.errorCode()).isEqualTo("COUNTRY_MISMATCH");
        assertThat(alpha2Only.errorCode()).isEqualTo("COUNTRY_MISMATCH");
    }

    @Test
    void ifMatchGuardsVersion() {
        String id = createFullUser();
        String path = "/api/v1/users/" + id;
        String body = """
                {"firstName": "Petr"}
                """;

        Response stale = patch(path, body, "If-Match", "\"7\"");
        Response malformed = patch(path, body, "If-Match", "7");
        Response matching = patch(path, body, "If-Match", "\"0\"");
        Response anyVersion = patch(path, """
                {"firstName": "Sidor"}
                """, "If-Match", "*");

        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.errorCode()).isEqualTo("VERSION_MISMATCH");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.errorCode()).isEqualTo("MALFORMED_REQUEST");
        assertThat(matching.status()).isEqualTo(200);
        assertThat(anyVersion.status()).isEqualTo(200);
    }

    @Test
    void deleteRemovesWholeAggregate() {
        // IT-PS-007
        UUID id = UUID.fromString(createFullUser());
        UUID addressId = jdbc.queryForObject("SELECT address_id FROM person.users WHERE id = ?", UUID.class, id);

        Response deleted = delete("/api/v1/users/" + id);
        Response repeated = delete("/api/v1/users/" + id);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(repeated.status()).isEqualTo(404);
        assertThat(repeated.errorCode()).isEqualTo("USER_NOT_FOUND");
        assertThat(count("users", "id", id)).isZero();
        assertThat(count("individuals", "user_id", id)).isZero();
        assertThat(count("addresses", "id", addressId)).isZero();
    }
}
