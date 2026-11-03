package com.nexttoskin.core.ingredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes {@code ingredient_tags} for every ingredient from its names (INCI name and all aliases).
 * Cheap enough to rerun in full after each import, which keeps tags in step with the rules in {@link IngredientTag}.
 */
@Component
public class IngredientTagger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IngredientTagger(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Returns the number of (ingredient, tag) pairs written. */
    public int retagAll() {
        Map<Long, List<String>> namesById = new HashMap<>();
        jdbc.query("""
                SELECT i.id, i.inci_name, a.alias_normalized
                FROM ingredients i LEFT JOIN ingredient_aliases a ON a.ingredient_id = i.id
                """, rs -> {
            List<String> names = namesById.computeIfAbsent(rs.getLong("id"), id -> new ArrayList<>());
            names.add(rs.getString("inci_name"));
            String alias = rs.getString("alias_normalized");
            if (alias != null) {
                names.add(alias);
            }
        });

        List<Object[]> rows = new ArrayList<>();
        namesById.forEach((id, names) ->
                IngredientTag.tagsFor(names).forEach(tag -> rows.add(new Object[] {id, tag.name()})));

        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM ingredient_tags");
            jdbc.batchUpdate("INSERT INTO ingredient_tags (ingredient_id, tag) VALUES (?, ?)", rows);
        });
        return rows.size();
    }
}
