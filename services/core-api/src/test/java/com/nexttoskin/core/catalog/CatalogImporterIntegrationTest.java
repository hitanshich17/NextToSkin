package com.nexttoskin.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.PostgresTestConfiguration;
import java.io.IOException;
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
class CatalogImporterIntegrationTest {

    private static final String PRODUCTS = """
            gentle-cleanser,Brand A,Gentle Cleanser,cleanser,Benzoyl Peroxide 4%,"Aqua, Glycerin, Fragrance (Parfum), Novel Botanical Extract",https://brand-a.example/img.jpg,https://brand-a.example/cleanser
            """;
    private static final String TWO_OFFERS = """
            gentle-cleanser,target,15.99,16,fl oz,https://target.example/p/1,2026-09-20
            gentle-cleanser,brand,18.00,,,https://brand-a.example/cleanser,2026-09-21
            """;

    @Autowired
    private CatalogImporter importer;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE products CASCADE");
        jdbc.execute("DELETE FROM ingredient_aliases WHERE source <> 'seed'");
        jdbc.execute("DELETE FROM ingredients WHERE NOT curated");
    }

    private CatalogImporter.Result importCsv(String offers) throws IOException {
        return importer.importCatalog(CatalogCsvReaderTest.read(PRODUCTS, offers));
    }

    @Test
    void importsProductWithIngredientsAndOffers() throws IOException {
        CatalogImporter.Result result = importCsv(TWO_OFFERS);

        assertThat(result).isEqualTo(new CatalogImporter.Result(1, 2, 1));
        assertThat(jdbc.queryForMap("""
                SELECT source, source_id, brand, category, image_url, source_url, imported FROM products
                """)).containsEntry("source", "curated")
                .containsEntry("source_id", "gentle-cleanser")
                .containsEntry("category", "cleanser")
                .containsEntry("source_url", "https://brand-a.example/cleanser")
                .containsEntry("imported", false);
        assertThat(jdbc.queryForList("""
                SELECT i.inci_name FROM product_ingredients pi JOIN ingredients i ON i.id = pi.ingredient_id
                ORDER BY pi.position
                """, String.class)).containsExactly("Aqua", "Glycerin", "Parfum", "Novel Botanical Extract");

        assertThat(jdbc.queryForList("""
                SELECT concat_ws(' ', retailer, price_cents, size_amount, size_unit,
                                 (checked_at AT TIME ZONE 'UTC')::date)
                FROM product_offers ORDER BY retailer
                """, String.class)).containsExactly(
                "brand 1800 2026-09-21",
                "target 1599 473.18 ml 2026-09-20");
    }

    @Test
    void storesActivesWithPercentageLinkedToCanonicalIngredient() throws IOException {
        importCsv(TWO_OFFERS);

        assertThat(jdbc.queryForList("""
                SELECT i.inci_name || ' ' || pa.percent FROM product_actives pa JOIN ingredients i ON i.id = pa.ingredient_id
                """, String.class)).containsExactly("Benzoyl Peroxide 4.00");
    }

    @Test
    void storesSpfForSunscreens() throws IOException {
        importer.importCatalog(CatalogCsvReaderTest.read("""
                sun,Brand S,Sun 50,sunscreen,Zinc Oxide 20%,"Water, Glycerin",,https://s.example/sun,,50
                """, ""));

        assertThat(jdbc.queryForObject("SELECT spf FROM products WHERE source_id = 'sun'", Integer.class)).isEqualTo(50);
    }

    @Test
    void tagsIngredientsAfterImport() throws IOException {
        importer.importCatalog(CatalogCsvReaderTest.read("""
                tagged,Brand T,Tagged Cream,moisturizer,,"Aqua, Fragrance (Parfum), Ethyl Macadamiate, Lactic Acid/Glycolic Acid Copolymer, Citric Acid",,https://t.example/p
                """, ""));

        assertThat(jdbc.queryForList("""
                SELECT i.inci_name || ':' || t.tag FROM product_ingredients pi
                JOIN ingredients i ON i.id = pi.ingredient_id
                JOIN ingredient_tags t ON t.ingredient_id = i.id
                ORDER BY pi.position
                """, String.class)).containsExactly("Parfum:FRAGRANCE", "Ethyl Macadamiate:NUT");
    }

    @Test
    void reimportReplacesOffersFromTheFile() throws IOException {
        importCsv(TWO_OFFERS);
        CatalogImporter.Result second = importCsv(
                "gentle-cleanser,target,13.99,16,fl oz,https://target.example/p/1,2026-09-23\n");

        assertThat(second.ingredientsCreated()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT retailer || ':' || price_cents FROM product_offers", String.class))
                .containsExactly("target:1399");
    }
}
