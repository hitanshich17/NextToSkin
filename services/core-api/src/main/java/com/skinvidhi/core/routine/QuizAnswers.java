package com.skinvidhi.core.routine;

import java.util.List;
import java.util.Set;

/** Answers to the quiz in docs/quiz.md. */
public record QuizAnswers(
        SkinType skinType,
        List<Concern> concerns,
        Reactivity reactivity,
        Set<Avoid> avoid,
        ActivesExperience activesExperience,
        Pregnancy pregnancy,
        Integer budgetCents,
        String city,
        Integer skinTone) {

    public enum SkinType { OILY, DRY, COMBINATION, NORMAL, NOT_SURE }

    public enum Concern {
        BREAKOUTS("breakouts"), DARK_SPOTS("dark spots and marks"), UNEVEN_TONE("uneven tone"),
        FINE_LINES("fine lines"), REDNESS("redness"), DULLNESS("dullness and texture"), DRYNESS("dryness"),
        FLAKY_PATCHES("flaky patches"), OILINESS("oiliness and pores");

        private final String label;

        Concern(String label) {
            this.label = label;
        }

        /** How the concern reads in a sentence. */
        public String label() {
            return label;
        }
    }

    public enum Reactivity { RARELY, SOMETIMES, OFTEN }

    public enum Avoid { FRAGRANCE, ESSENTIAL_OILS, ALCOHOL, NUTS, SOY }

    public enum ActivesExperience { NEVER, A_LITTLE, REGULARLY }

    public enum Pregnancy { YES, NO, PREFER_NOT_TO_SAY }

    public QuizAnswers {
        required(skinType, "skinType");
        required(concerns, "concerns");
        required(reactivity, "reactivity");
        required(activesExperience, "activesExperience");
        required(pregnancy, "pregnancy");
        concerns = List.copyOf(concerns);
        avoid = avoid == null ? Set.of() : Set.copyOf(avoid); // an empty avoid list may be left out
        if (concerns.size() != Set.copyOf(concerns).size()) {
            throw new IllegalArgumentException("concerns must be different");
        }
        if (concerns.isEmpty() || concerns.size() > 2) {
            throw new IllegalArgumentException("pick 1 or 2 concerns");
        }
        if (skinTone != null && (skinTone < 1 || skinTone > 6)) {
            throw new IllegalArgumentException("skin tone is 1 to 6");
        }
        if (budgetCents != null && budgetCents <= 0) {
            throw new IllegalArgumentException("budget must be positive (leave it out for no limit)");
        }
    }

    private static void required(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public boolean has(Concern concern) {
        return concerns.contains(concern);
    }

    public boolean pregnant() {
        return pregnancy == Pregnancy.YES;
    }
}
