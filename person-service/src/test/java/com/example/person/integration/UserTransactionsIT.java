package com.example.person.integration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * IT-PS-006 и IT-PS-009. Часы вызываются после чтения агрегата и правки сущностей,
 * поэтому через них тест вмешивается в середину транзакции.
 */
class UserTransactionsIT extends AbstractIntegrationTest {

    @MockitoSpyBean
    private Clock clock;

    @Test
    void failureInsidePatchRollsEverythingBack() {
        // IT-PS-006
        String id = createFullUser();
        String before = get("/api/v1/users/" + id).raw().body();
        int revisions = jdbc.queryForObject("SELECT count(*) FROM person.revinfo", Integer.class);

        // Первый вызов — правка адреса, второй — после того как изменены и user, и address, и individual
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("сбой внутри транзакции");
            }
            return invocation.callRealMethod();
        }).when(clock).instant();

        Response response = patch("/api/v1/users/" + id, """
                {"firstName": "Petr", "address": {"city": "Kazan"}, "individual": {"phoneNumber": "+79990000000"}}
                """);
        Mockito.reset(clock);

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(get("/api/v1/users/" + id).raw().body()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person.revinfo", Integer.class)).isEqualTo(revisions);
    }

    @Test
    void concurrentPatchesLetOnlyOneWin() {
        // IT-PS-009: барьер гарантирует, что обе транзакции прочитали одну и ту же версию
        String id = createFullUser();
        CyclicBarrier bothRead = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRead.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(clock).instant();

        List<CompletableFuture<Response>> requests = Stream.of("Petr", "Sidor")
                .map(name -> CompletableFuture.supplyAsync(() -> patch("/api/v1/users/" + id, """
                        {"firstName": "%s"}
                        """.formatted(name))))
                .toList();
        List<Response> responses = requests.stream().map(CompletableFuture::join).toList();
        Mockito.reset(clock);

        assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
        Response loser = responses.stream().filter(response -> response.status() == 409).findFirst().orElseThrow();
        assertThat(loser.errorCode()).isEqualTo("CONCURRENT_UPDATE");
        assertThat(count("users", "id", UUID.fromString(id))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM person.users WHERE id = ?", Long.class, UUID.fromString(id)))
                .isEqualTo(1);
    }
}
