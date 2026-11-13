"""Picks one product per step from a RoutinePlan within the budget ("best match within budget"), and estimates the
monthly cost. Ported from the Java catalog service (RoutineSelector.java, MonthlyCost.java); the parity test checks
both give identical routines.

Starts from each step's best-ranked product. While over budget, it makes the swap that saves the most money per
ranking place given up, always to a cheaper product further down a step's list. A product used in AM and PM is
paid for once, so swapping it swaps it in both steps. Last resort: switch the night treatment to the concern's next
active (never a retinoid), with a note.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal

from app.recommender.models import Note, Product, RoutinePlan, Step

ALTERNATIVES = 3  # cheaper alternatives shown per step
DAYS = 30
# Typical millilitres per use, by category; the sunscreen amount is the "two-finger" rule for the face.
ML_PER_USE = {"cleanser": Decimal("1.0"), "treatment": Decimal("0.3"), "moisturizer": Decimal("0.5"),
              "sunscreen": Decimal("1.2")}


@dataclass(frozen=True)
class Pick:
    product: Product
    cheaper_alternatives: list[Product] = field(default_factory=list)  # best match first
    owned: bool = False               # liked: kept in its step, $0 upfront
    cheaper_for_budget: bool = False  # the step's best match was swapped for this one to fit the budget


@dataclass(frozen=True)
class Routine:
    picks: dict[Step, Pick]               # a step with no fitting product is left out
    treatment_active: str | None
    total_cents: int                      # upfront: each product once, owned products free
    budget_cents: int | None
    monthly_cents: int | None             # None if a picked product's size is unknown
    notes: list[Note]
    avoided: list[str]

    @property
    def within_budget(self) -> bool:
        return self.budget_cents is None or self.total_cents <= self.budget_cents


def select(plan: RoutinePlan, budget_cents: int | None) -> Routine:
    best = _select(plan, plan.candidates, plan.treatment_active, plan.notes, budget_cents)
    if best.within_budget:
        return best
    for active, treatments in plan.budget_fallbacks.items():
        candidates = {**plan.candidates, Step.PM_TREATMENT: treatments}
        notes = list(plan.notes)
        old = Note.for_treatment(plan.treatment_active)
        if old in notes:
            notes.remove(old)
        if Note.for_treatment(active):
            notes.append(Note.for_treatment(active))
        notes.append(Note.TREATMENT_CHANGED_FOR_BUDGET)
        switched = _select(plan, candidates, active, notes, budget_cents)
        if switched.within_budget:
            return switched
    return best  # nothing fits: keep the best match, with the over-budget note


def _select(plan: RoutinePlan, candidates, treatment_active, plan_notes, budget_cents) -> Routine:
    owned = plan.owned
    lists = {step: candidates[step] for step in Step if candidates.get(step)}
    chosen = {step: 0 for step in lists}  # index into each step's list

    while budget_cents is not None and _total(lists, chosen, owned) > budget_cents:
        best = _best_swap(lists, chosen, owned)
        if best is None:
            break  # nothing cheaper left anywhere
        chosen = best

    picks: dict[Step, Pick] = {}
    for step, index in chosen.items():
        product = lists[step][index]
        cheaper = [p for p in lists[step] if p != product and p.cheapest_price_cents < product.cheapest_price_cents]
        is_owned = product in owned
        picks[step] = Pick(product, [] if is_owned else cheaper[:ALTERNATIVES], is_owned, index > 0)

    notes = list(plan_notes)
    if any(p.product.has_main_active("AHA") for p in picks.values()) and Note.AHA_SUNBURN_ALERT not in notes:
        notes.append(Note.AHA_SUNBURN_ALERT)  # the FDA alert applies to any AHA product, e.g. a glycolic cleanser
    total = _total(lists, chosen, owned)
    if budget_cents is not None and total > budget_cents:
        notes.append(Note.OVER_BUDGET)
    return Routine(picks, treatment_active, total, budget_cents, monthly_cost(picks), notes, list(plan.avoided))


def _best_swap(lists, chosen, owned) -> dict[Step, int] | None:
    """The cheaper choice that gives up the fewest ranking places per dollar saved, or None."""
    current = _total(lists, chosen, owned)
    best, best_score = None, 0.0
    for step in chosen:
        source = lists[step][chosen[step]]
        for i in range(chosen[step] + 1, len(lists[step])):
            candidate = _replace(lists, chosen, source, lists[step][i], step)
            saved = current - _total(lists, candidate, owned)
            if saved <= 0:
                continue
            score = saved / _places_given_up(chosen, candidate)
            if score > best_score:
                best_score, best = score, candidate
            break  # the best-ranked cheaper option for this step is the only one worth comparing
    return best


def _replace(lists, chosen, source: Product, target: Product, step: Step) -> dict[Step, int]:
    """Swaps source for target in step, and in any other step that uses source too."""
    result = dict(chosen)
    result[step] = lists[step].index(target)
    for other, index in chosen.items():
        if other != step and lists[other][index] == source and target in lists[other]:
            result[other] = lists[other].index(target)
    return result


def _places_given_up(before, after) -> int:
    return sum(abs(after[s] - before[s]) for s in after)


def _total(lists, chosen, owned) -> int:
    """Upfront cost: each product once, and nothing for products the client already owns."""
    products = list(dict.fromkeys(lists[step][index] for step, index in chosen.items()))
    return sum(p.cheapest_price_cents for p in products if p not in owned and p.cheapest_price_cents is not None)


def monthly_cost(picks: dict[Step, Pick]) -> int | None:
    """Estimated cost per month in cents (owned products count: they need rebuying), or None if a size is unknown.

    Grams count as millilitres (skin care is close to water's density); a product used AM and PM runs out twice as fast.
    """
    uses_per_day: dict[Product, int] = {}
    for pick in picks.values():
        uses_per_day[pick.product] = uses_per_day.get(pick.product, 0) + 1
    total = Decimal(0)
    for product, uses in uses_per_day.items():
        offer = product.offer
        if offer.size_amount is None:
            return None
        ml_per_month = ML_PER_USE[product.category] * (uses * DAYS)
        total += (Decimal(offer.price_cents) * ml_per_month / offer.size_amount).quantize(
            Decimal("0.01"), rounding=ROUND_HALF_UP)
    return int(total.quantize(Decimal(1), rounding=ROUND_HALF_UP))
