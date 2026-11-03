package com.nexttoskin.core.catalog;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes products and their ingredient lists. Shared by the importers; callers own the transaction.
 * Plain JDBC because imports are bulk loads with no entity state to manage.
 */
@Component
public class ProductWriter {

    /** A product as an importer found it. {@code sourceId} is unique within {@code source}. */
    public record ProductData(String source, String sourceId, String brand, String name, String category,
                              String ingredientsRaw, String imageUrl, String sourceUrl, boolean imported,
                              Integer spf) {
    }

    private final JdbcTemplate jdbc;

    public ProductWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the product, or updates it if (source, source_id) exists, and returns its id. */
    public long upsert(ProductData p) {
        return jdbc.queryForObject("""
                INSERT INTO products (source, source_id, brand, name, category, ingredients_raw, image_url, source_url,
                                      imported, spf)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, source_id) DO UPDATE SET
                    brand = EXCLUDED.brand,
                    name = EXCLUDED.name,
                    category = EXCLUDED.category,
                    ingredients_raw = EXCLUDED.ingredients_raw,
                    image_url = EXCLUDED.image_url,
                    source_url = EXCLUDED.source_url,
                    imported = EXCLUDED.imported,
                    spf = EXCLUDED.spf
                RETURNING id
                """, Long.class, p.source(), p.sourceId(), p.brand(), p.name(), p.category(),
                p.ingredientsRaw(), p.imageUrl(), p.sourceUrl(), p.imported(), p.spf());
    }

    /** Replaces the product's ingredient list. Ids must be distinct and in label order. */
    public void replaceIngredients(long productId, List<Long> ingredientIds) {
        jdbc.update("DELETE FROM product_ingredients WHERE product_id = ?", productId);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < ingredientIds.size(); i++) {
            rows.add(new Object[] {productId, i + 1, ingredientIds.get(i)});
        }
        jdbc.batchUpdate("INSERT INTO product_ingredients (product_id, position, ingredient_id) VALUES (?, ?, ?)",
                rows);
    }
}
