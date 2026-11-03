package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.AHA;

import com.nexttoskin.core.ingredient.IngredientTag;
import com.nexttoskin.core.routine.RoutinePlan.Note;
import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Picks one product per step from a {@link RoutinePlan}, within the budget ("best match within budget").
 *
 * <p>It starts from each step's best-ranked product. While the total is over budget, it makes the swap that saves
 * the most money per place given up in the rankings, always moving to a cheaper product further down a step's
 * list. A product used in both AM and PM is paid for once, so swapping it means swapping it in both steps.
 *
 * <p>Last resort: if the routine is still over budget, the night treatment may switch to the concern's next
 * active (e.g. vitamin C instead of BHA for dullness), with a note saying so.
 */
public final class RoutineSelector {

    /** How many cheaper alternatives each step shows. */
    static final int ALTERNATIVES = 3;

    private RoutineSelector() {
    }

    public static Routine select(RoutinePlan plan, Integer budgetCents) {
        Routine best = select(plan, plan.candidates(), plan.treatmentActive(), plan.notes(), budgetCents);
        if (best.withinBudget()) {
            return best;
        }
        for (Map.Entry<IngredientTag, List<RoutineProduct>> fallback : plan.budgetFallbacks().entrySet()) {
            Map<Step, List<RoutineProduct>> candidates = new EnumMap<>(plan.candidates());
            candidates.put(Step.PM_TREATMENT, fallback.getValue());
            List<Note> notes = new ArrayList<>(plan.notes());
            notes.remove(Note.forTreatment(plan.treatmentActive()));
            if (Note.forTreatment(fallback.getKey()) != null) {
                notes.add(Note.forTreatment(fallback.getKey()));
            }
            notes.add(Note.TREATMENT_CHANGED_FOR_BUDGET);
            Routine switched = select(plan, candidates, fallback.getKey(), notes, budgetCents);
            if (switched.withinBudget()) {
                return switched;
            }
        }
        return best; // nothing fits: keep the best match, with the over-budget note
    }

    private static Routine select(RoutinePlan plan, Map<Step, List<RoutineProduct>> candidates,
                                  IngredientTag treatmentActive, List<Note> planNotes, Integer budgetCents) {
        Set<RoutineProduct> owned = plan.owned();
        Map<Step, List<RoutineProduct>> lists = new EnumMap<>(Step.class);
        candidates.forEach((step, products) -> {
            if (!products.isEmpty()) {
                lists.put(step, products);
            }
        });
        Map<Step, Integer> chosen = new EnumMap<>(Step.class); // index into each step's list
        lists.keySet().forEach(step -> chosen.put(step, 0));

        while (budgetCents != null && total(lists, chosen, owned) > budgetCents) {
            Map<Step, Integer> best = bestSwap(lists, chosen, owned);
            if (best == null) {
                break; // nothing cheaper left anywhere
            }
            chosen.clear();
            chosen.putAll(best);
        }

        Map<Step, Routine.Pick> picks = new EnumMap<>(Step.class);
        chosen.forEach((step, index) -> {
            RoutineProduct product = lists.get(step).get(index);
            List<RoutineProduct> cheaper = lists.get(step).stream()
                    .filter(p -> !p.equals(product) && p.cheapestPriceCents() < product.cheapestPriceCents())
                    .limit(ALTERNATIVES)
                    .toList();
            boolean isOwned = owned.contains(product);
            boolean swapped = index > 0; // only the budget moves a step off its best match
            picks.put(step, new Routine.Pick(product, isOwned ? List.of() : cheaper, isOwned, swapped));
        });

        List<Note> notes = new ArrayList<>(planNotes);
        boolean ahaPicked = picks.values().stream().anyMatch(p -> p.product().hasMainActive(AHA));
        if (ahaPicked && !notes.contains(Note.AHA_SUNBURN_ALERT)) {
            notes.add(Note.AHA_SUNBURN_ALERT); // FDA alert applies to any AHA product, e.g. a glycolic cleanser
        }
        int total = total(lists, chosen, owned);
        if (budgetCents != null && total > budgetCents) {
            notes.add(Note.OVER_BUDGET);
        }
        return new Routine(picks, treatmentActive, total, budgetCents, MonthlyCost.estimate(picks), notes,
                plan.avoided());
    }

    /** The cheaper choice that gives up the fewest ranking places per dollar saved, or null if none. */
    private static Map<Step, Integer> bestSwap(Map<Step, List<RoutineProduct>> lists, Map<Step, Integer> chosen,
                                               Set<RoutineProduct> owned) {
        int current = total(lists, chosen, owned);
        Map<Step, Integer> best = null;
        double bestScore = 0;
        for (Step step : chosen.keySet()) {
            RoutineProduct from = lists.get(step).get(chosen.get(step));
            for (int i = chosen.get(step) + 1; i < lists.get(step).size(); i++) {
                Map<Step, Integer> next = replace(lists, chosen, from, lists.get(step).get(i), step);
                int saved = current - total(lists, next, owned);
                if (saved <= 0) {
                    continue;
                }
                double score = (double) saved / placesGivenUp(chosen, next);
                if (score > bestScore) {
                    bestScore = score;
                    best = next;
                }
                break; // the best-ranked cheaper option for this step is the only one worth comparing
            }
        }
        return best;
    }

    /** Swaps {@code from} for {@code to} in {@code step}, and in any other step that uses {@code from} too. */
    private static Map<Step, Integer> replace(Map<Step, List<RoutineProduct>> lists, Map<Step, Integer> chosen,
                                              RoutineProduct from, RoutineProduct to, Step step) {
        Map<Step, Integer> next = new EnumMap<>(chosen);
        next.put(step, lists.get(step).indexOf(to));
        chosen.forEach((other, index) -> {
            int toIndex = lists.get(other).indexOf(to);
            if (other != step && lists.get(other).get(index).equals(from) && toIndex >= 0) {
                next.put(other, toIndex);
            }
        });
        return next;
    }

    private static int placesGivenUp(Map<Step, Integer> before, Map<Step, Integer> after) {
        return after.keySet().stream().mapToInt(s -> Math.abs(after.get(s) - before.get(s))).sum();
    }

    /** Upfront cost: each product once, and nothing for products the client already owns. */
    private static int total(Map<Step, List<RoutineProduct>> lists, Map<Step, Integer> chosen,
                             Set<RoutineProduct> owned) {
        return chosen.entrySet().stream()
                .map(e -> lists.get(e.getKey()).get(e.getValue()))
                .distinct()
                .filter(p -> !owned.contains(p))
                .map(RoutineProduct::cheapestPriceCents)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }
}
