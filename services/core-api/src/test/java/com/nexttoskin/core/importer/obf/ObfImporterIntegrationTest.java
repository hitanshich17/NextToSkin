package com.nexttoskin.core.importer.obf;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.PostgresTestConfiguration;
import com.nexttoskin.core.importer.obf.ObfImporter.Outcome;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ObfImporterIntegrationTest {

    @Autowired
    private ObfImporter importer;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetImportedData() {
        jdbc.execute("TRUNCATE products CASCADE");
        jdbc.execute("DELETE FROM ingredient_aliases WHERE source <> 'seed'");
        jdbc.execute("DELETE FROM ingredients WHERE NOT curated");
    }

    private ImportStats importSample() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/obf/sample.jsonl")) {
            return importer.importStream(in);
        }
    }

    /** Canonical ingredient names of a product, in label order. */
    private List<String> ingredientsOf(String barcode) {
        return jdbc.queryForList("""
                SELECT i.inci_name FROM product_ingredients pi
                JOIN products p ON p.id = pi.product_id
                JOIN ingredients i ON i.id = pi.ingredient_id
                WHERE p.source_id = ? ORDER BY pi.position
                """, String.class, barcode);
    }

    @Test
    void countsEveryOutcome() throws IOException {
        ImportStats stats = importSample();

        assertThat(stats.linesRead()).isEqualTo(6);
        assertThat(stats.outcomes()).isEqualTo(Map.of(
                Outcome.IMPORTED, 2, Outcome.NO_INGREDIENTS, 1, Outcome.NO_BARCODE, 1,
                Outcome.NO_NAME, 1, Outcome.FAILED, 1));
    }

    @Test
    void storesProductWithCanonicalIngredientsInLabelOrder() throws IOException {
        importSample();

        Map<String, Object> product = jdbc.queryForMap(
                "SELECT brand, name, category, source, ingredients_raw FROM products WHERE source_id = '1001'");
        assertThat(product).containsEntry("brand", "NextToSkin Labs")
                .containsEntry("name", "Gentle Cream")
                .containsEntry("category", "moisturizer")
                .containsEntry("source", "open_beauty_facts");
        assertThat((String) product.get("ingredients_raw")).startsWith("Ingredients: Aqua, Glycerin");

        // "Parfum (Fragrance)" is one ingredient; "Water" repeats Aqua and is listed once, at Aqua's position
        assertThat(ingredientsOf("1001")).containsExactly("Aqua", "Glycerin", "Parfum", "Limonene", "Squalane");
    }

    @Test
    void resolvesSynonymsAndSlashNames() throws IOException {
        ImportStats stats = importSample();

        // AQUA/WATER/EAU: synonyms, one ingredient. CAPRYLYL/CAPRYL GLUCOSIDE: one unknown INCI name, kept whole.
        // COCOS NUCIFERA (COCONUT OIL): curated synonym. GLYCERIN/NOVEL HUMECTANT: known part, so both kept.
        assertThat(ingredientsOf("1002")).containsExactly("Aqua", "Sodium Laureth Sulfate",
                "CAPRYLYL/CAPRYL GLUCOSIDE", "Cocos Nucifera Oil", "Glycerin", "NOVEL HUMECTANT", "Novelium Extract");
        assertThat(stats.ingredientsCreated()).isEqualTo(3);
    }

    @Test
    void neverSavesSynonymsReadOnLabelsAsAliases() throws IOException {
        importSample();

        assertThat(jdbc.queryForList("""
                SELECT alias_normalized FROM ingredient_aliases WHERE source = 'label' ORDER BY 1
                """, String.class))
                .containsExactly("caprylyl/capryl glucoside", "novel humectant", "novelium extract");
    }

    @Test
    void reimportingUpdatesInPlace() throws IOException {
        importSample();
        ImportStats second = importSample();

        assertThat(second.count(Outcome.IMPORTED)).isEqualTo(2);
        assertThat(second.ingredientsCreated()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(2);
        assertThat(ingredientsOf("1001")).hasSize(5);
    }
}
