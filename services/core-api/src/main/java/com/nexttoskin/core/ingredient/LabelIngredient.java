package com.nexttoskin.core.ingredient;

import java.util.List;

/**
 * One ingredient as written on a label, plus any other names the label gave it,
 * e.g. "Parfum (Fragrance)" becomes name "Parfum" with synonym "Fragrance".
 */
public record LabelIngredient(String name, List<String> synonyms) {

    public LabelIngredient {
        synonyms = List.copyOf(synonyms);
    }

    public static LabelIngredient of(String name, String... synonyms) {
        return new LabelIngredient(name, List.of(synonyms));
    }
}
