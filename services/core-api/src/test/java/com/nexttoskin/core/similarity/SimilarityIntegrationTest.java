package com.nexttoskin.core.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.PostgresTestConfiguration;
import com.nexttoskin.core.catalog.CatalogImporter;
import com.nexttoskin.core.feedback.Feedback;
import com.nexttoskin.core.feedback.FeedbackRepository;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** pgvector similarity on the real catalog. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimilarityIntegrationTest {

    @Autowired
    private CatalogImporter importer;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private SimilarityRepository similarity;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void importRealCatalog() throws Exception {
        jdbc.execute("TRUNCATE products CASCADE");
        importer.importDirectory(Path.of("../../catalog")); // also rebuilds the vectors
    }

    private List<String> moisturizersMostLike(UUID client) {
        var scores = similarity.scores(client);
        List<String> moisturizers = jdbc.queryForList(
                "SELECT source_id FROM products WHERE source = 'curated' AND category = 'moisturizer'", String.class);
        return moisturizers.stream().sorted(Comparator.comparing(id -> -scores.getOrDefault(id, 0.0))).toList();
    }

    @Test
    void everyCuratedProductHasAVector() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_vectors", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM products WHERE source = 'curated'", Integer.class));
    }

    @Test
    void likingASnailEssencePointsToTheSnailCream() {
        UUID client = UUID.randomUUID();
        feedback.save(client, new Feedback("cosrx-advanced-snail-96-mucin-power-essence", Feedback.Verdict.LIKED, null), null);

        assertThat(moisturizersMostLike(client)).first().isEqualTo("cosrx-advanced-snail-92-all-in-one-cream");
    }

    @Test
    void likingACeraveCleanserPointsToCeraveMoisturizers() {
        UUID client = UUID.randomUUID();
        feedback.save(client, new Feedback("cerave-hydrating-facial-cleanser", Feedback.Verdict.LIKED, null), null);

        assertThat(moisturizersMostLike(client).subList(0, 2)).allMatch(id -> id.startsWith("cerave-"));
    }

    @Test
    void dislikingPushesSimilarProductsDown() {
        UUID client = UUID.randomUUID();
        feedback.save(client, new Feedback("cosrx-advanced-snail-96-mucin-power-essence", Feedback.Verdict.DISLIKED,
                Feedback.Reason.TEXTURE), null);

        assertThat(similarity.scores(client).get("cosrx-advanced-snail-92-all-in-one-cream")).isLessThan(-0.5);
    }

    @Test
    void noFeedbackNoScores() {
        assertThat(similarity.scores(UUID.randomUUID())).isEmpty();
    }
}
