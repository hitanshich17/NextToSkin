"""Turns quiz answers into ranked candidates for each routine step.

Every rule is documented, with its evidence and source, in docs/routine-rules.md; keep the two in step. Ported
from the Java catalog service (RoutineRules.java); tests/test_routine_parity.py checks both give identical results.

Pure logic with no I/O. Sorting relies on Python's stable sort, exactly like the Java version: products that tie
keep the catalog order (sorted by product id).
"""

from __future__ import annotations

from typing import Callable

from app.recommender.models import (
    TAG_ORDER, ActivesExperience, Avoid, Climate, Concern, NO_FEEDBACK, Note, Product, Reactivity, Reason,
    RoutinePlan, Signal, SkinType, Step, Texture, TriedProducts,
)

# Night treatment actives per concern, in order of preference (docs/routine-rules.md, section 1).
TREATMENTS: dict[Concern, list[str]] = {
    Concern.BREAKOUTS: ["RETINOID", "AZELAIC_ACID", "BHA", "NIACINAMIDE"],
    Concern.DARK_SPOTS: ["VITAMIN_C", "AZELAIC_ACID", "TRANEXAMIC_ACID", "NIACINAMIDE", "RETINOID"],
    Concern.UNEVEN_TONE: ["TRANEXAMIC_ACID", "AZELAIC_ACID", "VITAMIN_C", "NIACINAMIDE"],
    Concern.FINE_LINES: ["RETINOID", "VITAMIN_C", "PEPTIDE"],
    Concern.REDNESS: ["AZELAIC_ACID", "NIACINAMIDE", "CENTELLA"],
    Concern.DULLNESS: ["AHA", "BHA", "VITAMIN_C"],
    Concern.DRYNESS: ["HYALURONIC_ACID", "CERAMIDE"],
    Concern.FLAKY_PATCHES: ["CERAMIDE", "HYALURONIC_ACID"],
    Concern.OILINESS: ["NIACINAMIDE", "BHA"],
}

STRONG_RETINOID_MAX_POSITION = 15  # retinoids listed this early are stronger (e.g. 1% retinol)
FRAGRANCE_FAMILY = {"FRAGRANCE", "FRAGRANCE_ALLERGEN", "ESSENTIAL_OIL"}
EXFOLIANTS = {"AHA", "BHA"}
# Hydrating and soothing treatment actives, which a product with a strong main active never counts as.
GENTLE_ACTIVES = {"HYALURONIC_ACID", "CERAMIDE", "CENTELLA", "PEPTIDE"}
STRONG_ACTIVES = {"AHA", "BHA", "RETINOID", "BENZOYL_PEROXIDE"}
HIGH_UV_MIN_SPF = 50  # ranked first where the UV index is very high

# Feedback suspects (docs/feedback.md)
IRRITANTS = ["FRAGRANCE", "FRAGRANCE_ALLERGEN", "ESSENTIAL_OIL", "DRYING_ALCOHOL", "MENTHOL", "CAMPHOR",
             "SODIUM_LAURYL_SULFATE"]
SUSPECT_ACTIVES = ["AHA", "BHA", "RETINOID", "BENZOYL_PEROXIDE", "L_ASCORBIC_ACID"]
TOO_COMMON = 0.5  # ingredients in more than this share of the catalog can't tell products apart
SUSPECT_LABELS = {
    "FRAGRANCE": "Fragrance", "FRAGRANCE_ALLERGEN": "Fragrance allergens", "ESSENTIAL_OIL": "Essential oils",
    "DRYING_ALCOHOL": "Drying alcohol", "MENTHOL": "Menthol", "CAMPHOR": "Camphor",
    "SODIUM_LAURYL_SULFATE": "Sodium lauryl sulfate", "AHA": "AHAs (e.g. glycolic acid)", "BHA": "Salicylic acid",
    "RETINOID": "Retinoids", "BENZOYL_PEROXIDE": "Benzoyl peroxide",
    "L_ASCORBIC_ACID": "Pure vitamin C (L-ascorbic acid)",
}

Key = Callable[[Product], tuple]


def _in_tag_order(tags) -> list[str]:
    return [t for t in TAG_ORDER if t in tags]


class Suspects:
    """Ingredients that may have caused a reaction, from products disliked as "irritated"; excluded everywhere."""

    def __init__(self, tags=(), main_actives=(), ingredients=()):
        self.tags = set(tags)
        self.main_actives = set(main_actives)
        self.ingredients = set(ingredients)

    @classmethod
    def find(cls, tried: TriedProducts, catalog: list[Product]) -> "Suspects":
        irritated = tried.disliked_with(catalog, Reason.IRRITATED)
        if not irritated:
            return cls()
        tags = {t for p in irritated for t in IRRITANTS if p.has(t)}
        main_actives = {t for p in irritated for t in SUSPECT_ACTIVES if p.has_main_active(t)}
        shared = set()
        if len(irritated) >= 2:
            in_disliked = _count(irritated)
            in_catalog = _count(catalog)
            in_liked = {i for p in catalog if tried.likes(p) for i in p.ingredients}
            shared = {i for i, n in in_disliked.items()
                      if n >= 2 and i not in in_liked and in_catalog.get(i, 0) <= TOO_COMMON * len(catalog)}
        return cls(tags, main_actives, shared)

    def excludes(self, p: Product) -> bool:
        return (p.has_any(self.tags) or any(p.has_main_active(t) for t in self.main_actives)
                or any(i in self.ingredients for i in p.ingredients))

    def labels(self) -> list[str]:
        """What the routine now leaves out, in words for the results page."""
        labels = [SUSPECT_LABELS[t] for t in _in_tag_order(self.tags)]
        labels += [SUSPECT_LABELS[t] for t in _in_tag_order(self.main_actives) if t not in self.tags]
        return labels + sorted(self.ingredients)


def _count(products: list[Product]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for p in products:
        for i in p.ingredients:
            counts[i] = counts.get(i, 0) + 1
    return counts


def plan(answers, catalog: list[Product], climate: Climate | None = None,
         tried: TriedProducts = NO_FEEDBACK) -> RoutinePlan:
    """Ranked candidates per step for these answers. `catalog` must be sorted by product id."""
    return _Rules(answers, climate, tried).plan(catalog)


def reasons(answers, climate: Climate | None, tried: TriedProducts, step: Step, p: Product) -> list[str]:
    """Why a product ranks high in its step, in fixed phrases that mirror the ranking rules (for the explanation).

    Never mentions pregnancy, which isn't sent to the LLM.
    """
    return _Rules(answers, climate, tried).reasons_for(step, p)


class _Rules:
    def __init__(self, answers, climate: Climate | None, tried: TriedProducts):
        self.answers = answers
        self.climate = climate
        self.tried = tried

    # ---- The plan ----

    def plan(self, catalog: list[Product]) -> RoutinePlan:
        notes: list[Note] = []
        suspects = Suspects.find(self.tried, catalog)
        allowed = [p for p in catalog
                   if p.offer is not None  # can't be bought, so never picked
                   and self._passes_every_step_filters(p)
                   and not self.tried.dislikes(p) and not suspects.excludes(p)]

        # The first concern picks the treatment: its first active that has a suitable product.
        by_active: dict[str, list[Product]] = {}
        for active in self._treatment_actives_in_order(catalog, allowed):
            matching = [p for p in _in_category(allowed, "treatment")
                        if p.has_main_active(active)
                        # e.g. Lactic Acid 10% + HA is an AHA treatment, not a hyaluronic acid one
                        and (active not in GENTLE_ACTIVES or not any(p.has_main_active(s) for s in STRONG_ACTIVES))
                        and self._treatment_is_suitable(p)]
            if matching:
                by_active[active] = self._adjust(catalog, "treatment", _rank(
                    matching, _then(self._second_concern_coverage(), self._similarity_then_price())))
        treatment_active = next(iter(by_active), None)
        treatments = by_active.pop(treatment_active) if treatment_active else []
        by_active.pop("RETINOID", None)  # never a budget fallback: other steps weren't filtered for a retinoid night
        retinoid_night = treatment_active == "RETINOID"

        cleanser_order = _then(self._cleanser_fit(), self._similarity_then_price())
        moisturizer_order = _then(self._moisturizer_fit(), self._similarity_then_price())
        candidates: dict[Step, list[Product]] = {Step.PM_TREATMENT: treatments}
        candidates[Step.AM_CLEANSER] = _rank(self._cleansers(allowed, retinoid_night, morning=True), cleanser_order)
        candidates[Step.PM_CLEANSER] = _rank(self._cleansers(allowed, retinoid_night, morning=False), cleanser_order)
        _prefer_repeat(candidates, Step.AM_CLEANSER, Step.PM_CLEANSER, _then(self._cleanser_fit(), self._by_similarity()))
        moisturizers = self._moisturizers(allowed, retinoid_night)
        candidates[Step.AM_MOISTURIZER] = _rank(moisturizers, moisturizer_order)
        candidates[Step.PM_MOISTURIZER] = _rank(moisturizers, moisturizer_order)
        _prefer_repeat(candidates, Step.AM_MOISTURIZER, Step.PM_MOISTURIZER,
                       _then(self._moisturizer_fit(), self._by_similarity()))
        candidates[Step.AM_SUNSCREEN] = self._sunscreens(allowed, retinoid_night)
        # Feedback comes after the repeat preference, so a repeat can't bring back a disliked texture.
        for step in list(candidates):
            if step != Step.PM_TREATMENT:
                candidates[step] = self._adjust(catalog, step.category, candidates[step])
        self._pin_liked(candidates)

        owned = frozenset(lst[0] for lst in candidates.values() if lst and self.tried.likes(lst[0]))
        if any(self.tried.likes(p) and not any(p in lst for lst in candidates.values()) for p in catalog):
            notes.append(Note.LIKED_PRODUCT_LEFT_OUT)
        if any(step != Step.PM_TREATMENT and not lst for step, lst in candidates.items()):
            notes.append(Note.NO_PRODUCT_FITS_STEP)

        if treatment_active is None:
            notes.append(Note.NO_TREATMENT_FITS)
        elif Note.for_treatment(treatment_active):
            notes.append(Note.for_treatment(treatment_active))
        if self.answers.pregnant:
            notes.append(Note.NO_RETINOIDS_IN_PREGNANCY)
        if self.answers.has(Concern.FLAKY_PATCHES):
            notes.append(Note.SEE_DERMATOLOGIST_FOR_PATCHES)
        if self._climate_has(Signal.HIGH_UV):
            notes.append(Note.HIGH_UV_REAPPLY)
        if self._climate_has(Signal.POLLUTED):
            notes.append(Note.AIR_POLLUTION)
        ordered = {step: candidates[step] for step in Step}
        return RoutinePlan(ordered, treatment_active, notes, by_active, owned, suspects.labels())

    # ---- Feedback (docs/feedback.md) ----

    def _treatment_actives_in_order(self, catalog, allowed) -> list[str]:
        """A liked, suitable treatment's active goes first; actives of "didn't work" treatments go last."""
        actives = list(TREATMENTS[self.answers.concerns[0]])
        for p in self.tried.disliked_with(catalog, Reason.DIDNT_WORK):
            if p.category == "treatment":
                actives.sort(key=p.has_main_active)  # stable: the others keep their order
        for p in _in_category(allowed, "treatment"):
            if self.tried.likes(p) and self._treatment_is_suitable(p):
                first = next((a for a in actives if p.has_main_active(a)), None)
                if first is not None:
                    actives.remove(first)
                    actives.insert(0, first)
                    break
        return actives

    def _adjust(self, catalog, category: str, ranked: list[Product]) -> list[Product]:
        """"Too pricey" keeps only cheaper products (if any); "texture" moves the disliked texture to the end."""
        result = list(ranked)
        prices = [p.cheapest_price_cents for p in self.tried.disliked_with(catalog, Reason.TOO_PRICEY)
                  if p.category == category and p.offer is not None]
        if prices:
            cap = min(prices)
            if any(p.cheapest_price_cents < cap for p in result):
                result = [p for p in result if p.cheapest_price_cents < cap]
        avoided = {p.texture for p in self.tried.disliked_with(catalog, Reason.TEXTURE)
                   if p.category == category and p.texture != Texture.UNKNOWN}
        result.sort(key=lambda p: p.texture in avoided)
        return result

    def _pin_liked(self, candidates) -> None:
        """A liked product stays in its step: the best-ranked liked one moves to the front."""
        for step, lst in candidates.items():
            candidates[step] = sorted(lst, key=lambda p: not self.tried.likes(p))

    # ---- Filters for every step (docs/routine-rules.md, section 2) ----

    def _passes_every_step_filters(self, p: Product) -> bool:
        return not p.has_any(self.excluded_everywhere()) and not any(
            p.has_main_active(t) for t in self.excluded_main_actives_everywhere())

    def excluded_everywhere(self) -> set[str]:
        """Ingredients no product in the routine may contain at all."""
        tags: set[str] = set()
        for avoid in self.answers.avoid:
            tags |= {Avoid.FRAGRANCE: {"FRAGRANCE", "FRAGRANCE_ALLERGEN"}, Avoid.ESSENTIAL_OILS: {"ESSENTIAL_OIL"},
                     Avoid.ALCOHOL: {"DRYING_ALCOHOL"}, Avoid.NUTS: {"NUT"}, Avoid.SOY: {"SOY"}}[avoid]
        if (self.answers.reactivity == Reactivity.OFTEN or self.answers.has(Concern.REDNESS)
                or self.answers.has(Concern.FLAKY_PATCHES)):
            tags |= FRAGRANCE_FAMILY
        if self.answers.has(Concern.REDNESS):
            tags |= {"DRYING_ALCOHOL", "MENTHOL", "CAMPHOR", "SODIUM_LAURYL_SULFATE", "UREA", "AHA"}
        if self.answers.pregnant:
            tags.add("RETINOID")
        return tags

    def excluded_main_actives_everywhere(self) -> set[str]:
        """Actives that may appear in small amounts but not as a product's main active."""
        tags: set[str] = set()
        if self.answers.has(Concern.FLAKY_PATCHES):
            tags |= {"AHA", "BHA", "RETINOID", "BENZOYL_PEROXIDE"}  # barrier repair only
        if self.answers.reactivity == Reactivity.OFTEN:
            tags |= {"AHA", "L_ASCORBIC_ACID", "BENZOYL_PEROXIDE"}  # strong actives
        return tags

    # ---- Night treatment ----

    def _treatment_is_suitable(self, p: Product) -> bool:
        if p.has_main_active("RETINOID") and not self._retinoid_strength_allowed(p):
            return False
        # Pure L-ascorbic acid can sting reactive skin; derivatives are gentler.
        return not (p.has_main_active("L_ASCORBIC_ACID") and self.answers.has(Concern.REDNESS))

    def _retinoid_strength_allowed(self, p: Product) -> bool:
        adapalene = p.is_otc_active("RETINOID")
        strong = adapalene or p.first_positions["RETINOID"] <= STRONG_RETINOID_MAX_POSITION
        experience = (ActivesExperience.NEVER if self.answers.reactivity == Reactivity.OFTEN
                      else self.answers.actives_experience)
        return {ActivesExperience.NEVER: not strong, ActivesExperience.A_LITTLE: not adapalene,
                ActivesExperience.REGULARLY: True}[experience]

    def _second_concern_coverage(self) -> Key:
        """Treatments that also address the second concern come first."""
        second = TREATMENTS[self.answers.concerns[1]] if len(self.answers.concerns) > 1 else []
        return lambda p: (not any(p.has_main_active(a) for a in second),)

    # ---- Other steps (docs/routine-rules.md, sections 3 and 4) ----

    def _cleansers(self, allowed, retinoid_night: bool, morning: bool) -> list[Product]:
        experienced = (self.answers.actives_experience == ActivesExperience.REGULARLY
                       and self.answers.reactivity != Reactivity.OFTEN)
        allow_actives = not retinoid_night or (morning and experienced)

        def keep(p: Product) -> bool:
            # Acne cleansers stay out of a retinoid night; experienced users may use them in the morning.
            return allow_actives or not (p.has_main_active("BHA") or p.has_main_active("BENZOYL_PEROXIDE")
                                         or p.has_main_active("AHA"))

        return [p for p in _in_category(allowed, "cleanser") if keep(p) and not p.makeup_remover]

    def _cleanser_fit(self) -> Key:
        """How well a cleanser fits the answers, before price."""
        breakouts = self.answers.has(Concern.BREAKOUTS)
        texture = self._texture_match()
        return lambda p: (not (breakouts and (p.has_main_active("BENZOYL_PEROXIDE") or p.has_main_active("BHA"))),
                          *texture(p))

    def _moisturizers(self, allowed, retinoid_night: bool) -> list[Product]:
        dry = self.answers.skin_type == SkinType.DRY or self.answers.has(Concern.DRYNESS)
        excluded = {"DRYING_ALCOHOL", "AHA", "FRAGRANCE", "FRAGRANCE_ALLERGEN"} if dry else set()  # AAD dry skin
        return [p for p in _in_category(allowed, "moisturizer")
                if not p.has_any(excluded)
                and (not retinoid_night or not any(p.has_main_active(t) for t in EXFOLIANTS))]

    def _moisturizer_fit(self) -> Key:
        wanted = self._moisturizer_texture()
        return lambda p: (wanted is not None and p.texture != wanted,)

    def _moisturizer_texture(self) -> Texture | None:
        """Skin type decides; climate only when skin type has no say (normal, not sure)."""
        wanted = self._texture_for_skin_type()
        if wanted is None and self._climate_has(Signal.HUMID):
            return Texture.LIGHT
        if wanted is None and self._climate_has(Signal.DRY_AIR):
            return Texture.RICH
        return wanted

    def _sunscreens(self, allowed, retinoid_night: bool) -> list[Product]:
        sunscreens = [p for p in _in_category(allowed, "sunscreen")
                      if p.spf is not None and p.spf >= 30
                      and (not retinoid_night or not any(p.has_main_active(t) for t in EXFOLIANTS))]
        if self._mineral_preferred() and any(p.mineral for p in sunscreens):
            sunscreens = [p for p in sunscreens if p.mineral]
        tint = self._tint_preferred()
        high_uv = self._climate_has(Signal.HIGH_UV)
        order = _then(lambda p: (high_uv and p.spf < HIGH_UV_MIN_SPF, not (tint and p.tinted)),
                      self._similarity_then_price())
        return _rank(sunscreens, order)

    def _mineral_preferred(self) -> bool:
        return (self.answers.pregnant or self.answers.has(Concern.REDNESS) or self.answers.has(Concern.FLAKY_PATCHES)
                or self.answers.reactivity == Reactivity.OFTEN)

    def _tint_preferred(self) -> bool:
        return (self.answers.has(Concern.DARK_SPOTS) or self.answers.has(Concern.UNEVEN_TONE)
                or (self.answers.skin_tone is not None and self.answers.skin_tone >= 4))

    # ---- Why a product won its step (for the explanation) ----

    def reasons_for(self, step: Step, p: Product) -> list[str]:
        reasons: list[str] = []
        if self.tried.likes(p):
            reasons.append("you said you liked it")
        category = step.category
        if category == "treatment":
            if len(self.answers.concerns) > 1:
                second = self.answers.concerns[1]
                if any(p.has_main_active(a) for a in TREATMENTS[second]):
                    reasons.append("also helps with " + second.label)
        elif category == "cleanser":
            if self.answers.has(Concern.BREAKOUTS) and p.has_main_active("BENZOYL_PEROXIDE"):
                reasons.append("has benzoyl peroxide for breakouts")
            elif self.answers.has(Concern.BREAKOUTS) and p.has_main_active("BHA"):
                reasons.append("has salicylic acid for breakouts")
            wanted = self._texture_for_skin_type()
            if wanted is not None and p.texture == wanted:
                reasons.append(f"{_texture_word(wanted)} texture for {_words(self.answers.skin_type)} skin")
        elif category == "moisturizer":
            wanted = self._moisturizer_texture()
            if wanted is not None and p.texture == wanted:
                if self._texture_for_skin_type() is not None:
                    why = _words(self.answers.skin_type) + " skin"
                else:
                    why = "the humid air where you live" if wanted == Texture.LIGHT else "the dry air where you live"
                reasons.append(f"{_texture_word(wanted)} texture for {why}")
        elif category == "sunscreen":
            if self._climate_has(Signal.HIGH_UV) and p.spf is not None and p.spf >= HIGH_UV_MIN_SPF:
                reasons.append(f"SPF {p.spf} for the very strong sun where you live")
            if self._mineral_preferred() and p.mineral:
                if self.answers.has(Concern.REDNESS):
                    reasons.append("mineral filters, gentler on redness")
                elif self.answers.has(Concern.FLAKY_PATCHES):
                    reasons.append("mineral filters, gentler on flaky patches")
                elif self.answers.reactivity == Reactivity.OFTEN:
                    reasons.append("mineral filters, gentler on skin that reacts often")
                else:
                    reasons.append("mineral filters")  # the only cause is private (pregnancy)
            if self._tint_preferred() and p.tinted:
                reasons.append("tinted with iron oxides, which also block the visible light that can darken spots")
        if self.tried.similarity_to(p) >= 0.3:
            reasons.append("similar to products you liked")
        return reasons

    # ---- Helpers ----

    def _texture_match(self) -> Key:
        """Dry skin prefers rich textures, oily skin light ones."""
        wanted = self._texture_for_skin_type()
        return lambda p: (wanted is not None and p.texture != wanted,)

    def _texture_for_skin_type(self) -> Texture | None:
        return {SkinType.DRY: Texture.RICH, SkinType.OILY: Texture.LIGHT,
                SkinType.COMBINATION: Texture.LIGHT}.get(self.answers.skin_type)

    def _climate_has(self, signal: Signal) -> bool:
        return self.climate is not None and self.climate.has(signal)

    def _by_similarity(self) -> Key:
        """More like what the client liked (and less like what they disliked) first."""
        return lambda p: (-self.tried.similarity_to(p),)

    def _similarity_then_price(self) -> Key:
        """After the rules: similarity to liked products, then price (author's decision)."""
        return _then(self._by_similarity(), _by_price)


def _by_price(p: Product) -> tuple:
    price = p.cheapest_price_cents
    return (price is None, price or 0)  # products without a price last


def _then(*keys: Key) -> Key:
    return lambda p: tuple(part for key in keys for part in key(p))


def _prefer_repeat(candidates, am: Step, pm: Step, fit: Key) -> None:
    """If one product fits AM and PM as well as any other does, it goes first in both, so it's bought once."""
    am_list, pm_list = candidates[am], candidates[pm]
    if not am_list or not pm_list:
        return
    pm_best = [p for p in pm_list if fit(p) == fit(pm_list[0])]
    shared = [p for p in am_list if fit(p) == fit(am_list[0]) and p in pm_best]
    if shared:
        first = min(shared, key=_by_price)
        candidates[am] = [first] + [p for p in am_list if p != first]
        candidates[pm] = [first] + [p for p in pm_list if p != first]


def _in_category(products, category: str) -> list[Product]:
    return [p for p in products if p.category == category]


def _rank(products, key: Key) -> list[Product]:
    return list(dict.fromkeys(sorted(products, key=key)))


def _texture_word(t: Texture) -> str:
    return "light" if t == Texture.LIGHT else "rich"


def _words(value) -> str:
    return value.value.lower().replace("_", " ")
