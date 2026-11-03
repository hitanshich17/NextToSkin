package com.nexttoskin.core.similarity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** How much each catalog product resembles what a client liked, and doesn't resemble what they disliked. */
@Repository
public class SimilarityRepository {

    private final JdbcTemplate jdbc;

    public SimilarityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Catalog product id -> score: cosine similarity to the closest liked product minus cosine similarity to the
     * closest disliked one (each 0 if there are none), so roughly -1 to 1. Empty without feedback.
     */
    public Map<String, Double> scores(UUID clientId) {
        Map<String, Double> scores = new HashMap<>();
        jdbc.query("""
                WITH mine AS (
                    SELECT f.verdict, v.embedding
                    FROM product_feedback f JOIN product_vectors v ON v.product_id = f.product_id
                    WHERE f.client_id = ?
                )
                SELECT p.source_id,
                       coalesce(max(1 - (v.embedding <=> m.embedding)) FILTER (WHERE m.verdict = 'LIKED'), 0)
                     - coalesce(max(1 - (v.embedding <=> m.embedding)) FILTER (WHERE m.verdict = 'DISLIKED'), 0)
                       AS score
                FROM products p
                JOIN product_vectors v ON v.product_id = p.id
                CROSS JOIN mine m
                WHERE p.source = 'curated'
                GROUP BY p.source_id
                """, rs -> {
            scores.put(rs.getString("source_id"), rs.getDouble("score"));
        }, clientId);
        return scores;
    }
}
