package com.nexttoskin.core.explanation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The explanation built from fixed sentences, used whenever the LLM can't give one. */
final class ExplanationTemplate {

    private ExplanationTemplate() {
    }

    static String explain(ExplanationFacts facts) {
        List<String> sentences = new ArrayList<>();
        String skin = facts.skinType().equals("not sure") ? "your skin" : facts.skinType() + " skin";
        sentences.add("This routine is built for " + skin + ", with " + and(facts.concerns()) + " as your main "
                + (facts.concerns().size() > 1 ? "concerns." : "concern."));
        String mainConcern = facts.concerns().get(0);
        facts.steps().stream().filter(s -> s.step().equals("treatment")).findFirst().ifPresentOrElse(
                t -> sentences.add("Your night treatment, " + t.product() + ", uses " + facts.treatmentActive()
                        + (facts.treatmentChangedForBudget()
                        ? ", the next-best option for " + mainConcern + " that fits your budget."
                        : ", one of the best-supported options for " + mainConcern + ".")),
                () -> sentences.add("No treatment in our catalog fits all your answers, so your night routine keeps "
                        + "to cleansing and moisturizing."));
        if (!facts.climate().isEmpty()) {
            sentences.add("The products are also chosen for the " + and(facts.climate()) + " where you live.");
        }
        List<String> leftOut = Stream.concat(facts.avoid().stream(),
                facts.avoidedForYou().stream().map(String::toLowerCase)).distinct().toList();
        if (!leftOut.isEmpty()) {
            sentences.add("Every product leaves out " + and(leftOut) + ".");
        }
        if (facts.steps().stream().anyMatch(ExplanationFacts.StepFacts::alreadyOwned)) {
            sentences.add("Products you liked are kept and not counted in the price.");
        }
        return String.join(" ", sentences);
    }

    private static String and(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }
}
