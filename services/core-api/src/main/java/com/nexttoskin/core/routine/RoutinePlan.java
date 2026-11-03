package com.nexttoskin.core.routine;

import com.nexttoskin.core.ingredient.IngredientTag;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranked candidate products for each routine step, before the budget is applied.
 *
 * @param treatmentActive the active the night treatment was chosen for, or null if none fits
 * @param budgetFallbacks treatments for the concern's next actives, in order of preference; used only when the
 *                        routine can't fit the budget otherwise (never retinoids: the other steps weren't filtered
 *                        for a retinoid night)
 * @param owned liked products in the routine: the client likely owns them, so they cost $0 upfront
 * @param avoided what is left out because of the client's reactions, in words (docs/feedback.md)
 */
public record RoutinePlan(Map<Step, List<RoutineProduct>> candidates, IngredientTag treatmentActive, List<Note> notes,
                          Map<IngredientTag, List<RoutineProduct>> budgetFallbacks, Set<RoutineProduct> owned,
                          List<String> avoided) {

    public RoutinePlan {
        candidates = Map.copyOf(candidates);
        notes = List.copyOf(notes);
        budgetFallbacks = Collections.unmodifiableMap(new LinkedHashMap<>(budgetFallbacks)); // keeps the order
        owned = Set.copyOf(owned);
        avoided = List.copyOf(avoided);
    }

    public RoutinePlan(Map<Step, List<RoutineProduct>> candidates, IngredientTag treatmentActive, List<Note> notes,
                       Map<IngredientTag, List<RoutineProduct>> budgetFallbacks) {
        this(candidates, treatmentActive, notes, budgetFallbacks, Set.of(), List.of());
    }

    public RoutinePlan(Map<Step, List<RoutineProduct>> candidates, IngredientTag treatmentActive, List<Note> notes) {
        this(candidates, treatmentActive, notes, Map.of());
    }

    /** The Core 4 routine: AM cleanser, moisturizer, sunscreen; PM cleanser, treatment, moisturizer. */
    public enum Step {
        AM_CLEANSER("cleanser"), AM_MOISTURIZER("moisturizer"), AM_SUNSCREEN("sunscreen"),
        PM_CLEANSER("cleanser"), PM_TREATMENT("treatment"), PM_MOISTURIZER("moisturizer");

        private final String category;

        Step(String category) {
            this.category = category;
        }

        public String category() {
            return category;
        }
    }

    /** Messages shown with the routine. Wording and sources are in docs/routine-rules.md. */
    public enum Note {
        RETINOID_START_SLOWLY("Use your retinoid at night only. Start every other night and build up as your skin "
                + "tolerates it, and wear sunscreen every day."),
        VITAMIN_C_NEEDS_SUNSCREEN("Vitamin C does not protect you from the sun. Use it together with a "
                + "broad-spectrum SPF 30+ sunscreen every morning."),
        AHA_SUNBURN_ALERT("This routine contains an alpha hydroxy acid (AHA), which may increase your skin's "
                + "sensitivity to the sun. Use sunscreen and limit sun exposure while using it and for a week afterwards."),
        SEE_DERMATOLOGIST_FOR_PATCHES("If patches are itchy, scaly, spreading, or don't improve within 2-3 weeks, "
                + "see a dermatologist. This can be a skin condition (such as eczema, psoriasis or seborrheic "
                + "dermatitis) rather than dryness."),
        NO_RETINOIDS_IN_PREGNANCY("Retinoids are left out because they should not be used during pregnancy or "
                + "while breastfeeding."),
        NO_TREATMENT_FITS("No treatment in our catalog fits all your answers, so your night routine has no "
                + "treatment step."),
        HIGH_UV_REAPPLY("The sun is very strong where you live. Reapply sunscreen every 2 hours when outdoors, "
                + "and right after swimming or sweating."),
        AIR_POLLUTION("Air pollution in your city is linked to dark spots and wrinkles. Daily sunscreen and "
                + "cleansing at night are the basics that help."),
        TREATMENT_CHANGED_FOR_BUDGET("To fit your budget, your night treatment is a different type than our first "
                + "choice for your concern. It still targets the same concern."),
        LIKED_PRODUCT_LEFT_OUT("A product you liked isn't in this routine because it doesn't fit your current "
                + "answers."),
        NO_PRODUCT_FITS_STEP("For at least one step, no product in our catalog fits all your answers and past "
                + "reactions, so that step is left empty."),
        OVER_BUDGET("Even the lowest-priced routine that fits your answers costs more than your budget. "
                + "This is that routine.");

        private final String text;

        Note(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        /** The note a treatment with this active always comes with, or null. */
        public static Note forTreatment(IngredientTag active) {
            if (active == null) {
                return null;
            }
            return switch (active) {
                case RETINOID -> RETINOID_START_SLOWLY;
                case VITAMIN_C -> VITAMIN_C_NEEDS_SUNSCREEN;
                case AHA -> AHA_SUNBURN_ALERT;
                default -> null;
            };
        }
    }
}
