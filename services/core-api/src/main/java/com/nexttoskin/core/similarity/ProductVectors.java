package com.nexttoskin.core.similarity;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds each curated product's ingredient vector (TF-IDF style) and stores it with pgvector.
 *
 * <p>Weight of an ingredient = position weight x rarity:
 * <ul>
 *   <li>position weight {@code 1 / sqrt(position)}: earlier on the label = higher concentration; declared OTC
 *       actives count as position 1</li>
 *   <li>rarity {@code ln(N / products containing it)}: water and glycerin, in nearly everything, weigh ~0, so two
 *       products look alike only when they share distinctive ingredients</li>
 * </ul>
 * Rebuilt in full after each catalog import, like the ingredient tags.
 */
@Component
public class ProductVectors {

    /** Matches the sparsevec(1000000) column; ingredient ids must stay below it. */
    static final int DIMENSIONS = 1_000_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ProductVectors(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Returns the number of vectors written. */
    public int rebuildAll() {
        Map<Long, Map<Long, Integer>> labels = new HashMap<>(); // product -> ingredient -> first position
        jdbc.query("""
                SELECT pi.product_id, pi.ingredient_id, min(pi.position) AS position
                FROM product_ingredients pi JOIN products p ON p.id = pi.product_id
                WHERE p.source = 'curated' GROUP BY pi.product_id, pi.ingredient_id
                UNION ALL
                SELECT pa.product_id, pa.ingredient_id, 1
                FROM product_actives pa JOIN products p ON p.id = pa.product_id
                WHERE p.source = 'curated'
                """, rs -> {
            labels.computeIfAbsent(rs.getLong("product_id"), id -> new HashMap<>())
                    .merge(rs.getLong("ingredient_id"), rs.getInt("position"), Math::min);
        });
        Map<Long, Integer> productsWith = new HashMap<>();
        labels.values().forEach(label -> label.keySet().forEach(i -> productsWith.merge(i, 1, Integer::sum)));

        return tx.execute(status -> {
            jdbc.update("DELETE FROM product_vectors v USING products p WHERE p.id = v.product_id AND p.source = 'curated'");
            int written = 0;
            for (var product : labels.entrySet()) {
                Map<Long, Double> weights = weights(product.getValue(), productsWith, labels.size());
                if (!weights.isEmpty()) { // a vector of zeros has no direction to compare
                    jdbc.update("INSERT INTO product_vectors (product_id, embedding) VALUES (?, ?::sparsevec)",
                            product.getKey(), sparsevec(weights));
                    written++;
                }
            }
            return written;
        });
    }

    /** Ingredient id -> weight; ingredients in every product get weight 0 and are left out. */
    static Map<Long, Double> weights(Map<Long, Integer> positions, Map<Long, Integer> productsWith, int products) {
        Map<Long, Double> weights = new TreeMap<>(); // sparsevec literals need ascending indices
        positions.forEach((ingredient, position) -> {
            double rarity = Math.log((double) products / productsWith.get(ingredient));
            double weight = rarity / Math.sqrt(Math.max(position, 1));
            if (weight > 0) {
                weights.put(ingredient, weight);
            }
        });
        return weights;
    }

    /** pgvector's text form, e.g. {@code {3:0.5,17:1.2}/1000000}; sparsevec indices start at 1, like our ids. */
    static String sparsevec(Map<Long, Double> weights) {
        weights.keySet().forEach(id -> {
            if (id < 1 || id > DIMENSIONS) {
                throw new IllegalStateException("ingredient id " + id + " doesn't fit the vector size " + DIMENSIONS);
            }
        });
        return weights.entrySet().stream()
                .map(e -> e.getKey() + ":" + String.format(Locale.ROOT, "%.6f", e.getValue()))
                .collect(Collectors.joining(",", "{", "}/" + DIMENSIONS));
    }

    List<Long> productIdsWithVectors() {
        return jdbc.queryForList("SELECT product_id FROM product_vectors ORDER BY product_id", Long.class);
    }
}
