package com.nexttoskin.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.nexttoskin.core.KafkaTestConfiguration;
import com.nexttoskin.core.PostgresTestConfiguration;
import com.nexttoskin.core.catalog.CatalogCsvReader;
import com.nexttoskin.core.catalog.CatalogImporter;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Catalog import → outbox → relay → a real Kafka broker, checked against the published schema. */
@SpringBootTest(properties = {"nexttoskin.outbox.relay.enabled=true", "spring.kafka.admin.auto-create=true",
        "nexttoskin.outbox.relay.interval=1h"}) // the test drives the relay itself
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogEventsIntegrationTest {

    private static final Path CATALOG = Path.of("../../catalog");

    @Autowired
    private CatalogImporter importer;

    @Autowired
    private CatalogEvents events;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    private final ObjectMapper json = new ObjectMapper();
    private JsonSchema schema;

    @BeforeAll
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE products, outbox_events CASCADE");
        try (InputStream in = Files.newInputStream(Path.of("../../schemas/product-updated/v1.json"))) {
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        }
    }

    private List<JsonNode> unpublished() {
        return jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE published_at IS NULL ORDER BY id",
                String.class).stream().map(this::parse).toList();
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void drainOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL");
    }

    /** Imports the real catalog, with one product changed or dropped. */
    private void importCatalog(String productsCsv) throws Exception {
        String offers = Files.readString(CATALOG.resolve("offers.csv"));
        String firstId = productsCsv.lines().skip(1).findFirst().orElseThrow().split(",")[0];
        if (!productsCsv.contains("\n" + firstId)) {
            throw new IllegalStateException("bad fixture");
        }
        // Offers of products missing from the file would fail validation; keep only offers of listed products.
        List<String> keep = new ArrayList<>(List.of(offers.lines().findFirst().orElseThrow()));
        offers.lines().skip(1).filter(l -> productsCsv.contains("\n" + l.split(",")[0] + ",")).forEach(keep::add);
        importer.importCatalog(CatalogCsvReader.read(new StringReader(productsCsv),
                new StringReader(String.join("\n", keep)), LocalDate.now()));
    }

    @Test
    void theWholeFlow() throws Exception {
        String products = Files.readString(CATALOG.resolve("products.csv"), StandardCharsets.UTF_8);
        int productCount = (int) products.lines().skip(1).filter(l -> !l.isBlank()).count();

        // 1. First import: one event per product, each valid against the published schema.
        importer.importDirectory(CATALOG);
        List<JsonNode> first = unpublished();
        assertThat(first).hasSize(productCount);
        assertThat(first).allSatisfy(e -> assertThat(schema.validate(e)).as(e.at("/product/id").asText()).isEmpty());
        JsonNode differin = first.stream().filter(e -> e.at("/product/id").asText().equals("differin-adapalene-gel"))
                .findFirst().orElseThrow();
        assertThat(differin.at("/product/actives/0/tags").toString()).contains("RETINOID");
        assertThat(differin.at("/product/offers")).isNotEmpty();

        // 2. Re-importing the same catalog changes nothing, so no events (deduplication).
        drainOutbox();
        importer.importDirectory(CATALOG);
        assertThat(unpublished()).isEmpty();
        assertThat(events.sync()).isZero();

        // 3. One product renamed: exactly one event, for that product.
        String renamed = products.replaceFirst("(?m)^(cerave-hydrating-facial-cleanser,CeraVe,)Hydrating Facial Cleanser,",
                "$1Hydrating Facial Cleanser (New Look),");
        assertThat(renamed).isNotEqualTo(products);
        importCatalog(renamed);
        assertThat(unpublished()).singleElement().satisfies(e -> {
            assertThat(e.at("/product/id").asText()).isEqualTo("cerave-hydrating-facial-cleanser");
            assertThat(e.at("/product/name").asText()).isEqualTo("Hydrating Facial Cleanser (New Look)");
        });

        // 4. A product dropped from the file: one REMOVED event, and routines stop using it.
        drainOutbox();
        String dropped = renamed.replaceFirst("(?m)^cerave-hydrating-facial-cleanser,.*\\R", "");
        importCatalog(dropped);
        assertThat(unpublished()).singleElement().satisfies(e -> {
            assertThat(e.at("/product/status").asText()).isEqualTo("REMOVED");
            assertThat(schema.validate(e)).isEmpty();
        });

        // 5. The relay publishes everything waiting, keyed by product id, and marks it published.
        jdbc.update("UPDATE outbox_events SET published_at = NULL");
        int waiting = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
        relay.relay();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Integer.class))
                .isZero();

        List<ConsumerRecord<String, String>> records = consumeAll(waiting);
        assertThat(records).hasSize(waiting);
        assertThat(records).allSatisfy(r -> assertThat(parse(r.value()).at("/product/id").asText()).isEqualTo(r.key()));
        // Per-key order: the cleanser's three versions arrive in the order they happened.
        assertThat(records.stream().filter(r -> r.key().equals("cerave-hydrating-facial-cleanser"))
                .map(r -> parse(r.value()))
                .map(e -> e.at("/product/status").asText() + ":" + e.at("/product/name").asText()))
                .containsExactly("ACTIVE:Hydrating Facial Cleanser", "ACTIVE:Hydrating Facial Cleanser (New Look)",
                        "REMOVED:Hydrating Facial Cleanser (New Look)");
    }

    @Test
    void topicsHaveThreePartitions() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var topics = admin.describeTopics(List.of(CatalogEvents.TOPIC, CatalogEvents.TOPIC + ".dlt"))
                    .allTopicNames().get();
            assertThat(topics.values()).allSatisfy(t -> assertThat(t.partitions()).hasSize(3));
        }
    }

    private List<ConsumerRecord<String, String>> consumeAll(int expected) {
        Properties props = new Properties();
        props.putAll(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaAdmin.getConfigurationProperties().get("bootstrap.servers"),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(CatalogEvents.TOPIC));
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }
}
