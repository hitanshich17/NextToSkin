package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.ingredient.IngredientTag;
import com.nexttoskin.core.routine.RoutinePlan.Note;
import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutineSelectorTest {

    private static RoutineProduct p(String id, String category, int priceCents, Object... tagPositions) {
        Map<IngredientTag, Integer> positions = new HashMap<>();
        for (int i = 0; i < tagPositions.length; i += 2) {
            positions.put((IngredientTag) tagPositions[i], (Integer) tagPositions[i + 1]);
        }
        return new RoutineProduct(id, "Brand", id, category, null, false, positions,
                new RoutineProduct.Offer("brand", priceCents, BigDecimal.valueOf(100), "ml", "https://example.com/" + id));
    }

    static final RoutineProduct CLEANSER = p("cleanser", "cleanser", 1500);
    static final RoutineProduct CHEAP_CLEANSER = p("cheap-cleanser", "cleanser", 500);
    static final RoutineProduct SERUM = p("serum", "treatment", 5000);
    static final RoutineProduct CHEAPER_SERUM = p("cheaper-serum", "treatment", 4000);
    static final RoutineProduct CREAM = p("cream", "moisturizer", 1000);
    static final RoutineProduct SPF = p("spf", "sunscreen", 1000);

    /** Cleanser and moisturizer lists are shared by AM and PM, so the same product fills both. */
    private static RoutinePlan plan() {
        Map<Step, List<RoutineProduct>> candidates = new EnumMap<>(Step.class);
        candidates.put(Step.AM_CLEANSER, List.of(CLEANSER, CHEAP_CLEANSER));
        candidates.put(Step.PM_CLEANSER, List.of(CLEANSER, CHEAP_CLEANSER));
        candidates.put(Step.PM_TREATMENT, List.of(SERUM, CHEAPER_SERUM));
        candidates.put(Step.AM_MOISTURIZER, List.of(CREAM));
        candidates.put(Step.PM_MOISTURIZER, List.of(CREAM));
        candidates.put(Step.AM_SUNSCREEN, List.of(SPF));
        return new RoutinePlan(candidates, NIACINAMIDE, List.of());
    }

    private static String pick(Routine routine, Step step) {
        return routine.picks().get(step).product().id();
    }

    @Test
    void withoutABudgetEveryStepGetsItsBestMatch() {
        Routine routine = RoutineSelector.select(plan(), null);

        assertThat(pick(routine, Step.AM_CLEANSER)).isEqualTo("cleanser");
        assertThat(pick(routine, Step.PM_TREATMENT)).isEqualTo("serum");
        assertThat(routine.totalCents()).isEqualTo(1500 + 5000 + 1000 + 1000); // repeats counted once
        assertThat(routine.withinBudget()).isTrue();
    }

    @Test
    void overBudgetGivesUpAsFewRankingPlacesAsPossible() {
        // Both swaps save $10; the treatment swap moves one step, the shared cleanser two.
        Routine routine = RoutineSelector.select(plan(), 7500);

        assertThat(pick(routine, Step.PM_TREATMENT)).isEqualTo("cheaper-serum");
        assertThat(pick(routine, Step.AM_CLEANSER)).isEqualTo("cleanser");
        assertThat(routine.totalCents()).isEqualTo(7500);
        assertThat(routine.notes()).doesNotContain(Note.OVER_BUDGET);
    }

    @Test
    void aRepeatedProductIsSwappedInBothSteps() {
        Routine routine = RoutineSelector.select(plan(), 6500);

        assertThat(pick(routine, Step.AM_CLEANSER)).isEqualTo("cheap-cleanser");
        assertThat(pick(routine, Step.PM_CLEANSER)).isEqualTo("cheap-cleanser");
        assertThat(routine.totalCents()).isEqualTo(6500);
    }

    @Test
    void cheaperOptionsAreShownAsAlternatives() {
        Routine routine = RoutineSelector.select(plan(), null);

        assertThat(routine.picks().get(Step.AM_CLEANSER).cheaperAlternatives()).containsExactly(CHEAP_CLEANSER);
        assertThat(routine.picks().get(Step.AM_SUNSCREEN).cheaperAlternatives()).isEmpty();
    }

    @Test
    void impossibleBudgetGivesTheCheapestRoutineAndSaysSo() {
        Routine routine = RoutineSelector.select(plan(), 1000);

        assertThat(routine.totalCents()).isEqualTo(500 + 4000 + 1000 + 1000);
        assertThat(routine.withinBudget()).isFalse();
        assertThat(routine.notes()).contains(Note.OVER_BUDGET);
    }

    @Test
    void stepWithoutCandidatesIsLeftOut() {
        Map<Step, List<RoutineProduct>> candidates = new EnumMap<>(plan().candidates());
        candidates.put(Step.PM_TREATMENT, List.of());
        Routine routine = RoutineSelector.select(new RoutinePlan(candidates, null, List.of()), null);

        assertThat(routine.picks()).doesNotContainKey(Step.PM_TREATMENT);
    }

    @Test
    void anAhaInAnyPickedProductShowsTheSunburnAlert() {
        Map<Step, List<RoutineProduct>> candidates = new EnumMap<>(plan().candidates());
        RoutineProduct glycolicCleanser = p("glycolic-cleanser", "cleanser", 900, AHA, 3);
        candidates.put(Step.AM_CLEANSER, List.of(glycolicCleanser));
        Routine routine = RoutineSelector.select(new RoutinePlan(candidates, NIACINAMIDE, List.of()), null);

        assertThat(routine.notes()).containsExactly(Note.AHA_SUNBURN_ALERT);
    }

    // ---- Last resort: the concern's next treatment active ----

    static final RoutineProduct PRICEY_BHA = p("pricey-bha", "treatment", 3700, BHA, 2);
    static final RoutineProduct VITAMIN_C_SERUM = p("vitamin-c-serum", "treatment", 2000, VITAMIN_C, 3);
    static final RoutineProduct AZELAIC_CREAM = p("azelaic-cream", "treatment", 1000, AZELAIC_ACID, 3);

    private static RoutinePlan dullnessPlan(java.util.LinkedHashMap<IngredientTag, List<RoutineProduct>> fallbacks) {
        Map<Step, List<RoutineProduct>> candidates = new EnumMap<>(plan().candidates());
        candidates.put(Step.PM_TREATMENT, List.of(PRICEY_BHA));
        return new RoutinePlan(candidates, BHA, List.of(), fallbacks);
    }

    private static java.util.LinkedHashMap<IngredientTag, List<RoutineProduct>> fallbacks() {
        var fallbacks = new java.util.LinkedHashMap<IngredientTag, List<RoutineProduct>>();
        fallbacks.put(VITAMIN_C, List.of(VITAMIN_C_SERUM));
        fallbacks.put(AZELAIC_ACID, List.of(AZELAIC_CREAM));
        return fallbacks;
    }

    @Test
    void treatmentKeepsItsActiveWhenTheBudgetFits() {
        Routine routine = RoutineSelector.select(dullnessPlan(fallbacks()), 7000); // 500 + 3700 + 1000 + 1000 = 6200

        assertThat(routine.treatmentActive()).isEqualTo(BHA);
        assertThat(routine.notes()).doesNotContain(Note.TREATMENT_CHANGED_FOR_BUDGET);
    }

    @Test
    void overBudgetSwitchesToTheNextActiveThatFits() {
        Routine routine = RoutineSelector.select(dullnessPlan(fallbacks()), 5000); // vitamin C: 500 + 2000 + 2000 = 4500

        assertThat(routine.treatmentActive()).isEqualTo(VITAMIN_C);
        assertThat(pick(routine, Step.PM_TREATMENT)).isEqualTo("vitamin-c-serum");
        assertThat(routine.notes()).contains(Note.TREATMENT_CHANGED_FOR_BUDGET, Note.VITAMIN_C_NEEDS_SUNSCREEN)
                .doesNotContain(Note.OVER_BUDGET);
    }

    @Test
    void preferenceOrderWinsOverPrice() {
        // Vitamin C fits, so the cheaper azelaic acid (later in the concern's list) isn't used.
        assertThat(RoutineSelector.select(dullnessPlan(fallbacks()), 4600).treatmentActive()).isEqualTo(VITAMIN_C);
        assertThat(RoutineSelector.select(dullnessPlan(fallbacks()), 3500).treatmentActive()).isEqualTo(AZELAIC_ACID);
    }

    @Test
    void whenNothingFitsTheBestActiveStays() {
        Routine routine = RoutineSelector.select(dullnessPlan(fallbacks()), 1000);

        assertThat(routine.treatmentActive()).isEqualTo(BHA);
        assertThat(routine.notes()).contains(Note.OVER_BUDGET).doesNotContain(Note.TREATMENT_CHANGED_FOR_BUDGET);
    }
}
