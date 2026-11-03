package com.nexttoskin.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexttoskin.core.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ProductOffersSchemaIntegrationTest {

    private static final String INSERT_OFFER = """
            INSERT INTO product_offers (product_id, retailer, price_cents, size_amount, size_unit, url)
            VALUES (?, ?, ?, ?, ?, 'https://example.com/p')
            """;

    @Autowired
    private JdbcTemplate jdbc;

    private long productId;

    @BeforeEach
    void createProduct() {
        jdbc.execute("TRUNCATE products CASCADE");
        productId = jdbc.queryForObject(
                "INSERT INTO products (name, source, source_id) VALUES ('Cleanser', 'test', '1') RETURNING id",
                Long.class);
    }

    @Test
    void productCanHaveOffersFromSeveralRetailersAndSizes() {
        jdbc.update(INSERT_OFFER, productId, "target", 1599, 473, "ml");
        jdbc.update(INSERT_OFFER, productId, "walmart", 1497, 473, "ml");
        jdbc.update(INSERT_OFFER, productId, "walmart", 999, 236, "ml");

        assertThat(jdbc.queryForObject(
                "SELECT min(price_cents) FROM product_offers WHERE product_id = ?", Integer.class, productId))
                .isEqualTo(999);
    }

    @Test
    void rejectsDuplicateOfferEvenWithoutSize() {
        jdbc.update(INSERT_OFFER, productId, "brand", 2000, null, null);

        assertThatThrownBy(() -> jdbc.update(INSERT_OFFER, productId, "brand", 1800, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSizeWithoutUnit() {
        assertThatThrownBy(() -> jdbc.update(INSERT_OFFER, productId, "target", 1599, 473, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingProductDeletesItsOffers() {
        jdbc.update(INSERT_OFFER, productId, "target", 1599, 473, "ml");
        jdbc.update("DELETE FROM products WHERE id = ?", productId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_offers", Integer.class)).isZero();
    }
}
