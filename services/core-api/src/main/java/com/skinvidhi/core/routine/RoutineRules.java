package com.skinvidhi.core.routine;

import static com.skinvidhi.core.ingredient.IngredientTag.*;

import com.skinvidhi.core.climate.Climate;
import com.skinvidhi.core.climate.Climate.Signal;
import com.skinvidhi.core.feedback.Feedback.Reason;
import com.skinvidhi.core.ingredient.IngredientTag;
import com.skinvidhi.core.routine.QuizAnswers.ActivesExperience;
import com.skinvidhi.core.routine.QuizAnswers.Avoid;
import com.skinvidhi.core.routine.QuizAnswers.Concern;
import com.skinvidhi.core.routine.QuizAnswers.Reactivity;
import com.skinvidhi.core.routine.QuizAnswers.SkinType;
import com.skinvidhi.core.routine.RoutinePlan.Note;
import com.skinvidhi.core.routine.RoutinePlan.Step;
import com.skinvidhi.core.routine.RoutineProduct.Texture;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Turns quiz answers into ranked candidates for each routine step. Every rule here is documented, with its
 * evidence and source, in docs/routine-rules.md; keep the two in step.
 *
 * <p>Pure logic with no database access, so each rule can be unit-tested.
 */
public final class RoutineRules {

    /** Night treatment actives per concern, in order of preference (docs/routine-rules.md, section 1). */
    static final Map<Concern, List<IngredientTag>> TREATMENTS = Map.of(
            Concern.BREAKOUTS, List.of(RETINOID, AZELAIC_ACID, BHA, NIACINAMIDE),
            Concern.DARK_SPOTS, List.of(VITAMIN_C, AZELAIC_ACID, TRANEXAMIC_ACID, NIACINAMIDE, RETINOID),
            Concern.UNEVEN_TONE, List.of(TRANEXAMIC_ACID, AZELAIC_ACID, VITAMIN_C, NIACINAMIDE),
            Concern.FINE_LINES, List.of(RETINOID, VITAMIN_C, PEPTIDE),
            Concern.REDNESS, List.of(AZELAIC_ACID, NIACINAMIDE, CENTELLA),
            Concern.DULLNESS, List.of(AHA, BHA, VITAMIN_C),
            Concern.DRYNESS, List.of(HYALURONIC_ACID, CERAMIDE),
            Concern.FLAKY_PATCHES, List.of(CERAMIDE, HYALURONIC_ACID),
            Concern.OILINESS, List.of(NIACINAMIDE, BHA));

    /** Retinoids listed this early are stronger (e.g. 1% retinol); beginners get the gentler ones. */
    static final int STRONG_RETINOID_MAX_POSITION = 15;

    private static final Set<IngredientTag> FRAGRANCE_FAMILY = EnumSet.of(FRAGRANCE, FRAGRANCE_ALLERGEN, ESSENTIAL_OIL);
    private static final Set<IngredientTag> EXFOLIANTS = EnumSet.of(AHA, BHA);
    /** Hydrating and soothing treatment actives (author's decision, docs/routine-rules.md section 1)... */
    static final Set<IngredientTag> GENTLE_ACTIVES = EnumSet.of(HYALURONIC_ACID, CERAMIDE, CENTELLA, PEPTIDE);
    /** ...which a product with any of these as a main active never counts as. */
    static final Set<IngredientTag> STRONG_ACTIVES = EnumSet.of(AHA, BHA, RETINOID, BENZOYL_PEROXIDE);

    /** Sunscreens at or above this SPF rank first where the UV index is very high. */
    static final int HIGH_UV_MIN_SPF = 50;

    private final QuizAnswers answers;
    private final Climate climate;
    private final TriedProducts tried;

    private RoutineRules(QuizAnswers answers, Climate climate, TriedProducts tried) {
        this.answers = answers;
        this.climate = climate;
        this.tried = tried;
    }

    public static RoutinePlan plan(QuizAnswers answers, List<RoutineProduct> catalog) {
        return plan(answers, catalog, null);
    }

    /** @param climate the city's climate, or null if unknown (no city, or the lookup failed) */
    public static RoutinePlan plan(QuizAnswers answers, List<RoutineProduct> catalog, Climate climate) {
        return plan(answers, catalog, climate, TriedProducts.NONE);
    }

    /** @param tried what the client said about products they tried (docs/feedback.md) */
    public static RoutinePlan plan(QuizAnswers answers, List<RoutineProduct> catalog, Climate climate,
                                   TriedProducts tried) {
        return new RoutineRules(answers, climate, tried).plan(catalog);
    }

    private RoutinePlan plan(List<RoutineProduct> catalog) {
        List<Note> notes = new ArrayList<>();
        Suspects suspects = Suspects.find(tried, catalog);
        List<RoutineProduct> allowed = catalog.stream()
                .filter(p -> p.offer() != null) // can't be bought, so never picked
                .filter(this::passesEveryStepFilters)
                .filter(p -> !tried.dislikes(p) && !suspects.excludes(p))
                .toList();

        // The first concern picks the treatment: its first active that has a suitable product.
        Map<IngredientTag, List<RoutineProduct>> byActive = new LinkedHashMap<>();
        for (IngredientTag active : treatmentActivesInOrder(catalog, allowed)) {
            List<RoutineProduct> matching = inCategory(allowed, "treatment").stream()
                    .filter(p -> p.hasMainActive(active))
                    // e.g. Lactic Acid 10% + HA is an AHA treatment, not a hyaluronic acid one
                    .filter(p -> !GENTLE_ACTIVES.contains(active) || STRONG_ACTIVES.stream().noneMatch(p::hasMainActive))
                    .filter(this::treatmentIsSuitable)
                    .toList();
            if (!matching.isEmpty()) {
                byActive.put(active, adjust(catalog, "treatment",
                        rank(matching, secondConcernCoverage().thenComparing(similarityThenPrice()))));
            }
        }
        IngredientTag treatmentActive = byActive.isEmpty() ? null : byActive.keySet().iterator().next();
        List<RoutineProduct> treatments = treatmentActive == null ? List.of() : byActive.remove(treatmentActive);
        byActive.remove(RETINOID); // never a budget fallback: the other steps weren't filtered for a retinoid night
        boolean retinoidNight = treatmentActive == RETINOID;

        Map<Step, List<RoutineProduct>> candidates = new EnumMap<>(Step.class);
        candidates.put(Step.PM_TREATMENT, treatments);
        candidates.put(Step.AM_CLEANSER, rank(cleansers(allowed, retinoidNight, true), cleanserFit().thenComparing(similarityThenPrice())));
        candidates.put(Step.PM_CLEANSER, rank(cleansers(allowed, retinoidNight, false), cleanserFit().thenComparing(similarityThenPrice())));
        preferRepeat(candidates, Step.AM_CLEANSER, Step.PM_CLEANSER, cleanserFit().thenComparing(bySimilarity()));
        List<RoutineProduct> moisturizers = moisturizers(allowed, retinoidNight);
        candidates.put(Step.AM_MOISTURIZER, rank(moisturizers, moisturizerFit().thenComparing(similarityThenPrice())));
        candidates.put(Step.PM_MOISTURIZER, rank(moisturizers, moisturizerFit().thenComparing(similarityThenPrice())));
        preferRepeat(candidates, Step.AM_MOISTURIZER, Step.PM_MOISTURIZER, moisturizerFit().thenComparing(bySimilarity()));
        candidates.put(Step.AM_SUNSCREEN, sunscreens(allowed, retinoidNight));
        // Feedback comes after the repeat preference, so a repeat can't bring back a disliked texture.
        candidates.replaceAll((step, list) -> step == Step.PM_TREATMENT ? list : adjust(catalog, step.category(), list));
        pinLiked(candidates);

        Set<RoutineProduct> owned = new java.util.HashSet<>();
        candidates.values().forEach(list -> list.stream().limit(1).filter(tried::likes).forEach(owned::add));
        boolean likedLeftOut = catalog.stream().filter(tried::likes)
                .anyMatch(p -> candidates.values().stream().noneMatch(list -> list.contains(p)));
        if (likedLeftOut) {
            notes.add(Note.LIKED_PRODUCT_LEFT_OUT);
        }
        if (candidates.entrySet().stream().anyMatch(e -> e.getKey() != Step.PM_TREATMENT && e.getValue().isEmpty())) {
            notes.add(Note.NO_PRODUCT_FITS_STEP);
        }

        if (treatmentActive == null) {
            notes.add(Note.NO_TREATMENT_FITS);
        } else if (Note.forTreatment(treatmentActive) != null) {
            notes.add(Note.forTreatment(treatmentActive));
        }
        if (answers.pregnant()) {
            notes.add(Note.NO_RETINOIDS_IN_PREGNANCY);
        }
        if (answers.has(Concern.FLAKY_PATCHES)) {
            notes.add(Note.SEE_DERMATOLOGIST_FOR_PATCHES);
        }
        if (climateHas(Signal.HIGH_UV)) {
            notes.add(Note.HIGH_UV_REAPPLY);
        }
        if (climateHas(Signal.POLLUTED)) {
            notes.add(Note.AIR_POLLUTION);
        }
        return new RoutinePlan(candidates, treatmentActive, notes, byActive, owned, suspects.labels());
    }

    // ---- Feedback (docs/feedback.md) ----

    /**
     * The first concern's treatment actives, in order: an active of a liked, suitable treatment goes first; actives
     * of treatments disliked as "didn't work" go last.
     */
    private List<IngredientTag> treatmentActivesInOrder(List<RoutineProduct> catalog, List<RoutineProduct> allowed) {
        List<IngredientTag> actives = new ArrayList<>(TREATMENTS.get(answers.concerns().get(0)));
        tried.disliked(catalog, Reason.DIDNT_WORK).stream()
                .filter(p -> "treatment".equals(p.category()))
                .forEach(p -> actives.sort(Comparator.comparing(p::hasMainActive))); // stable: others keep order
        inCategory(allowed, "treatment").stream()
                .filter(tried::likes)
                .filter(this::treatmentIsSuitable)
                .flatMap(p -> actives.stream().filter(p::hasMainActive).limit(1))
                .findFirst()
                .ifPresent(active -> {
                    actives.remove(active);
                    actives.add(0, active);
                });
        return actives;
    }

    /**
     * Applies dislike reasons to one step's ranked list: "too pricey" keeps only cheaper products (if any are left),
     * "texture" moves the disliked texture to the end.
     */
    private List<RoutineProduct> adjust(List<RoutineProduct> catalog, String category, List<RoutineProduct> ranked) {
        List<RoutineProduct> list = new ArrayList<>(ranked);
        tried.disliked(catalog, Reason.TOO_PRICEY).stream()
                .filter(p -> category.equals(p.category()) && p.offer() != null)
                .mapToInt(RoutineProduct::cheapestPriceCents)
                .min()
                .ifPresent(cap -> {
                    if (list.stream().anyMatch(p -> p.cheapestPriceCents() < cap)) {
                        list.removeIf(p -> p.cheapestPriceCents() >= cap);
                    }
                });
        Set<Texture> avoided = EnumSet.noneOf(Texture.class);
        tried.disliked(catalog, Reason.TEXTURE).stream()
                .filter(p -> category.equals(p.category()) && p.texture() != Texture.UNKNOWN)
                .forEach(p -> avoided.add(p.texture()));
        list.sort(Comparator.comparing(p -> avoided.contains(p.texture()))); // stable
        return list;
    }

    /** A liked product stays in its step: the best-ranked liked one moves to the front. */
    private void pinLiked(Map<Step, List<RoutineProduct>> candidates) {
        candidates.replaceAll((step, list) -> {
            List<RoutineProduct> pinned = new ArrayList<>(list);
            pinned.sort(Comparator.comparing(p -> !tried.likes(p))); // stable
            return pinned;
        });
    }

    // ---- Filters for every step (docs/routine-rules.md, section 2) ----

    private boolean passesEveryStepFilters(RoutineProduct p) {
        return !p.hasAny(excludedEverywhere()) && excludedMainActivesEverywhere().stream().noneMatch(p::hasMainActive);
    }

    /** Ingredients no product in the routine may contain at all. */
    Set<IngredientTag> excludedEverywhere() {
        Set<IngredientTag> tags = EnumSet.noneOf(IngredientTag.class);
        for (Avoid avoid : answers.avoid()) {
            switch (avoid) {
                case FRAGRANCE -> tags.addAll(EnumSet.of(FRAGRANCE, FRAGRANCE_ALLERGEN));
                case ESSENTIAL_OILS -> tags.add(ESSENTIAL_OIL);
                case ALCOHOL -> tags.add(DRYING_ALCOHOL);
                case NUTS -> tags.add(NUT);
                case SOY -> tags.add(SOY);
            }
        }
        if (answers.reactivity() == Reactivity.OFTEN || answers.has(Concern.REDNESS)
                || answers.has(Concern.FLAKY_PATCHES)) {
            tags.addAll(FRAGRANCE_FAMILY);
        }
        if (answers.has(Concern.REDNESS)) {
            tags.addAll(EnumSet.of(DRYING_ALCOHOL, MENTHOL, CAMPHOR, SODIUM_LAURYL_SULFATE, UREA, AHA));
        }
        if (answers.pregnant()) {
            tags.add(RETINOID);
        }
        return tags;
    }

    /** Actives that may appear in small amounts but not as a product's main active. */
    Set<IngredientTag> excludedMainActivesEverywhere() {
        Set<IngredientTag> tags = EnumSet.noneOf(IngredientTag.class);
        if (answers.has(Concern.FLAKY_PATCHES)) {
            tags.addAll(EnumSet.of(AHA, BHA, RETINOID, BENZOYL_PEROXIDE)); // barrier repair only
        }
        if (answers.reactivity() == Reactivity.OFTEN) {
            tags.addAll(EnumSet.of(AHA, L_ASCORBIC_ACID, BENZOYL_PEROXIDE)); // strong actives
        }
        return tags;
    }

    // ---- Night treatment ----

    private boolean treatmentIsSuitable(RoutineProduct p) {
        if (p.hasMainActive(RETINOID) && !retinoidStrengthAllowed(p)) {
            return false;
        }
        // Pure L-ascorbic acid can sting reactive skin; derivatives are gentler.
        return !(p.hasMainActive(L_ASCORBIC_ACID) && answers.has(Concern.REDNESS));
    }

    private boolean retinoidStrengthAllowed(RoutineProduct p) {
        boolean adapalene = p.isOtcActive(RETINOID);
        boolean strong = adapalene || p.firstPositions().get(RETINOID) <= STRONG_RETINOID_MAX_POSITION;
        ActivesExperience experience = answers.reactivity() == Reactivity.OFTEN
                ? ActivesExperience.NEVER : answers.activesExperience();
        return switch (experience) {
            case NEVER -> !strong;
            case A_LITTLE -> !adapalene;
            case REGULARLY -> true;
        };
    }

    /** Treatments that also address the second concern come first. */
    private Comparator<RoutineProduct> secondConcernCoverage() {
        List<IngredientTag> second = answers.concerns().size() > 1 ? TREATMENTS.get(answers.concerns().get(1)) : List.of();
        return Comparator.comparing(p -> second.stream().noneMatch(p::hasMainActive));
    }

    // ---- Other steps (docs/routine-rules.md, sections 3 and 4) ----

    private List<RoutineProduct> cleansers(List<RoutineProduct> allowed, boolean retinoidNight, boolean morning) {
        boolean experienced = answers.activesExperience() == ActivesExperience.REGULARLY
                && answers.reactivity() != Reactivity.OFTEN;
        Predicate<RoutineProduct> keep = p -> true;
        if (retinoidNight) {
            // Acne cleansers stay out of a retinoid night; experienced users may use them in the morning.
            boolean allowActives = morning && experienced;
            keep = p -> allowActives || !(p.hasMainActive(BHA) || p.hasMainActive(BENZOYL_PEROXIDE) || p.hasMainActive(AHA));
        }
        return inCategory(allowed, "cleanser").stream().filter(keep).filter(p -> !p.makeupRemover()).toList();
    }

    /** How well a cleanser fits the answers, before price. */
    private Comparator<RoutineProduct> cleanserFit() {
        Comparator<RoutineProduct> acneFirst = Comparator.comparing(p ->
                !(answers.has(Concern.BREAKOUTS) && (p.hasMainActive(BENZOYL_PEROXIDE) || p.hasMainActive(BHA))));
        return acneFirst.thenComparing(textureMatch());
    }

    private List<RoutineProduct> moisturizers(List<RoutineProduct> allowed, boolean retinoidNight) {
        boolean dry = answers.skinType() == SkinType.DRY || answers.has(Concern.DRYNESS);
        Set<IngredientTag> excluded = dry
                ? EnumSet.of(DRYING_ALCOHOL, AHA, FRAGRANCE, FRAGRANCE_ALLERGEN) // AAD dry-skin advice
                : EnumSet.noneOf(IngredientTag.class);
        return inCategory(allowed, "moisturizer").stream()
                .filter(p -> !p.hasAny(excluded))
                .filter(p -> !retinoidNight || EXFOLIANTS.stream().noneMatch(p::hasMainActive))
                .toList();
    }

    /** How well a moisturizer fits the answers, before price. */
    private Comparator<RoutineProduct> moisturizerFit() {
        Texture wanted = moisturizerTexture();
        return Comparator.comparing(p -> wanted != null && p.texture() != wanted);
    }

    /** Skin type decides; climate only when skin type has no say (normal, not sure). Null: no preference. */
    private Texture moisturizerTexture() {
        Texture wanted = textureForSkinType();
        if (wanted == null && climateHas(Signal.HUMID)) {
            return Texture.LIGHT;
        }
        if (wanted == null && climateHas(Signal.DRY_AIR)) {
            return Texture.RICH;
        }
        return wanted;
    }

    /**
     * If one product fits the AM and the PM step as well as any other product does, it goes first in both
     * lists, so the routine repeats it instead of buying two (author's decision).
     */
    private static void preferRepeat(Map<Step, List<RoutineProduct>> candidates, Step am, Step pm,
                                     Comparator<RoutineProduct> fit) {
        List<RoutineProduct> amList = candidates.get(am);
        List<RoutineProduct> pmList = candidates.get(pm);
        if (amList.isEmpty() || pmList.isEmpty()) {
            return;
        }
        List<RoutineProduct> pmBest = pmList.stream().filter(p -> fit.compare(p, pmList.get(0)) == 0).toList();
        amList.stream()
                .filter(p -> fit.compare(p, amList.get(0)) == 0)
                .filter(pmBest::contains)
                .min(byPrice())
                .ifPresent(shared -> {
                    candidates.put(am, moveToFront(amList, shared));
                    candidates.put(pm, moveToFront(pmList, shared));
                });
    }

    private static List<RoutineProduct> moveToFront(List<RoutineProduct> list, RoutineProduct first) {
        List<RoutineProduct> moved = new ArrayList<>(List.of(first));
        list.stream().filter(p -> !p.equals(first)).forEach(moved::add);
        return moved;
    }

    private List<RoutineProduct> sunscreens(List<RoutineProduct> allowed, boolean retinoidNight) {
        List<RoutineProduct> sunscreens = inCategory(allowed, "sunscreen").stream()
                .filter(p -> p.spf() != null && p.spf() >= 30)
                .filter(p -> !retinoidNight || EXFOLIANTS.stream().noneMatch(p::hasMainActive))
                .toList();
        if (mineralPreferred() && sunscreens.stream().anyMatch(RoutineProduct::mineral)) {
            sunscreens = sunscreens.stream().filter(RoutineProduct::mineral).toList();
        }
        boolean tintPreferred = tintPreferred();
        Comparator<RoutineProduct> tintFirst = Comparator.comparing(p -> !(tintPreferred && p.tinted()));
        boolean highUv = climateHas(Signal.HIGH_UV);
        Comparator<RoutineProduct> highSpfFirst = Comparator.comparing(p -> highUv && p.spf() < HIGH_UV_MIN_SPF);
        return rank(sunscreens, highSpfFirst.thenComparing(tintFirst).thenComparing(similarityThenPrice()));
    }

    private boolean mineralPreferred() {
        return answers.pregnant() || answers.has(Concern.REDNESS) || answers.has(Concern.FLAKY_PATCHES)
                || answers.reactivity() == Reactivity.OFTEN;
    }

    private boolean tintPreferred() {
        return answers.has(Concern.DARK_SPOTS) || answers.has(Concern.UNEVEN_TONE)
                || (answers.skinTone() != null && answers.skinTone() >= 4);
    }

    // ---- Why a product won its step (for the explanation) ----

    /**
     * The reasons a product ranks high in its step, in fixed phrases. Each one mirrors a ranking rule above, so the
     * explanation can only say what the rules actually used. Never mentions pregnancy (it isn't sent to the LLM).
     */
    public static List<String> reasons(QuizAnswers answers, Climate climate, TriedProducts tried, Step step,
                                       RoutineProduct p) {
        return new RoutineRules(answers, climate, tried).reasonsFor(step, p);
    }

    private List<String> reasonsFor(Step step, RoutineProduct p) {
        List<String> reasons = new ArrayList<>();
        if (tried.likes(p)) {
            reasons.add("you said you liked it");
        }
        switch (step.category()) {
            case "treatment" -> {
                if (answers.concerns().size() > 1) {
                    Concern second = answers.concerns().get(1);
                    if (TREATMENTS.get(second).stream().anyMatch(p::hasMainActive)) {
                        reasons.add("also helps with " + second.label());
                    }
                }
            }
            case "cleanser" -> {
                if (answers.has(Concern.BREAKOUTS) && p.hasMainActive(BENZOYL_PEROXIDE)) {
                    reasons.add("has benzoyl peroxide for breakouts");
                } else if (answers.has(Concern.BREAKOUTS) && p.hasMainActive(BHA)) {
                    reasons.add("has salicylic acid for breakouts");
                }
                Texture wanted = textureForSkinType();
                if (wanted != null && p.texture() == wanted) {
                    reasons.add(texture(wanted) + " texture for " + words(answers.skinType()) + " skin");
                }
            }
            case "moisturizer" -> {
                Texture wanted = moisturizerTexture();
                if (wanted != null && p.texture() == wanted) {
                    reasons.add(texture(wanted) + " texture for " + (textureForSkinType() != null
                            ? words(answers.skinType()) + " skin"
                            : wanted == Texture.LIGHT ? "the humid air where you live" : "the dry air where you live"));
                }
            }
            case "sunscreen" -> {
                if (climateHas(Signal.HIGH_UV) && p.spf() != null && p.spf() >= HIGH_UV_MIN_SPF) {
                    reasons.add("SPF " + p.spf() + " for the very strong sun where you live");
                }
                if (mineralPreferred() && p.mineral()) {
                    if (answers.has(Concern.REDNESS)) {
                        reasons.add("mineral filters, gentler on redness");
                    } else if (answers.has(Concern.FLAKY_PATCHES)) {
                        reasons.add("mineral filters, gentler on flaky patches");
                    } else if (answers.reactivity() == Reactivity.OFTEN) {
                        reasons.add("mineral filters, gentler on skin that reacts often");
                    } else {
                        reasons.add("mineral filters"); // the only cause is private (pregnancy)
                    }
                }
                if (tintPreferred() && p.tinted()) {
                    reasons.add("tinted with iron oxides, which also block the visible light that can darken spots");
                }
            }
            default -> {
            }
        }
        if (tried.similarityTo(p) >= 0.3) {
            reasons.add("similar to products you liked");
        }
        return reasons;
    }

    private static String texture(Texture t) {
        return t == Texture.LIGHT ? "light" : "rich";
    }

    private static String words(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    // ---- Helpers ----

    /** Dry skin prefers rich textures, oily skin light ones. */
    private Comparator<RoutineProduct> textureMatch() {
        Texture wanted = textureForSkinType();
        return Comparator.comparing(p -> wanted != null && p.texture() != wanted);
    }

    /** The texture the skin type asks for, or null for normal and "not sure" skin. */
    private Texture textureForSkinType() {
        return switch (answers.skinType()) {
            case DRY -> Texture.RICH;
            case OILY, COMBINATION -> Texture.LIGHT;
            default -> null;
        };
    }

    private boolean climateHas(Signal signal) {
        return climate != null && climate.has(signal);
    }

    /** More like what the client liked (and less like what they disliked) first; 0 for everyone without feedback. */
    private Comparator<RoutineProduct> bySimilarity() {
        return Comparator.comparingDouble(p -> -tried.similarityTo(p));
    }

    /** After the rules: similarity to liked products, then price (author's decision). */
    private Comparator<RoutineProduct> similarityThenPrice() {
        return bySimilarity().thenComparing(byPrice());
    }

    private static Comparator<RoutineProduct> byPrice() {
        return Comparator.comparing(RoutineProduct::cheapestPriceCents, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static List<RoutineProduct> inCategory(List<RoutineProduct> products, String category) {
        return products.stream().filter(p -> category.equals(p.category())).toList();
    }

    private static List<RoutineProduct> rank(List<RoutineProduct> products, Comparator<RoutineProduct> order) {
        return new ArrayList<>(new LinkedHashSet<>(products.stream().sorted(order).toList()));
    }
}
