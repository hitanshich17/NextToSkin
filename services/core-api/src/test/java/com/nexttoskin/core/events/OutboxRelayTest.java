package com.nexttoskin.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexttoskin.core.PostgresTestConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Relay failure handling against real PostgreSQL, with Kafka replaced by a mock that can fail on demand. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    @BeforeEach
    void outbox() {
        jdbc.update("DELETE FROM outbox_events");
        for (String key : List.of("a", "b", "c")) {
            jdbc.update("INSERT INTO outbox_events (event_id, topic, event_key, payload) VALUES (?, 't', ?, ?::jsonb)",
                    UUID.randomUUID(), key, "{\"key\": \"" + key + "\"}");
        }
    }

    @SuppressWarnings("unchecked")
    private void kafkaAccepts(String key) {
        when(kafka.send(eq("t"), eq(key), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void kafkaRejects(String key) {
        when(kafka.send(eq("t"), eq(key), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    private List<String> published() {
        return jdbc.queryForList("SELECT event_key FROM outbox_events WHERE published_at IS NOT NULL ORDER BY id",
                String.class);
    }

    @Test
    void publishesInOrderAndMarksPublished() {
        List.of("a", "b", "c").forEach(this::kafkaAccepts);
        assertThat(new OutboxRelay(jdbc, tx, kafka).publishBatch()).isEqualTo(3);
        assertThat(published()).containsExactly("a", "b", "c");
    }

    @Test
    void aFailureStopsTheBatchSoNothingOvertakesIt() {
        kafkaAccepts("a");
        kafkaRejects("b");
        kafkaAccepts("c");
        OutboxRelay relay = new OutboxRelay(jdbc, tx, kafka);

        assertThat(relay.publishBatch()).isEqualTo(1);
        assertThat(published()).containsExactly("a"); // c waits behind b
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE event_key = 'b'", String.class))
                .contains("broker down");

        kafkaAccepts("b"); // broker back
        assertThat(relay.publishBatch()).isEqualTo(2);
        assertThat(published()).containsExactly("a", "b", "c");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE event_key = 'b'", Integer.class))
                .isEqualTo(2);
    }
}
