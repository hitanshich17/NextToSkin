package com.nexttoskin.core.routine;

import com.nexttoskin.core.ingredient.IngredientTag;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Loads the curated routine catalog with each product's ingredient tags, label positions and cheapest offer. */
@Repository
public class RoutineCatalog {

    private final JdbcTemplate jdbc;

    public RoutineCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoutineProduct> load() {
        record Row(String id, String brand, String name, String category, Integer spf, boolean imported,
                   RoutineProduct.Offer offer, Map<IngredientTag, Integer> positions) {
        }
        Map<Long, Row> rows = new LinkedHashMap<>();
        jdbc.query("""
                WITH tagged AS (
                    SELECT pi.product_id, t.tag, pi.position
                    FROM product_ingredients pi JOIN ingredient_tags t ON t.ingredient_id = pi.ingredient_id
                    UNION ALL
                    SELECT pa.product_id, t.tag, 0  -- declared OTC actives count as position 0
                    FROM product_actives pa JOIN ingredient_tags t ON t.ingredient_id = pa.ingredient_id
                )
                , cheapest AS (
                    SELECT DISTINCT ON (product_id) product_id, retailer, price_cents, size_amount, size_unit, url
                    FROM product_offers ORDER BY product_id, price_cents, size_amount DESC NULLS LAST, retailer, url
                )
                SELECT p.id, p.source_id, p.brand, p.name, p.category, p.spf, p.imported,
                       c.retailer, c.price_cents, c.size_amount, c.size_unit, c.url,
                       tg.tag, min(tg.position) AS first_position
                FROM products p
                LEFT JOIN tagged tg ON tg.product_id = p.id
                LEFT JOIN cheapest c ON c.product_id = p.id
                WHERE p.source = 'curated' AND p.removed_at IS NULL AND p.category IN ('cleanser', 'treatment', 'moisturizer', 'sunscreen')
                GROUP BY p.id, tg.tag, c.retailer, c.price_cents, c.size_amount, c.size_unit, c.url
                ORDER BY p.source_id COLLATE "C"  -- byte order, like Python: products that tie in a ranking keep this order
                """, rs -> {
            Row row = rows.computeIfAbsent(rs.getLong("id"), id -> {
                try {
                    RoutineProduct.Offer offer = rs.getObject("price_cents") == null ? null
                            : new RoutineProduct.Offer(rs.getString("retailer"), rs.getInt("price_cents"),
                                    rs.getBigDecimal("size_amount"), rs.getString("size_unit"), rs.getString("url"));
                    return new Row(rs.getString("source_id"), rs.getString("brand"), rs.getString("name"),
                            rs.getString("category"), (Integer) rs.getObject("spf"), rs.getBoolean("imported"),
                            offer, new EnumMap<>(IngredientTag.class));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            String tag = rs.getString("tag");
            if (tag != null) {
                row.positions().put(IngredientTag.valueOf(tag), rs.getInt("first_position"));
            }
        });
        Map<String, Set<String>> ingredients = new HashMap<>();
        jdbc.query("""
                SELECT p.source_id, i.inci_name
                FROM products p
                JOIN product_ingredients pi ON pi.product_id = p.id
                JOIN ingredients i ON i.id = pi.ingredient_id
                WHERE p.source = 'curated'
                """, rs -> {
            ingredients.computeIfAbsent(rs.getString("source_id"), id -> new HashSet<>()).add(rs.getString("inci_name"));
        });
        return rows.values().stream()
                .map(r -> new RoutineProduct(r.id(), r.brand(), r.name(), r.category(), r.spf(), r.imported(),
                        r.positions(), r.offer(), ingredients.getOrDefault(r.id(), Set.of())))
                .toList();
    }
}
