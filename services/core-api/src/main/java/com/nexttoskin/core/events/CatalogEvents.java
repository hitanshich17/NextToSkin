package com.nexttoskin.core.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns catalog changes into product-updated events in the outbox (docs/events.md). Compares each product's
 * snapshot hash with the one last published, so unchanged products produce no event. Runs after every catalog
 * import and at startup, which catches up if the service stopped between an import and its sync.
 */
@Service
public class CatalogEvents {

    public static final String TOPIC = "catalog.product-updated";
    public static final int SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(CatalogEvents.class);

    private final ProductSnapshots snapshots;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired // the constructor Spring uses; the other one lets tests fix the clock
    public CatalogEvents(ProductSnapshots snapshots, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this(snapshots, jdbc, tx, json, Clock.systemUTC());
    }

    CatalogEvents(ProductSnapshots snapshots, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                  Clock clock) {
        this.snapshots = snapshots;
        this.jdbc = jdbc;
        this.tx = tx;
        // Sorted keys make the hash stable however the snapshot was built.
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncAtStartup() {
        int queued = sync();
        if (queued > 0) {
            log.info("Queued {} catalog events at startup", queued);
        }
    }

    /** Queues an event for every product whose snapshot changed since it was last published. Returns the count. */
    public int sync() {
        return tx.execute(status -> {
            // One sync at a time, so two instances never queue the same change twice.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('catalog-events-sync'))", Object.class);
            int queued = 0;
            for (ProductSnapshots.Snapshot s : snapshots.all()) {
                String productJson = write(s.product());
                String hash = sha256(productJson);
                if (hash.equals(s.storedHash())) {
                    continue;
                }
                UUID eventId = UUID.randomUUID();
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("schemaVersion", SCHEMA_VERSION);
                event.put("eventId", eventId.toString());
                event.put("eventType", "product-updated");
                event.put("occurredAt", Instant.now(clock).toString());
                event.put("product", s.product());
                jdbc.update("INSERT INTO outbox_events (event_id, topic, event_key, payload) VALUES (?, ?, ?, ?::jsonb)",
                        eventId, TOPIC, s.product().id(), write(event));
                jdbc.update("UPDATE products SET event_hash = ? WHERE id = ?", hash, s.rowId());
                queued++;
            }
            return queued;
        });
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
