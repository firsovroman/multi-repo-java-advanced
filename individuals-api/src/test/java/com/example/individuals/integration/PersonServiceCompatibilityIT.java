package com.example.individuals.integration;

import com.example.person.client.api.PersonsApi;
import com.example.person.client.model.PersonRegistrationRequest;
import com.example.person.client.model.PersonRegistrationResponse;
import com.example.person.client.model.PersonResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IT-PS-013: опубликованный person-client против настоящего образа person-service.
 * Нужен собранный jar: {@code cd person-service && ./gradlew bootJar}, иначе тест пропускается.
 */
@EnabledIf("personServiceJarExists")
class PersonServiceCompatibilityIT {

    private static final Path PERSON_SERVICE = Path.of("../person-service");
    private static final Path JAR = PERSON_SERVICE.resolve("build/libs/person-service.jar");

    private static Network network;
    private static PostgreSQLContainer postgres;
    private static GenericContainer<?> personService;
    private static PersonsApi personsApi;

    static boolean personServiceJarExists() {
        return Files.exists(JAR);
    }

    @BeforeAll
    static void startPersonService() {
        network = Network.newNetwork();
        postgres = new PostgreSQLContainer("postgres:18")
                .withNetwork(network)
                .withNetworkAliases("person-postgres")
                .withDatabaseName("person")
                .withUsername("person")
                .withPassword("person");
        postgres.start();

        personService = new GenericContainer<>(new ImageFromDockerfile()
                .withFileFromPath("Dockerfile", PERSON_SERVICE.resolve("Dockerfile"))
                .withFileFromPath("build/libs/person-service.jar", JAR))
                .withNetwork(network)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://person-postgres:5432/person")
                .withEnv("SPRING_DATASOURCE_USERNAME", "person")
                .withEnv("SPRING_DATASOURCE_PASSWORD", "person")
                .withExposedPorts(8082)
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(8082));
        personService.start();

        WebClient webClient = WebClient.create("http://" + personService.getHost() + ":" + personService.getMappedPort(8082));
        personsApi = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build()
                .createClient(PersonsApi.class);
    }

    @AfterAll
    static void stopPersonService() {
        personService.stop();
        postgres.stop();
        network.close();
    }

    @Test
    void registersAndReadsPerson() {
        String email = uniqueEmail();

        UUID userId = register(email).block().getUserId();

        PersonResponse byUid = personsApi.getPersonByUserUid(userId).block();
        assertThat(byUid.getEmail()).isEqualTo(email);
        assertThat(byUid.getFirstName()).isEqualTo("Ivan");
        assertThat(byUid.getFilled()).isFalse();
        assertThat(byUid.getCreatedAt()).isNotNull();

        PersonResponse byEmail = personsApi.getPersonByEmail(email.toUpperCase()).block();
        assertThat(byEmail.getUserId()).isEqualTo(userId);
    }

    @Test
    void duplicateEmailIsConflict() {
        String email = uniqueEmail();
        register(email).block();

        assertThatThrownBy(() -> register(email.toUpperCase()).block())
                .isInstanceOf(WebClientResponseException.Conflict.class);
    }

    @Test
    void unknownPersonIsNotFound() {
        assertThatThrownBy(() -> personsApi.getPersonByUserUid(UUID.randomUUID()).block())
                .isInstanceOf(WebClientResponseException.NotFound.class);
    }

    private static Mono<PersonRegistrationResponse> register(String email) {
        return personsApi.registerPerson(Mono.just(new PersonRegistrationRequest(email, "Ivan", "Ivanov")));
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
