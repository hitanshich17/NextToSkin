package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.*;

import com.nexttoskin.core.feedback.Feedback.Reason;
import com.nexttoskin.core.ingredient.IngredientTag;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ingredients that may have caused a reaction, from the products a client disliked as "irritated"
 * (docs/feedback.md, "Suspect ingredients"). Suspects are excluded from every step.
 *
 * @param tags irritants found anywhere in a disliked product
 * @param mainActives strong actives that were a disliked product's main active
 * @param ingredients ingredients shared by 2+ disliked products and no liked product (and not too common)
 */
record Suspects(Set<IngredientTag> tags, Set<IngredientTag> mainActives, Set<String> ingredients) {

    static final Set<IngredientTag> IRRITANTS = EnumSet.of(FRAGRANCE, FRAGRANCE_ALLERGEN, ESSENTIAL_OIL,
            DRYING_ALCOHOL, MENTHOL, CAMPHOR, SODIUM_LAURYL_SULFATE);
    static final Set<IngredientTag> STRONG_ACTIVES = EnumSet.of(AHA, BHA, RETINOID, BENZOYL_PEROXIDE, L_ASCORBIC_ACID);
    /** Ingredients in more than this share of the catalog (water, glycerin, ...) can't tell products apart. */
    static final double TOO_COMMON = 0.5;

    static final Suspects NONE = new Suspects(Set.of(), Set.of(), Set.of());

    private static final Map<IngredientTag, String> LABELS = Map.ofEntries(
            Map.entry(FRAGRANCE, "Fragrance"), Map.entry(FRAGRANCE_ALLERGEN, "Fragrance allergens"),
            Map.entry(ESSENTIAL_OIL, "Essential oils"), Map.entry(DRYING_ALCOHOL, "Drying alcohol"),
            Map.entry(MENTHOL, "Menthol"), Map.entry(CAMPHOR, "Camphor"),
            Map.entry(SODIUM_LAURYL_SULFATE, "Sodium lauryl sulfate"), Map.entry(AHA, "AHAs (e.g. glycolic acid)"),
            Map.entry(BHA, "Salicylic acid"), Map.entry(RETINOID, "Retinoids"),
            Map.entry(BENZOYL_PEROXIDE, "Benzoyl peroxide"), Map.entry(L_ASCORBIC_ACID, "Pure vitamin C (L-ascorbic acid)"));

    static Suspects find(TriedProducts tried, List<RoutineProduct> catalog) {
        List<RoutineProduct> irritated = tried.disliked(catalog, Reason.IRRITATED);
        if (irritated.isEmpty()) {
            return NONE;
        }
        Set<IngredientTag> tags = EnumSet.noneOf(IngredientTag.class);
        Set<IngredientTag> mainActives = EnumSet.noneOf(IngredientTag.class);
        for (RoutineProduct p : irritated) {
            IRRITANTS.stream().filter(p::has).forEach(tags::add);
            STRONG_ACTIVES.stream().filter(p::hasMainActive).forEach(mainActives::add);
        }
        Set<String> shared = new TreeSet<>();
        if (irritated.size() >= 2) {
            Map<String, Integer> inDisliked = count(irritated);
            Map<String, Integer> inCatalog = count(catalog);
            Set<String> inLiked = new java.util.HashSet<>();
            catalog.stream().filter(tried::likes).forEach(p -> inLiked.addAll(p.ingredients()));
            inDisliked.forEach((ingredient, n) -> {
                if (n >= 2 && !inLiked.contains(ingredient)
                        && inCatalog.getOrDefault(ingredient, 0) <= TOO_COMMON * catalog.size()) {
                    shared.add(ingredient);
                }
            });
        }
        return new Suspects(tags, mainActives, shared);
    }

    private static Map<String, Integer> count(List<RoutineProduct> products) {
        Map<String, Integer> counts = new HashMap<>();
        products.forEach(p -> p.ingredients().forEach(i -> counts.merge(i, 1, Integer::sum)));
        return counts;
    }

    boolean excludes(RoutineProduct p) {
        return p.hasAny(tags) || mainActives.stream().anyMatch(p::hasMainActive)
                || p.ingredients().stream().anyMatch(ingredients::contains);
    }

    /** What the routine now leaves out, in words for the results page. */
    List<String> labels() {
        List<String> labels = new ArrayList<>();
        tags.forEach(t -> labels.add(LABELS.get(t)));
        mainActives.stream().filter(t -> !tags.contains(t)).forEach(t -> labels.add(LABELS.get(t)));
        labels.addAll(ingredients);
        return labels;
    }
}
