package com.nexttoskin.core.events;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox events to Kafka in id order (docs/events.md). At least once: an event is marked published only
 * after Kafka acknowledged it, so a crash in between sends it again and consumers deduplicate by eventId.
 */
@Component
@ConditionalOnProperty(name = "nexttoskin.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    static final int BATCH = 100;
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
    }

    record Pending(long id, String topic, String key, String payload) {
    }

    @Scheduled(fixedDelayString = "${nexttoskin.outbox.relay.interval:500ms}")
    public void relay() {
        while (publishBatch() == BATCH) {
            // a full batch means there may be more
        }
    }

    /** Publishes up to one batch; returns how many were published. */
    int publishBatch() {
        Integer published = tx.execute(status -> {
            // Only one relay at a time (several instances may run), so events leave in order.
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext('outbox-relay'))",
                    Boolean.class);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<Pending> pending = jdbc.query("""
                    SELECT id, topic, event_key, payload::text AS payload FROM outbox_events
                    WHERE published_at IS NULL ORDER BY id LIMIT ?
                    """, (rs, i) -> new Pending(rs.getLong("id"), rs.getString("topic"), rs.getString("event_key"),
                    rs.getString("payload")), BATCH);
            int sent = 0;
            for (Pending p : pending) {
                try {
                    kafka.send(p.topic(), p.key(), p.payload()).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    // Stop here so later events never overtake this one; retried on the next run.
                    String error = e.getCause() != null ? e.getCause().toString() : e.toString();
                    jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                            error.length() > 500 ? error.substring(0, 500) : error, p.id());
                    log.warn("Outbox relay: event {} not published yet: {}", p.id(), error);
                    break;
                }
                jdbc.update("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = ?",
                        p.id());
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }
}
