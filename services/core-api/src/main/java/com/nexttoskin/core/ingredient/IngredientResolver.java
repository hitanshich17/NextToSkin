package com.nexttoskin.core.ingredient;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Maps label ingredient names to canonical ingredient ids, creating an (uncurated) ingredient for each
 * name it has not seen before.
 *
 * <p>Label text is noisy (OCR errors, merged lines, lot codes), so this class never saves synonyms it
 * reads on a label as aliases. A wrong alias would silently turn one ingredient into another in every
 * later product and could hide the real cause of a reaction. Two entries for the same ingredient only
 * weaken the evidence a little. New synonyms enter the dictionary through R__seed_ingredients.sql.
 *
 * <p>The whole alias table is loaded into memory once, so resolving a name is a map lookup instead of a
 * query. Create one resolver per import run; it is not thread-safe and does not see changes made by
 * others after it loads.
 */
public class IngredientResolver {

    private final JdbcTemplate jdbc;
    private final Map<String, Long> idsByAlias = new HashMap<>();
    private final Set<Long> curatedIds = new HashSet<>();
    private int ingredientsCreated;

    public IngredientResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.query("""
                SELECT a.alias_normalized, a.ingredient_id, i.curated
                FROM ingredient_aliases a JOIN ingredients i ON i.id = a.ingredient_id
                """, rs -> {
            long id = rs.getLong("ingredient_id");
            idsByAlias.put(rs.getString("alias_normalized"), id);
            if (rs.getBoolean("curated")) {
                curatedIds.add(id);
            }
        });
    }

    /** Returns the canonical ingredient ids for one label ingredient, usually exactly one. */
    public List<Long> resolve(LabelIngredient ingredient) {
        String key = IngredientNames.normalize(ingredient.name());
        Long known = idsByAlias.get(key);
        if (known != null) {
            return List.of(known);
        }

        // "Cocos Nucifera (Coconut Oil)": the label says both names are one ingredient.
        // Trusted only when the other name is curated.
        for (String synonym : ingredient.synonyms()) {
            Long id = idsByAlias.get(IngredientNames.normalize(synonym));
            if (id != null && curatedIds.contains(id)) {
                return List.of(id);
            }
        }

        // A slash separates synonyms ("Paraffinum Liquidum/Mineral Oil"), is part of one INCI name
        // ("Caprylyl/Capryl Glucoside", "Acrylates/C10-30 Alkyl Acrylate Crosspolymer"), or replaced a
        // comma in OCR. When a part is a known ingredient, keep every part as its own ingredient:
        // synonyms collapse to one id, and nothing is lost if it was a comma.
        if (key.contains("/") && !key.contains("polymer")) {
            List<String> parts = Arrays.stream(ingredient.name().split("\\s*/\\s*"))
                    .filter(IngredientListParser::isPlausibleName)
                    .toList();
            if (parts.stream().anyMatch(p -> idsByAlias.containsKey(IngredientNames.normalize(p)))) {
                return parts.stream().map(this::idFor).distinct().toList();
            }
        }

        return List.of(idFor(ingredient.name()));
    }

    public int ingredientsCreated() {
        return ingredientsCreated;
    }

    private long idFor(String name) {
        String key = IngredientNames.normalize(name);
        Long known = idsByAlias.get(key);
        if (known != null) {
            return known;
        }
        long id = jdbc.queryForObject("""
                INSERT INTO ingredients (inci_name) VALUES (?)
                ON CONFLICT (inci_name) DO UPDATE SET inci_name = EXCLUDED.inci_name
                RETURNING id
                """, Long.class, name.strip());
        jdbc.update("""
                INSERT INTO ingredient_aliases (ingredient_id, alias, alias_normalized, source)
                VALUES (?, ?, ?, 'label')
                ON CONFLICT (alias_normalized) DO NOTHING
                """, id, name.strip(), key);
        idsByAlias.put(key, id);
        ingredientsCreated++;
        return id;
    }
}
