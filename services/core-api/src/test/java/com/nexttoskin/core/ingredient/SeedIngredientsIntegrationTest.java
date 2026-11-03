package com.nexttoskin.core.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.PostgresTestConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SeedIngredientsIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void resetImportedData() {
        jdbc.execute("TRUNCATE products CASCADE");
        jdbc.execute("DELETE FROM ingredient_aliases WHERE source <> 'seed'");
        jdbc.execute("DELETE FROM ingredients WHERE NOT curated");
    }

    /** Runs the repeatable migration again, as Flyway does when the file changes. */
    private void rerunSeed() throws IOException {
        String sql = new ClassPathResource("db/migration/R__seed_ingredients.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        tx.executeWithoutResult(status -> jdbc.execute(sql)); // temp tables live until commit
    }

    private long insertAutoIngredient(String inciName, String aliasNormalized) {
        long id = jdbc.queryForObject(
                "INSERT INTO ingredients (inci_name) VALUES (?) RETURNING id", Long.class, inciName);
        jdbc.update("INSERT INTO ingredient_aliases (ingredient_id, alias, alias_normalized, source) "
                + "VALUES (?, ?, ?, 'label')", id, inciName, aliasNormalized);
        return id;
    }

    @Test
    void seedAliasesMatchJavaNormalization() {
        List<Map<String, Object>> aliases = jdbc.queryForList(
                "SELECT alias, alias_normalized FROM ingredient_aliases WHERE source = 'seed'");

        assertThat(aliases).hasSizeGreaterThan(100);
        assertThat(aliases).allSatisfy(row -> assertThat(IngredientNames.normalize((String) row.get("alias")))
                .as("alias %s", row.get("alias"))
                .isEqualTo(row.get("alias_normalized")));
    }

    @Test
    void curatedIngredientsCarryIrritantPrior() {
        assertThat(jdbc.queryForObject(
                "SELECT is_common_irritant FROM ingredients WHERE inci_name = 'Methylisothiazolinone'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT i.inci_name FROM ingredient_aliases a JOIN ingredients i ON i.id = a.ingredient_id "
                        + "WHERE a.alias_normalized = 'fragrance'", String.class))
                .isEqualTo("Parfum");
    }

    @Test
    void rerunningSeedMergesIngredientsImportedBeforeTheyWereCurated() throws IOException {
        // Pretend Aqua was not curated yet when products were imported: labels created "AQUA" and "WATER".
        jdbc.update("DELETE FROM ingredients WHERE inci_name = 'Aqua'");
        long aqua = insertAutoIngredient("AQUA", "aqua");
        long water = insertAutoIngredient("WATER", "water");
        long glycerin = jdbc.queryForObject("SELECT id FROM ingredients WHERE inci_name = 'Glycerin'", Long.class);
        long product = jdbc.queryForObject("""
                INSERT INTO products (name, source, source_id) VALUES ('Test', 'test', '1') RETURNING id
                """, Long.class);
        jdbc.update("INSERT INTO product_ingredients VALUES (?, 1, ?), (?, 2, ?), (?, 3, ?)",
                product, aqua, product, glycerin, product, water);

        rerunSeed();

        assertThat(jdbc.queryForList("""
                SELECT i.inci_name FROM product_ingredients pi JOIN ingredients i ON i.id = pi.ingredient_id
                WHERE pi.product_id = ? ORDER BY pi.position
                """, String.class, product)).containsExactly("Aqua", "Glycerin");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ingredients WHERE id IN (?, ?)", Integer.class, aqua, water)).isZero();
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT i.inci_name || ':' || a.source FROM ingredient_aliases a
                JOIN ingredients i ON i.id = a.ingredient_id WHERE a.alias_normalized IN ('aqua', 'water')
                """, String.class)).containsExactly("Aqua:seed");
    }
}
