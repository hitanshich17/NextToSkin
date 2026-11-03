package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.ingredient.IngredientTag;
import com.nexttoskin.core.routine.QuizAnswers.ActivesExperience;
import com.nexttoskin.core.routine.QuizAnswers.Avoid;
import com.nexttoskin.core.routine.QuizAnswers.Concern;
import com.nexttoskin.core.routine.QuizAnswers.Pregnancy;
import com.nexttoskin.core.routine.QuizAnswers.Reactivity;
import com.nexttoskin.core.routine.QuizAnswers.SkinType;
import com.nexttoskin.core.routine.RoutinePlan.Note;
import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One test per rule in docs/routine-rules.md. Products are small hand-made fixtures. */
class RoutineRulesTest {

    /** Builds a product; tags are given as tag, position pairs (position 0 = declared OTC active). */
    private static RoutineProduct p(String id, String category, int priceCents, Object... tagPositions) {
        return build(id, category, null, priceCents, tagPositions);
    }

    private static RoutineProduct spf(String id, int spf, int priceCents, Object... tagPositions) {
        return build(id, "sunscreen", spf, priceCents, tagPositions);
    }

    private static RoutineProduct build(String id, String category, Integer spf, int priceCents, Object... tagPositions) {
        Map<IngredientTag, Integer> positions = new HashMap<>();
        for (int i = 0; i < tagPositions.length; i += 2) {
            positions.put((IngredientTag) tagPositions[i], (Integer) tagPositions[i + 1]);
        }
        return new RoutineProduct(id, "Brand", id.replace('-', ' '), category, spf, false, positions,
                new RoutineProduct.Offer("brand", priceCents, java.math.BigDecimal.valueOf(100), "ml",
                        "https://example.com/" + id));
    }

    // Catalog fixtures
    static final RoutineProduct GENTLE_CLEANSER = p("gentle-cleanser", "cleanser", 1000, CERAMIDE, 6);
    static final RoutineProduct BP_CLEANSER = p("bp-cleanser", "cleanser", 1200, BENZOYL_PEROXIDE, 0, AHA, 13);
    static final RoutineProduct FRAGRANT_CLEANSER = p("fragrant-cleanser", "cleanser", 800, FRAGRANCE, 9);
    static final RoutineProduct ADAPALENE = p("adapalene-gel", "treatment", 1500, RETINOID, 0);
    static final RoutineProduct STRONG_RETINOL = p("strong-retinol", "treatment", 7600, RETINOID, 12, NUT, 13);
    static final RoutineProduct GENTLE_RETINOL = p("gentle-retinol", "treatment", 1500, RETINOID, 23, SOY, 18);
    static final RoutineProduct AZELAIC = p("azelaic", "treatment", 1200, AZELAIC_ACID, 4);
    static final RoutineProduct VITC_PURE = p("vitc-pure", "treatment", 7900, VITAMIN_C, 3, L_ASCORBIC_ACID, 3);
    static final RoutineProduct VITC_GENTLE = p("vitc-gentle", "treatment", 2000, VITAMIN_C, 2);
    static final RoutineProduct NIACINAMIDE_SERUM = p("niacinamide", "treatment", 600, NIACINAMIDE, 2, VITAMIN_C, 38);
    static final RoutineProduct AHA_SERUM = p("aha-serum", "treatment", 1300, AHA, 3);
    static final RoutineProduct CERAMIDE_SERUM = p("ceramide-serum", "treatment", 900, CERAMIDE, 5);
    static final RoutineProduct LIGHT_GEL = p("water-gel", "moisturizer", 1900, HYALURONIC_ACID, 6);
    static final RoutineProduct RICH_CREAM = p("rich-cream", "moisturizer", 1600, CERAMIDE, 9);
    static final RoutineProduct FRAGRANT_CREAM = p("fragrant-cream", "moisturizer", 1200, FRAGRANCE, 20);
    static final RoutineProduct BHA_CREAM = p("bha-cream", "moisturizer", 1100, BHA, 5);
    static final RoutineProduct CHEMICAL_SPF = spf("chemical-spf", 50, 1000, CHEMICAL_UV_FILTER, 0);
    static final RoutineProduct MINERAL_SPF = spf("mineral-spf", 30, 1600);
    static final RoutineProduct TINTED_SPF = spf("tinted-spf", 50, 3000, IRON_OXIDE, 8);

    static final List<RoutineProduct> CATALOG = List.of(GENTLE_CLEANSER, BP_CLEANSER, FRAGRANT_CLEANSER, ADAPALENE,
            STRONG_RETINOL, GENTLE_RETINOL, AZELAIC, VITC_PURE, VITC_GENTLE, NIACINAMIDE_SERUM, AHA_SERUM,
            CERAMIDE_SERUM, LIGHT_GEL, RICH_CREAM, FRAGRANT_CREAM, BHA_CREAM, CHEMICAL_SPF, MINERAL_SPF, TINTED_SPF);

    private static QuizAnswers answers(SkinType type, List<Concern> concerns, Reactivity reactivity, Set<Avoid> avoid,
                                       ActivesExperience experience, Pregnancy pregnancy, Integer skinTone) {
        return new QuizAnswers(type, concerns, reactivity, avoid, experience, pregnancy, null, null, skinTone);
    }

    private static QuizAnswers simple(Concern... concerns) {
        return answers(SkinType.NORMAL, List.of(concerns), Reactivity.RARELY, Set.of(), ActivesExperience.A_LITTLE,
                Pregnancy.NO, null);
    }

    private static List<String> ids(RoutinePlan plan, Step step) {
        return plan.candidates().get(step).stream().map(RoutineProduct::id).toList();
    }

    @Test
    void breakoutsGetARetinoidMatchedToExperience() {
        var never = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.BREAKOUTS), Reactivity.RARELY,
                Set.of(), ActivesExperience.NEVER, Pregnancy.NO, null), CATALOG);
        assertThat(never.treatmentActive()).isEqualTo(RETINOID);
        assertThat(ids(never, Step.PM_TREATMENT)).containsExactly("gentle-retinol");
        assertThat(never.notes()).contains(Note.RETINOID_START_SLOWLY);

        var aLittle = RoutineRules.plan(simple(Concern.BREAKOUTS), CATALOG);
        assertThat(ids(aLittle, Step.PM_TREATMENT)).containsExactly("gentle-retinol", "strong-retinol");

        var regularly = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.BREAKOUTS), Reactivity.RARELY,
                Set.of(), ActivesExperience.REGULARLY, Pregnancy.NO, null), CATALOG);
        assertThat(ids(regularly, Step.PM_TREATMENT)).contains("adapalene-gel");
    }

    @Test
    void pregnancyRemovesRetinoidsEverywhereAndPrefersMineralSunscreen() {
        var plan = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.BREAKOUTS), Reactivity.RARELY,
                Set.of(), ActivesExperience.REGULARLY, Pregnancy.YES, null), CATALOG);

        assertThat(plan.treatmentActive()).isEqualTo(AZELAIC_ACID);
        assertThat(plan.candidates().values()).allSatisfy(products ->
                assertThat(products).noneMatch(p -> p.has(RETINOID)));
        assertThat(ids(plan, Step.AM_SUNSCREEN)).containsExactly("mineral-spf", "tinted-spf");
        assertThat(plan.notes()).contains(Note.NO_RETINOIDS_IN_PREGNANCY);
    }

    @Test
    void retinoidNightKeepsAcneCleansersAndLeaveOnExfoliantsApart() {
        var aLittle = RoutineRules.plan(simple(Concern.BREAKOUTS), CATALOG);
        assertThat(ids(aLittle, Step.PM_CLEANSER)).doesNotContain("bp-cleanser");
        assertThat(ids(aLittle, Step.AM_CLEANSER)).doesNotContain("bp-cleanser");
        assertThat(ids(aLittle, Step.AM_MOISTURIZER)).doesNotContain("bha-cream");

        var regularly = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.BREAKOUTS), Reactivity.RARELY,
                Set.of(), ActivesExperience.REGULARLY, Pregnancy.NO, null), CATALOG);
        assertThat(ids(regularly, Step.AM_CLEANSER)).first().isEqualTo("bp-cleanser"); // acne cleanser first
        assertThat(ids(regularly, Step.PM_CLEANSER)).doesNotContain("bp-cleanser");
    }

    @Test
    void rednessFollowsTheAadRosaceaList() {
        var plan = RoutineRules.plan(simple(Concern.REDNESS), CATALOG);

        assertThat(plan.treatmentActive()).isEqualTo(AZELAIC_ACID);
        assertThat(plan.candidates().values()).allSatisfy(products ->
                assertThat(products).noneMatch(p -> p.has(FRAGRANCE) || p.has(AHA)));
        assertThat(ids(plan, Step.AM_SUNSCREEN)).containsExactly("mineral-spf", "tinted-spf");
    }

    @Test
    void reactiveSkinGetsGentleVitaminCAndNoStrongActives() {
        var plan = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.DARK_SPOTS), Reactivity.OFTEN,
                Set.of(), ActivesExperience.REGULARLY, Pregnancy.NO, null), CATALOG);

        assertThat(plan.treatmentActive()).isEqualTo(VITAMIN_C);
        assertThat(ids(plan, Step.PM_TREATMENT)).containsExactly("vitc-gentle");
        assertThat(plan.notes()).contains(Note.VITAMIN_C_NEEDS_SUNSCREEN);
        assertThat(ids(plan, Step.AM_CLEANSER)).doesNotContain("fragrant-cleanser", "bp-cleanser");
    }

    @Test
    void minorActivesDoNotMakeATreatment() {
        // The niacinamide serum lists vitamin C at position 38: a preservative, not a vitamin C treatment.
        var plan = RoutineRules.plan(simple(Concern.DARK_SPOTS), CATALOG);
        assertThat(ids(plan, Step.PM_TREATMENT)).doesNotContain("niacinamide");
    }

    @Test
    void ahaTreatmentShowsTheFdaSunburnAlert() {
        var plan = RoutineRules.plan(simple(Concern.DULLNESS), CATALOG);
        assertThat(plan.treatmentActive()).isEqualTo(AHA);
        assertThat(plan.notes()).contains(Note.AHA_SUNBURN_ALERT);
    }

    @Test
    void flakyPatchesGetBarrierRepairAndTheDermatologistNote() {
        var plan = RoutineRules.plan(simple(Concern.FLAKY_PATCHES), CATALOG);

        assertThat(plan.treatmentActive()).isEqualTo(CERAMIDE);
        assertThat(plan.candidates().values()).allSatisfy(products -> assertThat(products)
                .noneMatch(p -> p.hasMainActive(AHA) || p.hasMainActive(BHA) || p.hasMainActive(RETINOID)
                        || p.has(FRAGRANCE)));
        assertThat(plan.notes()).contains(Note.SEE_DERMATOLOGIST_FOR_PATCHES);
    }

    @Test
    void drySkinGetsFragranceFreeRichMoisturizersFirst() {
        var plan = RoutineRules.plan(answers(SkinType.DRY, List.of(Concern.DRYNESS), Reactivity.RARELY,
                Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO, null), CATALOG);
        assertThat(ids(plan, Step.AM_MOISTURIZER)).doesNotContain("fragrant-cream");
        assertThat(plan.candidates().get(Step.AM_MOISTURIZER).getFirst().texture())
                .isEqualTo(RoutineProduct.Texture.RICH);

        var oily = RoutineRules.plan(answers(SkinType.OILY, List.of(Concern.OILINESS), Reactivity.RARELY,
                Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO, null), CATALOG);
        assertThat(ids(oily, Step.AM_MOISTURIZER)).first().isEqualTo("water-gel");
    }

    @Test
    void avoidListIsAHardFilter() {
        var plan = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.FINE_LINES), Reactivity.RARELY,
                Set.of(Avoid.NUTS, Avoid.SOY), ActivesExperience.A_LITTLE, Pregnancy.NO, null), CATALOG);
        // Both retinols contain nuts or soy, so fine lines fall back to vitamin C.
        assertThat(plan.treatmentActive()).isEqualTo(VITAMIN_C);
    }

    @Test
    void tintedSunscreenFirstForDiscolorationAndDeeperSkinTones() {
        assertThat(ids(RoutineRules.plan(simple(Concern.UNEVEN_TONE), CATALOG), Step.AM_SUNSCREEN))
                .first().isEqualTo("tinted-spf");
        var deeperTone = answers(SkinType.NORMAL, List.of(Concern.OILINESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, 5);
        assertThat(ids(RoutineRules.plan(deeperTone, CATALOG), Step.AM_SUNSCREEN)).first().isEqualTo("tinted-spf");
        assertThat(ids(RoutineRules.plan(simple(Concern.OILINESS), CATALOG), Step.AM_SUNSCREEN))
                .first().isEqualTo("chemical-spf"); // otherwise cheapest first
    }

    @Test
    void noFittingTreatmentIsReportedNotHidden() {
        var plan = RoutineRules.plan(simple(Concern.UNEVEN_TONE), List.of(GENTLE_CLEANSER, RICH_CREAM, MINERAL_SPF));
        assertThat(plan.treatmentActive()).isNull();
        assertThat(plan.candidates().get(Step.PM_TREATMENT)).isEmpty();
        assertThat(plan.notes()).contains(Note.NO_TREATMENT_FITS);
    }

    @Test
    void sameProductRepeatsWhenItFitsAmAndPm() {
        var plan = RoutineRules.plan(simple(Concern.DULLNESS), CATALOG);
        assertThat(ids(plan, Step.AM_CLEANSER).get(0)).isEqualTo(ids(plan, Step.PM_CLEANSER).get(0));
        assertThat(ids(plan, Step.AM_MOISTURIZER).get(0)).isEqualTo(ids(plan, Step.PM_MOISTURIZER).get(0));
    }

    @Test
    void amAndPmDifferWhenNoProductFitsBoth() {
        // Experienced user on a retinoid night: the benzoyl peroxide cleanser is AM-only.
        var plan = RoutineRules.plan(answers(SkinType.OILY, List.of(Concern.BREAKOUTS), Reactivity.RARELY, Set.of(),
                ActivesExperience.REGULARLY, Pregnancy.NO, null), CATALOG);
        assertThat(ids(plan, Step.AM_CLEANSER)).first().isEqualTo("bp-cleanser");
        assertThat(ids(plan, Step.PM_CLEANSER)).doesNotContain("bp-cleanser");
    }

    @Test
    void productsWithoutAnOfferAreNeverCandidates() {
        var noOffer = new RoutineProduct("no-offer", "Brand", "no offer", "cleanser", null, false, Map.of(), null);
        var plan = RoutineRules.plan(simple(Concern.DULLNESS), List.of(noOffer, GENTLE_CLEANSER));
        assertThat(ids(plan, Step.AM_CLEANSER)).containsExactly("gentle-cleanser");
    }

    // ---- Climate (docs/routine-rules.md, section 6) ----

    static final com.nexttoskin.core.climate.Climate MILD = new com.nexttoskin.core.climate.Climate("Seattle", "Washington", 4.2, 10.3, 78.7, 5.0);
    static final com.nexttoskin.core.climate.Climate HUMID = new com.nexttoskin.core.climate.Climate("Miami", "Florida", 6.9, 23.6, 83.3, 6.2);
    static final com.nexttoskin.core.climate.Climate DRY = new com.nexttoskin.core.climate.Climate("Minneapolis", "Minnesota", 1.5, -14.4, 78.1, 7.0);
    static final com.nexttoskin.core.climate.Climate SUNNY_SMOGGY = new com.nexttoskin.core.climate.Climate("Phoenix", "Arizona", 9.5, 14.5, 38.4, 10.4);

    @Test
    void veryHighUvPutsSpf50First() {
        List<RoutineProduct> sunscreens = List.of(MINERAL_SPF, TINTED_SPF); // SPF 30 cheaper, SPF 50 pricier
        assertThat(ids(RoutineRules.plan(simple(Concern.OILINESS), sunscreens, MILD), Step.AM_SUNSCREEN))
                .containsExactly("mineral-spf", "tinted-spf");
        var sunny = RoutineRules.plan(simple(Concern.OILINESS), sunscreens, SUNNY_SMOGGY);
        assertThat(ids(sunny, Step.AM_SUNSCREEN)).containsExactly("tinted-spf", "mineral-spf");
        assertThat(sunny.notes()).contains(Note.HIGH_UV_REAPPLY);
    }

    @Test
    void humidCityPutsLightMoisturizersFirstForNormalSkin() {
        assertThat(ids(RoutineRules.plan(simple(Concern.OILINESS), CATALOG, MILD), Step.AM_MOISTURIZER).get(0))
                .isNotEqualTo("water-gel");
        assertThat(ids(RoutineRules.plan(simple(Concern.OILINESS), CATALOG, HUMID), Step.AM_MOISTURIZER))
                .first().isEqualTo("water-gel");
    }

    @Test
    void dryAirPutsRichMoisturizersFirstForNormalSkin() {
        var cheapGel = p("cheap-water-gel", "moisturizer", 500, HYALURONIC_ACID, 6); // would win on price
        assertThat(ids(RoutineRules.plan(simple(Concern.DULLNESS), List.of(cheapGel, RICH_CREAM), MILD),
                Step.AM_MOISTURIZER)).first().isEqualTo("cheap-water-gel");
        assertThat(ids(RoutineRules.plan(simple(Concern.DULLNESS), List.of(cheapGel, RICH_CREAM), DRY),
                Step.AM_MOISTURIZER)).first().isEqualTo("rich-cream");
    }

    @Test
    void skinTypeWinsOverClimate() {
        var oily = answers(SkinType.OILY, List.of(Concern.OILINESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null);
        assertThat(ids(RoutineRules.plan(oily, CATALOG, DRY), Step.AM_MOISTURIZER)).first().isEqualTo("water-gel");
    }

    @Test
    void pollutedAirAddsANoteOnly() {
        var clean = RoutineRules.plan(simple(Concern.DULLNESS), CATALOG, MILD);
        var smoggy = RoutineRules.plan(simple(Concern.DULLNESS), CATALOG,
                new com.nexttoskin.core.climate.Climate("Fresno", "California", 4.2, 10.3, 60.0, 14.0));
        assertThat(smoggy.notes()).contains(Note.AIR_POLLUTION);
        assertThat(smoggy.candidates()).isEqualTo(clean.candidates());
    }

    @Test
    void cleansingBalmsAndOilsAreNeverTheRoutineCleanser() {
        var balm = p("oat-cleansing-balm", "cleanser", 500);
        var oil = p("pore-cleansing-oil", "cleanser", 600);
        var oilFreeWash = p("oil-free-acne-wash", "cleanser", 700);
        var plan = RoutineRules.plan(simple(Concern.DULLNESS), List.of(balm, oil, oilFreeWash, GENTLE_CLEANSER));
        assertThat(ids(plan, Step.AM_CLEANSER)).containsExactly("oil-free-acne-wash", "gentle-cleanser");
        assertThat(ids(plan, Step.PM_CLEANSER)).containsExactly("oil-free-acne-wash", "gentle-cleanser");
    }

    @Test
    void budgetFallbacksAreTheConcernsNextActivesButNeverARetinoid() {
        // Dark spots: vitamin C, azelaic acid, tranexamic acid, niacinamide, retinoid.
        var plan = RoutineRules.plan(simple(Concern.DARK_SPOTS), CATALOG);

        assertThat(plan.treatmentActive()).isEqualTo(VITAMIN_C);
        assertThat(plan.budgetFallbacks().keySet()).containsExactly(AZELAIC_ACID, NIACINAMIDE);
    }

    @Test
    void anExfoliatingAcidNeverCountsAsAHydratingTreatment() {
        var lacticWithHa = p("lactic-acid-ha", "treatment", 900, AHA, 2, HYALURONIC_ACID, 7);
        var plainHa = p("ha-serum", "treatment", 1500, HYALURONIC_ACID, 3);
        var dryness = answers(SkinType.DRY, List.of(Concern.DRYNESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null);

        var plan = RoutineRules.plan(dryness, List.of(lacticWithHa, plainHa, GENTLE_CLEANSER, RICH_CREAM, MINERAL_SPF));
        assertThat(plan.treatmentActive()).isEqualTo(HYALURONIC_ACID);
        assertThat(ids(plan, Step.PM_TREATMENT)).containsExactly("ha-serum");

        // Without a plain hyaluronic acid serum, dryness gets ceramides or no treatment, never the AHA.
        var withoutHa = RoutineRules.plan(dryness, List.of(lacticWithHa, GENTLE_CLEANSER, RICH_CREAM, MINERAL_SPF));
        assertThat(withoutHa.candidates().get(Step.PM_TREATMENT)).doesNotContain(lacticWithHa);
    }

    // ---- Reasons for the explanation: each mirrors a ranking rule ----

    private static List<String> reasons(QuizAnswers answers, com.nexttoskin.core.climate.Climate climate, Step step,
                                        RoutineProduct p) {
        return RoutineRules.reasons(answers, climate, TriedProducts.NONE, step, p);
    }

    @Test
    void cleanserReasons() {
        var oilyBreakouts = answers(SkinType.OILY, List.of(Concern.BREAKOUTS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null);
        assertThat(reasons(oilyBreakouts, null, Step.AM_CLEANSER, BP_CLEANSER))
                .containsExactly("has benzoyl peroxide for breakouts");
        var foam = p("foam-cleanser", "cleanser", 900);
        assertThat(reasons(oilyBreakouts, null, Step.AM_CLEANSER, foam)).containsExactly("light texture for oily skin");
    }

    @Test
    void moisturizerTextureReasonNamesSkinTypeOrClimate() {
        var dry = answers(SkinType.DRY, List.of(Concern.DRYNESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null);
        assertThat(reasons(dry, null, Step.AM_MOISTURIZER, RICH_CREAM)).containsExactly("rich texture for dry skin");
        assertThat(reasons(simple(Concern.DULLNESS), HUMID, Step.AM_MOISTURIZER, LIGHT_GEL))
                .containsExactly("light texture for the humid air where you live");
        assertThat(reasons(simple(Concern.DULLNESS), null, Step.AM_MOISTURIZER, LIGHT_GEL)).isEmpty();
    }

    @Test
    void sunscreenReasons() {
        var redness = answers(SkinType.NORMAL, List.of(Concern.REDNESS, Concern.DARK_SPOTS), Reactivity.RARELY,
                Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO, null);
        var tintedMineral50 = spf("tinted-mineral", 50, 3000, IRON_OXIDE, 8);
        assertThat(reasons(redness, SUNNY_SMOGGY, Step.AM_SUNSCREEN, tintedMineral50)).containsExactly(
                "SPF 50 for the very strong sun where you live",
                "mineral filters, gentler on redness",
                "tinted with iron oxides, which also block the visible light that can darken spots");
    }

    @Test
    void pregnancyIsNeverAReasonInWords() {
        var pregnant = answers(SkinType.NORMAL, List.of(Concern.DULLNESS), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.YES, null);
        assertThat(reasons(pregnant, null, Step.AM_SUNSCREEN, MINERAL_SPF)).containsExactly("mineral filters");
    }

    @Test
    void treatmentReasonNamesTheSecondConcernItAlsoHelps() {
        var breakoutsAndDarkSpots = answers(SkinType.NORMAL, List.of(Concern.BREAKOUTS, Concern.DARK_SPOTS),
                Reactivity.RARELY, Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO, null);
        assertThat(reasons(breakoutsAndDarkSpots, null, Step.PM_TREATMENT, AZELAIC))
                .containsExactly("also helps with dark spots and marks");
    }

    @Test
    void likedAndSimilarAreReasonsToo() {
        var tried = new TriedProducts(Set.of("water-gel"), Map.of(), Map.of("rich-cream", 0.6));
        assertThat(RoutineRules.reasons(simple(Concern.DULLNESS), null, tried, Step.AM_MOISTURIZER, LIGHT_GEL))
                .containsExactly("you said you liked it");
        assertThat(RoutineRules.reasons(simple(Concern.DULLNESS), null, tried, Step.AM_MOISTURIZER, RICH_CREAM))
                .containsExactly("similar to products you liked");
    }
}
