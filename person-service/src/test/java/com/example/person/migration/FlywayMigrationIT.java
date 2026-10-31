package com.example.person.migration;

import com.example.person.PersonServiceApplication;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT-DB-001: миграции person-service применяются к PostgreSQL.
 * IT-PS-001: V003–V006 проходят и на пустой БД, и поверх уже применённых V001–V002; Hibernate validate их принимает.
 */
@Testcontainers
class FlywayMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("person")
            .withUsername("person")
            .withPassword("person");

    @Test
    void migrationsApplySuccessfully() throws Exception {
        Flyway flyway = flyway(POSTGRES.getJdbcUrl(), null);

        MigrateResult result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(6);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getTables(null, "person", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            assertThat(tables).contains("countries", "addresses", "users", "individuals",
                    "revinfo", "users_aud", "addresses_aud", "individuals_aud");

            try (var rs = connection.createStatement().executeQuery("SELECT count(*) FROM person.countries")) {
                rs.next();
                assertThat(rs.getInt(1)).isPositive();
            }
        }
    }

    @Test
    void newMigrationsApplyOnTopOfAppliedOnes() throws Exception {
        String url = createDatabase("upgraded");
        assertThat(flyway(url, "2").migrate().migrationsExecuted).isEqualTo(2);

        // Приложение само догоняет схему: история лежит там же, где её оставил контейнер person-migrations
        try (var context = new SpringApplicationBuilder(PersonServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--management.tracing.export.enabled=false")) {
            assertThat(context.isRunning()).isTrue();
        }

        assertThat(flyway(url, null).info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("6"));
    }

    private static Flyway flyway(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("person")
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private static String createDatabase(String name) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute("CREATE DATABASE " + name);
        }
        return POSTGRES.getJdbcUrl().replace("/person", "/" + name);
    }
}
