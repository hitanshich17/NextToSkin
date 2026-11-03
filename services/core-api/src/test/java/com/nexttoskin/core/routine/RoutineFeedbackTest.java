package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.feedback.Feedback;
import com.nexttoskin.core.feedback.Feedback.Reason;
import com.nexttoskin.core.feedback.Feedback.Verdict;
import com.nexttoskin.core.ingredient.IngredientTag;
import com.nexttoskin.core.routine.QuizAnswers.ActivesExperience;
import com.nexttoskin.core.routine.QuizAnswers.Concern;
import com.nexttoskin.core.routine.QuizAnswers.Pregnancy;
import com.nexttoskin.core.routine.QuizAnswers.Reactivity;
import com.nexttoskin.core.routine.QuizAnswers.SkinType;
import com.nexttoskin.core.routine.RoutinePlan.Note;
import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** "Tried it?" feedback in the routine (docs/feedback.md). */
class RoutineFeedbackTest {

    /** A product with tags (tag, position pairs) and, optionally, named ingredients. */
    private static RoutineProduct p(String id, String category, int priceCents, Set<String> ingredients,
                                    Object... tagPositions) {
        Map<IngredientTag, Integer> positions = new HashMap<>();
        for (int i = 0; i < tagPositions.length; i += 2) {
            positions.put((IngredientTag) tagPositions[i], (Integer) tagPositions[i + 1]);
        }
        Integer spf = category.equals("sunscreen") ? 50 : null;
        return new RoutineProduct(id, "Brand", id.replace('-', ' '), category, spf, false, positions,
                new RoutineProduct.Offer("brand", priceCents, BigDecimal.valueOf(100), "ml", "https://example.com/" + id),
                ingredients);
    }

    private static RoutineProduct p(String id, String category, int priceCents, Object... tagPositions) {
        return p(id, category, priceCents, Set.of("Water", "Glycerin"), tagPositions);
    }

    static final RoutineProduct GEL_CLEANSER = p("gel-cleanser", "cleanser", 900);
    static final RoutineProduct CREAM_CLEANSER = p("cream-cleanser", "cleanser", 1100);
    static final RoutineProduct FRAGRANT_CLEANSER = p("fragrant-cleanser", "cleanser", 700, FRAGRANCE, 9);
    static final RoutineProduct NIACINAMIDE = p("niacinamide", "treatment", 600, IngredientTag.NIACINAMIDE, 2);
    static final RoutineProduct BHA_LIQUID = p("bha-liquid", "treatment", 3000, BHA, 3);
    static final RoutineProduct WATER_GEL = p("water-gel", "moisturizer", 1500);
    static final RoutineProduct RICH_CREAM = p("rich-cream", "moisturizer", 1200);
    static final RoutineProduct FRAGRANT_CREAM = p("fragrant-cream", "moisturizer", 800, FRAGRANCE, 20);
    static final RoutineProduct SPF = p("daily-spf", "sunscreen", 1600, CHEMICAL_UV_FILTER, 0);
    static final RoutineProduct CHEAP_SPF = p("cheap-spf", "sunscreen", 1000, CHEMICAL_UV_FILTER, 0);

    static final List<RoutineProduct> CATALOG = List.of(GEL_CLEANSER, CREAM_CLEANSER, FRAGRANT_CLEANSER, NIACINAMIDE,
            BHA_LIQUID, WATER_GEL, RICH_CREAM, FRAGRANT_CREAM, SPF, CHEAP_SPF);

    /** Oiliness: niacinamide first, then BHA. Normal skin: no texture preference, so price decides. */
    private static QuizAnswers oiliness() {
        return new QuizAnswers(SkinType.NORMAL, List.of(Concern.OILINESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null, null, null);
    }

    private static TriedProducts tried(Feedback... feedback) {
        return TriedProducts.from(List.of(feedback));
    }

    private static Feedback liked(RoutineProduct p) {
        return new Feedback(p.id(), Verdict.LIKED, null);
    }

    private static Feedback disliked(RoutineProduct p, Reason reason) {
        return new Feedback(p.id(), Verdict.DISLIKED, reason);
    }

    private static RoutinePlan plan(List<RoutineProduct> catalog, TriedProducts tried) {
        return RoutineRules.plan(oiliness(), catalog, null, tried);
    }

    private static List<String> ids(RoutinePlan plan, Step step) {
        return plan.candidates().get(step).stream().map(RoutineProduct::id).toList();
    }

    @Test
    void withoutFeedbackTheCheapestFitsComeFirst() {
        var plan = plan(CATALOG, TriedProducts.NONE);
        assertThat(ids(plan, Step.AM_CLEANSER)).first().isEqualTo("fragrant-cleanser");
        assertThat(ids(plan, Step.AM_MOISTURIZER)).first().isEqualTo("fragrant-cream");
        assertThat(plan.avoided()).isEmpty();
    }

    @Test
    void aDislikedProductNeverComesBack() {
        var plan = plan(CATALOG, tried(disliked(CHEAP_SPF, Reason.TOO_PRICEY)));
        assertThat(plan.candidates().values()).allSatisfy(list -> assertThat(list).doesNotContain(CHEAP_SPF));
    }

    @Test
    void irritationExcludesItsIrritantsFromEveryStep() {
        // A reaction to a fragranced cleanser rules out the fragranced moisturizer too.
        var plan = plan(CATALOG, tried(disliked(FRAGRANT_CLEANSER, Reason.IRRITATED)));

        assertThat(plan.candidates().values()).allSatisfy(list -> assertThat(list).noneMatch(p -> p.has(FRAGRANCE)));
        assertThat(plan.avoided()).containsExactly("Fragrance");
    }

    @Test
    void aDislikeWithoutAReasonCountsAsIrritation() {
        var plan = plan(CATALOG, tried(new Feedback(FRAGRANT_CLEANSER.id(), Verdict.DISLIKED, null)));
        assertThat(ids(plan, Step.AM_MOISTURIZER)).doesNotContain("fragrant-cream");
    }

    @Test
    void irritationExcludesTheProductsMainActiveButNotTraces() {
        var bhaCleanser = p("bha-cleanser", "cleanser", 800, BHA, 0);
        var traceBhaCream = p("trace-bha-cream", "moisturizer", 700, BHA, 25);
        var catalog = new java.util.ArrayList<>(CATALOG);
        catalog.addAll(List.of(bhaCleanser, traceBhaCream));

        var plan = plan(catalog, tried(disliked(bhaCleanser, Reason.IRRITATED)));

        assertThat(ids(plan, Step.PM_TREATMENT)).doesNotContain("bha-liquid");
        assertThat(ids(plan, Step.AM_MOISTURIZER)).contains("trace-bha-cream");
        assertThat(plan.avoided()).containsExactly("Salicylic acid");
    }

    @Test
    void ingredientsSharedByTwoReactionsAreSuspects() {
        var lanolinCleanser = p("lanolin-cleanser", "cleanser", 800, Set.of("Water", "Glycerin", "Lanolin", "Lecithin"));
        var lanolinCream = p("lanolin-cream", "moisturizer", 900, Set.of("Water", "Glycerin", "Lanolin", "Lecithin"));
        var lanolinSpf = p("lanolin-spf", "sunscreen", 900, Set.of("Water", "Lanolin"), CHEMICAL_UV_FILTER, 0);
        var likedWithLecithin = p("lecithin-gel", "moisturizer", 1000, Set.of("Water", "Lecithin"));
        var catalog = new java.util.ArrayList<>(CATALOG);
        catalog.addAll(List.of(lanolinCleanser, lanolinCream, lanolinSpf, likedWithLecithin));

        var plan = plan(catalog, tried(disliked(lanolinCleanser, Reason.IRRITATED),
                disliked(lanolinCream, Reason.IRRITATED), liked(likedWithLecithin)));

        // Lanolin is in both reactions and nothing liked; lecithin is in a liked product; water is everywhere.
        assertThat(plan.avoided()).containsExactly("Lanolin");
        assertThat(ids(plan, Step.AM_SUNSCREEN)).doesNotContain("lanolin-spf");
        assertThat(ids(plan, Step.AM_MOISTURIZER)).contains("lecithin-gel");
    }

    @Test
    void oneReactionIsNotEnoughToSuspectAPlainIngredient() {
        var lanolinCleanser = p("lanolin-cleanser", "cleanser", 800, Set.of("Water", "Lanolin"));
        var lanolinSpf = p("lanolin-spf", "sunscreen", 900, Set.of("Water", "Lanolin"), CHEMICAL_UV_FILTER, 0);
        var catalog = new java.util.ArrayList<>(CATALOG);
        catalog.addAll(List.of(lanolinCleanser, lanolinSpf));

        var plan = plan(catalog, tried(disliked(lanolinCleanser, Reason.IRRITATED)));
        assertThat(ids(plan, Step.AM_SUNSCREEN)).contains("lanolin-spf");
    }

    @Test
    void didntWorkTreatmentMovesToTheNextActive() {
        var plan = plan(CATALOG, tried(disliked(NIACINAMIDE, Reason.DIDNT_WORK)));
        assertThat(plan.treatmentActive()).isEqualTo(BHA);
    }

    @Test
    void didntWorkKeepsTheActiveWhenThereIsNoOther() {
        var otherNiacinamide = p("other-niacinamide", "treatment", 900, IngredientTag.NIACINAMIDE, 3);
        var plan = plan(List.of(NIACINAMIDE, otherNiacinamide, SPF), tried(disliked(NIACINAMIDE, Reason.DIDNT_WORK)));
        assertThat(ids(plan, Step.PM_TREATMENT)).containsExactly("other-niacinamide");
    }

    @Test
    void textureDislikeMovesThatTextureToTheEnd() {
        var plan = plan(CATALOG, tried(disliked(RICH_CREAM, Reason.TEXTURE)));
        // fragrant-cream is also a "cream" (rich), so the gel comes first.
        assertThat(ids(plan, Step.AM_MOISTURIZER)).containsExactly("water-gel", "fragrant-cream");
        assertThat(ids(plan, Step.PM_MOISTURIZER)).first().isEqualTo("water-gel");
    }

    @Test
    void tooPriceyKeepsOnlyCheaperProducts() {
        var plan = plan(CATALOG, tried(disliked(GEL_CLEANSER, Reason.TOO_PRICEY)));
        assertThat(ids(plan, Step.AM_CLEANSER)).containsExactly("fragrant-cleanser");
    }

    @Test
    void likedProductIsKeptInBothStepsAndOwned() {
        var plan = plan(CATALOG, tried(liked(WATER_GEL)));

        assertThat(ids(plan, Step.AM_MOISTURIZER)).first().isEqualTo("water-gel");
        assertThat(ids(plan, Step.PM_MOISTURIZER)).first().isEqualTo("water-gel");
        assertThat(plan.owned()).containsExactly(WATER_GEL);
    }

    @Test
    void likedProductCostsNothingUpfrontButCountsMonthly() {
        var plan = plan(CATALOG, tried(liked(WATER_GEL)));
        Routine routine = RoutineSelector.select(plan, null);
        Routine without = RoutineSelector.select(plan(CATALOG, TriedProducts.NONE), null);

        assertThat(routine.picks().get(Step.AM_MOISTURIZER).owned()).isTrue();
        assertThat(routine.totalCents()).isEqualTo(without.totalCents() - FRAGRANT_CREAM.cheapestPriceCents());
        assertThat(routine.monthlyCents()).isGreaterThan(0);
    }

    @Test
    void likedTreatmentChoosesTheTreatmentActive() {
        var plan = plan(CATALOG, tried(liked(BHA_LIQUID)));
        assertThat(plan.treatmentActive()).isEqualTo(BHA);
        assertThat(ids(plan, Step.PM_TREATMENT)).first().isEqualTo("bha-liquid");
    }

    @Test
    void likedProductThatIsNoLongerSafeIsLeftOutWithANote() {
        var retinol = p("retinol", "treatment", 2000, RETINOID, 20);
        var pregnant = new QuizAnswers(SkinType.NORMAL, List.of(Concern.FINE_LINES), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.YES, null, null, null);

        var plan = RoutineRules.plan(pregnant, List.of(retinol, GEL_CLEANSER, WATER_GEL, SPF), null, tried(liked(retinol)));

        assertThat(plan.candidates().values()).allSatisfy(list -> assertThat(list).doesNotContain(retinol));
        assertThat(plan.notes()).contains(Note.LIKED_PRODUCT_LEFT_OUT, Note.NO_RETINOIDS_IN_PREGNANCY);
    }

    @Test
    void stepWithNothingLeftIsReported() {
        var fragrantSpf = p("fragrant-spf", "sunscreen", 1200, FRAGRANCE, 15, CHEMICAL_UV_FILTER, 0);
        var plan = plan(List.of(GEL_CLEANSER, FRAGRANT_CLEANSER, WATER_GEL, NIACINAMIDE, fragrantSpf),
                tried(disliked(FRAGRANT_CLEANSER, Reason.IRRITATED)));

        assertThat(plan.candidates().get(Step.AM_SUNSCREEN)).isEmpty();
        assertThat(plan.notes()).contains(Note.NO_PRODUCT_FITS_STEP);
    }

    // ---- Similarity to liked products (pgvector scores) ----

    private static TriedProducts similarity(Map<String, Double> scores) {
        return new TriedProducts(Set.of(), Map.of(), scores);
    }

    @Test
    void similarityComesBeforePrice() {
        // Equal fit for normal skin; the gel is pricier but much like something the client liked.
        var plan = plan(CATALOG, similarity(Map.of("water-gel", 0.8, "rich-cream", 0.1)));
        assertThat(ids(plan, Step.AM_MOISTURIZER)).first().isEqualTo("water-gel");
    }

    @Test
    void tinySimilarityDifferencesAreATieAndPriceDecides() {
        var plan = plan(CATALOG, similarity(Map.of("water-gel", 0.42, "rich-cream", 0.41, "fragrant-cream", 0.38)));
        assertThat(ids(plan, Step.AM_MOISTURIZER)).first().isEqualTo("fragrant-cream"); // all round to 0.4
    }

    @Test
    void similarityNeverBeatsTheRules() {
        // Oily skin wants light textures: a rich cream stays behind the gel however similar it is.
        var oily = new QuizAnswers(SkinType.OILY, List.of(Concern.OILINESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null, null, null);
        var plan = RoutineRules.plan(oily, CATALOG, null, similarity(Map.of("rich-cream", 0.9)));
        assertThat(ids(plan, Step.AM_MOISTURIZER)).first().isEqualTo("water-gel");
    }
}
