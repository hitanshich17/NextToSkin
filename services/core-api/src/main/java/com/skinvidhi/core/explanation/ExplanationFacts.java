package com.skinvidhi.core.explanation;

import static com.skinvidhi.core.ingredient.IngredientTag.*;

import com.skinvidhi.core.climate.Climate;
import com.skinvidhi.core.ingredient.IngredientTag;
import com.skinvidhi.core.routine.QuizAnswers;
import com.skinvidhi.core.routine.Routine;
import com.skinvidhi.core.routine.RoutinePlan.Note;
import com.skinvidhi.core.routine.RoutinePlan.Step;
import com.skinvidhi.core.routine.RoutineProduct;
import com.skinvidhi.core.routine.RoutineRules;
import com.skinvidhi.core.routine.TriedProducts;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the rules decided, in words, for the explanation. Sent to the AI service (and so to the LLM provider), so it
 * leaves out the pregnancy answer and the city, like the stored answers (docs/feedback.md).
 */
public record ExplanationFacts(String skinType, List<String> concerns, String reactivity, List<String> avoid,
                               String activesExperience, List<String> climate, String treatmentActive,
                               boolean treatmentChangedForBudget, List<StepFacts> steps, List<String> avoidedForYou,
                               List<String> notes, boolean withinBudget) {

    /** @param reasons why this product won its step, in the rules' own words (RoutineRules.reasons) */
    public record StepFacts(String time, String step, String product, List<String> keyIngredients,
                            boolean alreadyOwned, List<String> reasons) {
    }

    static final Map<QuizAnswers.Avoid, String> AVOID = Map.of(
            QuizAnswers.Avoid.FRAGRANCE, "fragrance", QuizAnswers.Avoid.ESSENTIAL_OILS, "essential oils",
            QuizAnswers.Avoid.ALCOHOL, "drying alcohol", QuizAnswers.Avoid.NUTS, "nuts", QuizAnswers.Avoid.SOY, "soy");

    static final Map<Climate.Signal, String> CLIMATE = Map.of(
            Climate.Signal.HIGH_UV, "very strong sun", Climate.Signal.HUMID, "humid air",
            Climate.Signal.DRY_AIR, "dry air", Climate.Signal.POLLUTED, "polluted air");

    /** Tags that say what a product does; irritant and filter tags don't. */
    private static final Set<IngredientTag> WHAT_IT_DOES = Set.of(RETINOID, AHA, BHA, VITAMIN_C, BENZOYL_PEROXIDE,
            NIACINAMIDE, TRANEXAMIC_ACID, AZELAIC_ACID, CERAMIDE, HYALURONIC_ACID, PEPTIDE, CENTELLA, UREA);

    /**
     * How actives are named in sentences ("uses a retinoid"); others are their tag in lower case. The AI service
     * looks these names up in its ingredient roles (ai-service/app/explain.py, ROLES): keep the two in step.
     */
    static final Map<IngredientTag, String> ACTIVES = Map.of(
            RETINOID, "a retinoid", AHA, "an AHA (alpha hydroxy acid)", BHA, "salicylic acid (a BHA)",
            VITAMIN_C, "vitamin C", BENZOYL_PEROXIDE, "benzoyl peroxide", PEPTIDE, "peptides",
            CERAMIDE, "ceramides", CENTELLA, "centella");

    /** Notes that would reveal the pregnancy answer stay out. */
    private static final Set<Note> PRIVATE_NOTES = Set.of(Note.NO_RETINOIDS_IN_PREGNANCY);

    public static ExplanationFacts of(QuizAnswers answers, Routine routine, Climate climate) {
        return of(answers, routine, climate, TriedProducts.NONE);
    }

    public static ExplanationFacts of(QuizAnswers answers, Routine routine, Climate climate, TriedProducts tried) {
        List<StepFacts> steps = new ArrayList<>();
        Arrays.stream(Step.values()).filter(routine.picks()::containsKey).forEach(step -> {
            Routine.Pick pick = routine.picks().get(step);
            RoutineProduct p = pick.product();
            List<String> reasons = new ArrayList<>(RoutineRules.reasons(answers, climate, tried, step, p));
            if (pick.cheaperForBudget()) {
                reasons.add("a cheaper option, picked to fit your budget");
            }
            steps.add(new StepFacts(step.name().substring(0, 2), step.category(), p.brand() + " " + p.name(),
                    keyIngredients(p), pick.owned(), reasons));
        });
        return new ExplanationFacts(
                words(answers.skinType()),
                answers.concerns().stream().map(QuizAnswers.Concern::label).toList(),
                words(answers.reactivity()),
                answers.avoid().stream().map(AVOID::get).sorted().toList(),
                words(answers.activesExperience()),
                climate == null ? List.of() : climate.signals().stream().map(CLIMATE::get).sorted().toList(),
                routine.treatmentActive() == null ? null : active(routine.treatmentActive()),
                routine.notes().contains(Note.TREATMENT_CHANGED_FOR_BUDGET),
                steps,
                routine.avoided(),
                routine.notes().stream().filter(n -> !PRIVATE_NOTES.contains(n)).map(Note::text).toList(),
                routine.withinBudget());
    }

    private static List<String> keyIngredients(RoutineProduct p) {
        List<String> key = new ArrayList<>(WHAT_IT_DOES.stream().filter(p::hasMainActive).map(ExplanationFacts::active)
                .sorted().toList());
        if ("sunscreen".equals(p.category())) {
            key.add(p.mineral() ? "mineral filters (zinc oxide, titanium dioxide)" : "chemical UV filters");
            if (p.tinted()) {
                key.add("iron oxides (tint)");
            }
        }
        return key;
    }

    static String active(IngredientTag tag) {
        return ACTIVES.getOrDefault(tag, words(tag));
    }

    static String words(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
