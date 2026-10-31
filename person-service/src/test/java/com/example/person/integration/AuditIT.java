package com.example.person.integration;

import com.example.person.domain.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-PS-008: ревизии Envers на создание, изменение и удаление.
 */
class AuditIT extends AbstractIntegrationTest {

    private static final int ADD = 0;
    private static final int MOD = 1;
    private static final int DEL = 2;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void creationIsOneRevisionOfThreeEntities() {
        UUID id = UUID.fromString(createFullUser());

        List<Map<String, Object>> users = auditRows("users_aud", "id", id);
        List<Map<String, Object>> individuals = auditRows("individuals_aud", "user_id", id);

        assertThat(users).hasSize(1);
        assertThat(users.getFirst().get("revtype")).isEqualTo(ADD);
        assertThat(individuals).hasSize(1);
        Object rev = users.getFirst().get("rev");
        assertThat(individuals.getFirst().get("rev")).isEqualTo(rev);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person.addresses_aud WHERE rev = ?", Integer.class, rev))
                .isEqualTo(1);
    }

    @Test
    void patchOfThreeEntitiesIsOneRevisionWithModifiedFlags() {
        UUID id = UUID.fromString(createFullUser());

        patch("/api/v1/users/" + id, """
                {"firstName": "Petr", "address": {"city": "Kazan"}, "individual": {"phoneNumber": "+79990000000"}}
                """);

        List<Map<String, Object>> users = auditRows("users_aud", "id", id);
        assertThat(users).hasSize(2);
        Map<String, Object> user = users.getLast();
        assertThat(user.get("revtype")).isEqualTo(MOD);
        assertThat(user.get("first_name_mod")).isEqualTo(true);
        assertThat(user.get("last_name_mod")).isEqualTo(false);
        assertThat(user.get("email_mod")).isEqualTo(false);

        Map<String, Object> individual = auditRows("individuals_aud", "user_id", id).getLast();
        assertThat(individual.get("rev")).isEqualTo(user.get("rev"));
        assertThat(individual.get("revtype")).isEqualTo(MOD);
        assertThat(individual.get("phone_number_mod")).isEqualTo(true);
        assertThat(individual.get("passport_number_mod")).isEqualTo(false);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM person.addresses_aud WHERE rev = ?", Integer.class, user.get("rev")))
                .isEqualTo(1);
    }

    @Test
    void revisionKeepsVersionAndTraceId() {
        UUID id = UUID.fromString(createFullUser());
        patch("/api/v1/users/" + id, """
                {"firstName": "Petr"}
                """);

        Map<String, Object> user = auditRows("users_aud", "id", id).getLast();

        assertThat(user.get("version")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT trace_id FROM person.revinfo WHERE rev = ?", String.class, user.get("rev")))
                .matches("[0-9a-f]{32}");
    }

    @Test
    void patchWithoutChangesLeavesNoRevision() {
        UUID id = UUID.fromString(createFullUser());

        patch("/api/v1/users/" + id, """
                {"firstName": "Ivan"}
                """);

        assertThat(auditRows("users_aud", "id", id)).hasSize(1);
    }

    @Test
    void deletionLeavesDelRevisionsAndFullHistory() {
        UUID id = UUID.fromString(createFullUser());
        UUID individualId = jdbc.queryForObject("SELECT id FROM person.individuals WHERE user_id = ?", UUID.class, id);
        UUID addressId = jdbc.queryForObject("SELECT address_id FROM person.users WHERE id = ?", UUID.class, id);
        patch("/api/v1/users/" + id, """
                {"firstName": "Petr"}
                """);

        delete("/api/v1/users/" + id);

        assertThat(auditRows("users_aud", "id", id)).extracting(row -> row.get("revtype"))
                .containsExactly(ADD, MOD, DEL);
        // Строка DEL содержит только id: ищем её по id самого individual и адреса
        assertThat(lastRevtype("individuals_aud", individualId)).isEqualTo(DEL);
        assertThat(lastRevtype("addresses_aud", addressId)).isEqualTo(DEL);

        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<?> history = AuditReaderFactory.get(entityManager).createQuery()
                    .forRevisionsOfEntity(UserEntity.class, false, true)
                    .getResultList();
            assertThat(history).anySatisfy(row -> {
                Object[] revision = (Object[]) row;
                assertThat(((UserEntity) revision[0]).getId()).isEqualTo(id);
                assertThat(revision[2]).isEqualTo(RevisionType.DEL);
            });
        } finally {
            entityManager.close();
        }
    }

    private List<Map<String, Object>> auditRows(String table, String column, UUID id) {
        return jdbc.queryForList("SELECT * FROM person." + table + " WHERE " + column + " = ? ORDER BY rev", id);
    }

    private Object lastRevtype(String table, UUID id) {
        return auditRows(table, "id", id).getLast().get("revtype");
    }
}
