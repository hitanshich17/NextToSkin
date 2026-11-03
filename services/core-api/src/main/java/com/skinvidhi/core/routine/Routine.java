package com.skinvidhi.core.routine;

import com.skinvidhi.core.ingredient.IngredientTag;
import com.skinvidhi.core.routine.RoutinePlan.Note;
import com.skinvidhi.core.routine.RoutinePlan.Step;
import java.util.List;
import java.util.Map;

/**
 * The routine shown to the user: one product per step, with cheaper alternatives.
 *
 * @param picks the chosen product per step; a step with no fitting product (e.g. no treatment) is left out
 * @param totalCents upfront cost; a product used in both AM and PM is counted once
 * @param budgetCents the user's budget, or null for no limit
 * @param monthlyCents estimated cost per month, or null if a picked product's size is unknown
 *                     (products the client already owns still count: they will need rebuying)
 * @param avoided what is left out because of the client's past reactions, in words
 */
public record Routine(Map<Step, Pick> picks, IngredientTag treatmentActive, int totalCents, Integer budgetCents,
                      Integer monthlyCents, List<Note> notes, List<String> avoided) {

    public Routine {
        picks = Map.copyOf(picks);
        notes = List.copyOf(notes);
        avoided = List.copyOf(avoided);
    }

    public boolean withinBudget() {
        return budgetCents == null || totalCents <= budgetCents;
    }

    /**
     * @param cheaperAlternatives other products for this step that cost less, best match first
     * @param owned a product the client liked: kept in its step, $0 in the upfront total
     * @param cheaperForBudget the step's best match was swapped for this cheaper one to fit the budget
     */
    public record Pick(RoutineProduct product, List<RoutineProduct> cheaperAlternatives, boolean owned,
                       boolean cheaperForBudget) {

        public Pick {
            cheaperAlternatives = List.copyOf(cheaperAlternatives);
        }

        public Pick(RoutineProduct product, List<RoutineProduct> cheaperAlternatives) {
            this(product, cheaperAlternatives, false, false);
        }
    }
}
