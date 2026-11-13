package com.nexttoskin.core.events;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the product snapshot carried by product-updated events (schemas/product-updated/v1.json) for every curated
 * product, straight from the catalog tables. Lists are sorted so the same data always gives the same snapshot.
 */
@Component
public class ProductSnapshots {

    public record Active(String name, BigDecimal percent, List<String> tags) {
    }

    public record Ingredient(String name, int position, List<String> tags) {
    }

    public record Offer(String retailer, int priceCents, BigDecimal sizeAmount, String sizeUnit, String url) {
    }

    /** @param status ACTIVE, or REMOVED once the product is dropped from the catalog */
    public record Product(String id, String status, String brand, String name, String category, Integer spf,
                          boolean imported, List<Active> actives, List<Ingredient> ingredients, List<Offer> offers) {
    }

    /** A product's snapshot and its catalog row id. */
    public record Snapshot(long rowId, String storedHash, Product product) {
    }

    private final JdbcTemplate jdbc;

    public ProductSnapshots(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Snapshot> all() {
        record Row(long rowId, String hash, String id, String status, String brand, String name, String category,
                   Integer spf, boolean imported) {
        }
        List<Row> rows = jdbc.query("""
                SELECT id, event_hash, source_id, removed_at IS NOT NULL AS removed, brand, name, category, spf, imported
                FROM products WHERE source = 'curated' ORDER BY source_id
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("event_hash"), rs.getString("source_id"),
                rs.getBoolean("removed") ? "REMOVED" : "ACTIVE", rs.getString("brand"), rs.getString("name"),
                rs.getString("category"), (Integer) rs.getObject("spf"), rs.getBoolean("imported")));

        Map<Long, List<Ingredient>> ingredients = new LinkedHashMap<>();
        jdbc.query("""
                SELECT pi.product_id, pi.position, i.inci_name,
                       coalesce(array_agg(t.tag ORDER BY t.tag) FILTER (WHERE t.tag IS NOT NULL), '{}') AS tags
                FROM product_ingredients pi
                JOIN products p ON p.id = pi.product_id AND p.source = 'curated'
                JOIN ingredients i ON i.id = pi.ingredient_id
                LEFT JOIN ingredient_tags t ON t.ingredient_id = i.id
                GROUP BY pi.product_id, pi.position, i.inci_name
                ORDER BY pi.product_id, pi.position
                """, rs -> {
            ingredients.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>()).add(new Ingredient(
                    rs.getString("inci_name"), rs.getInt("position"), tags(rs.getArray("tags"))));
        });

        Map<Long, List<Active>> actives = new LinkedHashMap<>();
        jdbc.query("""
                SELECT pa.product_id, i.inci_name, pa.percent,
                       coalesce(array_agg(t.tag ORDER BY t.tag) FILTER (WHERE t.tag IS NOT NULL), '{}') AS tags
                FROM product_actives pa
                JOIN products p ON p.id = pa.product_id AND p.source = 'curated'
                JOIN ingredients i ON i.id = pa.ingredient_id
                LEFT JOIN ingredient_tags t ON t.ingredient_id = i.id
                GROUP BY pa.product_id, i.inci_name, pa.percent
                ORDER BY pa.product_id, i.inci_name
                """, rs -> {
            actives.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>()).add(new Active(
                    rs.getString("inci_name"), rs.getBigDecimal("percent"), tags(rs.getArray("tags"))));
        });

        Map<Long, List<Offer>> offers = new LinkedHashMap<>();
        jdbc.query("""
                SELECT o.product_id, o.retailer, o.price_cents, o.size_amount, o.size_unit, o.url
                FROM product_offers o JOIN products p ON p.id = o.product_id AND p.source = 'curated'
                ORDER BY o.product_id, o.retailer, o.price_cents, o.size_amount, o.url
                """, rs -> {
            offers.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>()).add(new Offer(
                    rs.getString("retailer"), rs.getInt("price_cents"), rs.getBigDecimal("size_amount"),
                    rs.getString("size_unit"), rs.getString("url")));
        });

        return rows.stream().map(r -> new Snapshot(r.rowId(), r.hash(), new Product(r.id(), r.status(), r.brand(),
                r.name(), r.category(), r.spf(), r.imported(), actives.getOrDefault(r.rowId(), List.of()),
                ingredients.getOrDefault(r.rowId(), List.of()), offers.getOrDefault(r.rowId(), List.of()))))
                .toList();
    }

    private static List<String> tags(java.sql.Array array) throws java.sql.SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
