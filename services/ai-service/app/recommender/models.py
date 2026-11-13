"""The recommender's domain model: quiz answers, products (from the read model), climate and routine plans.

Ported from the Java catalog service (com.nexttoskin.core.routine); behavior must match it exactly until the Java
version is removed (tests/test_routine_parity.py).
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum
from typing import Mapping

# Ingredient tags, in the Java enum's declaration order (some outputs list tags in this order).
TAG_ORDER = [
    "FRAGRANCE", "FRAGRANCE_ALLERGEN", "ESSENTIAL_OIL", "DRYING_ALCOHOL", "NUT", "SOY", "RETINOID", "AHA", "BHA",
    "VITAMIN_C", "L_ASCORBIC_ACID", "BENZOYL_PEROXIDE", "NIACINAMIDE", "TRANEXAMIC_ACID", "AZELAIC_ACID", "CERAMIDE",
    "MENTHOL", "CAMPHOR", "SODIUM_LAURYL_SULFATE", "UREA", "IRON_OXIDE", "HYALURONIC_ACID", "PEPTIDE", "CENTELLA",
    "CHEMICAL_UV_FILTER",
]
ROUTINE_CATEGORIES = ("cleanser", "treatment", "moisturizer", "sunscreen")


class SkinType(str, Enum):
    OILY = "OILY"
    DRY = "DRY"
    COMBINATION = "COMBINATION"
    NORMAL = "NORMAL"
    NOT_SURE = "NOT_SURE"


class Concern(str, Enum):
    BREAKOUTS = "BREAKOUTS"
    DARK_SPOTS = "DARK_SPOTS"
    UNEVEN_TONE = "UNEVEN_TONE"
    FINE_LINES = "FINE_LINES"
    REDNESS = "REDNESS"
    DULLNESS = "DULLNESS"
    DRYNESS = "DRYNESS"
    FLAKY_PATCHES = "FLAKY_PATCHES"
    OILINESS = "OILINESS"

    @property
    def label(self) -> str:
        """How the concern reads in a sentence."""
        return CONCERN_LABELS[self]


CONCERN_LABELS = {
    Concern.BREAKOUTS: "breakouts", Concern.DARK_SPOTS: "dark spots and marks", Concern.UNEVEN_TONE: "uneven tone",
    Concern.FINE_LINES: "fine lines", Concern.REDNESS: "redness", Concern.DULLNESS: "dullness and texture",
    Concern.DRYNESS: "dryness", Concern.FLAKY_PATCHES: "flaky patches", Concern.OILINESS: "oiliness and pores",
}


class Reactivity(str, Enum):
    RARELY = "RARELY"
    SOMETIMES = "SOMETIMES"
    OFTEN = "OFTEN"


class Avoid(str, Enum):
    FRAGRANCE = "FRAGRANCE"
    ESSENTIAL_OILS = "ESSENTIAL_OILS"
    ALCOHOL = "ALCOHOL"
    NUTS = "NUTS"
    SOY = "SOY"


class ActivesExperience(str, Enum):
    NEVER = "NEVER"
    A_LITTLE = "A_LITTLE"
    REGULARLY = "REGULARLY"


class Pregnancy(str, Enum):
    YES = "YES"
    NO = "NO"
    PREFER_NOT_TO_SAY = "PREFER_NOT_TO_SAY"


@dataclass(frozen=True)
class QuizAnswers:
    """Answers to the quiz in docs/quiz.md."""

    skin_type: SkinType
    concerns: tuple[Concern, ...]
    reactivity: Reactivity
    actives_experience: ActivesExperience
    pregnancy: Pregnancy
    avoid: frozenset[Avoid] = frozenset()
    budget_cents: int | None = None
    city: str | None = None
    skin_tone: int | None = None

    def __post_init__(self):
        if not 1 <= len(self.concerns) <= 2:
            raise ValueError("pick 1 or 2 concerns")
        if len(set(self.concerns)) != len(self.concerns):
            raise ValueError("concerns must be different")
        if self.skin_tone is not None and not 1 <= self.skin_tone <= 6:
            raise ValueError("skin tone is 1 to 6")
        if self.budget_cents is not None and self.budget_cents <= 0:
            raise ValueError("budget must be positive (leave it out for no limit)")

    @classmethod
    def from_json(cls, data: Mapping) -> "QuizAnswers":
        for name in ("skinType", "concerns", "reactivity", "activesExperience", "pregnancy"):
            if data.get(name) is None:
                raise ValueError(f"{name} is required")
        return cls(
            skin_type=SkinType(data["skinType"]),
            concerns=tuple(Concern(c) for c in data["concerns"]),
            reactivity=Reactivity(data["reactivity"]),
            actives_experience=ActivesExperience(data["activesExperience"]),
            pregnancy=Pregnancy(data["pregnancy"]),
            avoid=frozenset(Avoid(a) for a in data.get("avoid") or []),
            budget_cents=data.get("budgetCents"),
            city=data.get("city"),
            skin_tone=data.get("skinTone"),
        )

    def has(self, concern: Concern) -> bool:
        return concern in self.concerns

    @property
    def pregnant(self) -> bool:
        return self.pregnancy == Pregnancy.YES


class Texture(str, Enum):
    LIGHT = "LIGHT"
    RICH = "RICH"
    UNKNOWN = "UNKNOWN"


_LIGHT = re.compile(r"\b(gel|water|fluid|essence|jelly|foam|whip|lotion|serum|liquid)\b")
_RICH = re.compile(r"\b(cream|balm|butter)\b")
_MAKEUP_REMOVER = re.compile(r"\b(cleansing (balm|oil)|(balm|oil) cleanser)\b")


@dataclass(frozen=True)
class Offer:
    retailer: str
    price_cents: int
    size_amount: Decimal | None
    size_unit: str | None
    url: str


@dataclass(frozen=True, eq=False)
class Product:
    """A catalog product as the rules see it. Two products are the same product when their ids match."""

    MAIN_ACTIVE_MAX_POSITION = 10
    POTENT_AT_LOW_LEVELS = frozenset({"RETINOID", "PEPTIDE"})

    id: str
    brand: str | None
    name: str
    category: str | None
    spf: int | None
    imported: bool
    # Earliest label position of an ingredient with each tag; 0 = declared OTC active.
    first_positions: Mapping[str, int]
    offer: Offer | None
    ingredients: frozenset[str] = field(default_factory=frozenset)

    def __eq__(self, other):
        return isinstance(other, Product) and other.id == self.id

    def __hash__(self):
        return hash(self.id)

    def __repr__(self):
        return f"Product({self.id})"

    @classmethod
    def from_snapshot(cls, s: Mapping) -> "Product":
        """From a product-updated event's product (schemas/product-updated/v1.json)."""
        positions: dict[str, int] = {}
        for ingredient in s["ingredients"]:
            for tag in ingredient["tags"]:
                positions[tag] = min(positions.get(tag, ingredient["position"]), ingredient["position"])
        for active in s["actives"]:
            for tag in active["tags"]:
                positions[tag] = 0
        offers = sorted(s["offers"], key=lambda o: (o["priceCents"], o["sizeAmount"] is None,
                                                    -(o["sizeAmount"] or 0), o["retailer"], o["url"]))
        cheapest = None
        if offers:
            o = offers[0]
            cheapest = Offer(o["retailer"], o["priceCents"],
                             None if o["sizeAmount"] is None else Decimal(str(o["sizeAmount"])), o["sizeUnit"], o["url"])
        return cls(id=s["id"], brand=s["brand"], name=s["name"], category=s["category"], spf=s.get("spf"),
                   imported=s["imported"], first_positions=positions, offer=cheapest,
                   ingredients=frozenset(i["name"] for i in s["ingredients"]))

    @property
    def cheapest_price_cents(self) -> int | None:
        return None if self.offer is None else self.offer.price_cents

    def has(self, tag: str) -> bool:
        return tag in self.first_positions

    def has_any(self, tags) -> bool:
        return any(self.has(t) for t in tags)

    def has_main_active(self, tag: str) -> bool:
        """The tag is a main active of this product rather than a minor ingredient."""
        position = self.first_positions.get(tag)
        return position is not None and (position <= self.MAIN_ACTIVE_MAX_POSITION or tag in self.POTENT_AT_LOW_LEVELS)

    def is_otc_active(self, tag: str) -> bool:
        return self.first_positions.get(tag) == 0

    @property
    def mineral(self) -> bool:
        return not self.has("CHEMICAL_UV_FILTER")

    @property
    def tinted(self) -> bool:
        return self.has("IRON_OXIDE")

    @property
    def texture(self) -> Texture:
        """Guessed from the product name until the catalog stores it."""
        name = self.name.lower()
        if _LIGHT.search(name):
            return Texture.LIGHT
        if _RICH.search(name):
            return Texture.RICH
        return Texture.UNKNOWN

    @property
    def makeup_remover(self) -> bool:
        """Cleansing balms and oils are never picked as the routine cleanser."""
        return self.category == "cleanser" and bool(_MAKEUP_REMOVER.search(self.name.lower()))


class Signal(str, Enum):
    HIGH_UV = "HIGH_UV"
    HUMID = "HUMID"
    DRY_AIR = "DRY_AIR"
    POLLUTED = "POLLUTED"


@dataclass(frozen=True)
class Climate:
    """A city's climate over the last 30 days (docs/routine-rules.md, section 6)."""

    HIGH_UV = 8.0             # EPA UV index "very high"
    HUMID_DEW_POINT_C = 18.3  # NWS: 65 °F and up feels humid
    DRY_DEW_POINT_C = 4.4     # 40 °F, author's threshold
    DRY_HUMIDITY = 40.0
    POLLUTED_PM25 = 9.0       # EPA annual PM2.5 standard (2024)

    city: str
    state: str
    uv_index_max: float
    dew_point_c: float
    humidity: float
    pm25: float | None

    def signals(self) -> list[Signal]:
        signals = []
        if self.uv_index_max >= self.HIGH_UV:
            signals.append(Signal.HIGH_UV)
        if self.dew_point_c >= self.HUMID_DEW_POINT_C:
            signals.append(Signal.HUMID)
        elif self.dew_point_c < self.DRY_DEW_POINT_C or self.humidity < self.DRY_HUMIDITY:
            signals.append(Signal.DRY_AIR)
        if self.pm25 is not None and self.pm25 > self.POLLUTED_PM25:
            signals.append(Signal.POLLUTED)
        return signals

    def has(self, signal: Signal) -> bool:
        return signal in self.signals()


class Reason(str, Enum):
    """Why a product was disliked; decides the replacement."""

    IRRITATED = "IRRITATED"
    DIDNT_WORK = "DIDNT_WORK"
    TEXTURE = "TEXTURE"
    TOO_PRICEY = "TOO_PRICEY"


@dataclass(frozen=True)
class TriedProducts:
    """What a client said about products they tried (docs/feedback.md), by product id."""

    liked: frozenset[str] = frozenset()
    disliked: Mapping[str, Reason] = field(default_factory=dict)  # a dislike without a reason = IRRITATED
    similarity: Mapping[str, float] = field(default_factory=dict)  # resemblance to liked minus disliked

    def likes(self, p: Product) -> bool:
        return p.id in self.liked

    def dislikes(self, p: Product) -> bool:
        return p.id in self.disliked

    def similarity_to(self, p: Product) -> float:
        """Rounded to 0.1 (half up, like Java's Math.round), so tiny differences count as a tie."""
        return math.floor(self.similarity.get(p.id, 0.0) * 10 + 0.5) / 10

    def disliked_with(self, catalog: list[Product], reason: Reason) -> list[Product]:
        return [p for p in catalog if self.disliked.get(p.id) == reason]


NO_FEEDBACK = TriedProducts()


class Step(str, Enum):
    """The Core 4 routine, in the order the steps are applied."""

    AM_CLEANSER = "AM_CLEANSER"
    AM_MOISTURIZER = "AM_MOISTURIZER"
    AM_SUNSCREEN = "AM_SUNSCREEN"
    PM_CLEANSER = "PM_CLEANSER"
    PM_TREATMENT = "PM_TREATMENT"
    PM_MOISTURIZER = "PM_MOISTURIZER"

    @property
    def category(self) -> str:
        return self.value.split("_", 1)[1].lower()


class Note(str, Enum):
    """Messages shown with the routine. Wording and sources are in docs/routine-rules.md."""

    RETINOID_START_SLOWLY = "RETINOID_START_SLOWLY"
    VITAMIN_C_NEEDS_SUNSCREEN = "VITAMIN_C_NEEDS_SUNSCREEN"
    AHA_SUNBURN_ALERT = "AHA_SUNBURN_ALERT"
    SEE_DERMATOLOGIST_FOR_PATCHES = "SEE_DERMATOLOGIST_FOR_PATCHES"
    NO_RETINOIDS_IN_PREGNANCY = "NO_RETINOIDS_IN_PREGNANCY"
    NO_TREATMENT_FITS = "NO_TREATMENT_FITS"
    HIGH_UV_REAPPLY = "HIGH_UV_REAPPLY"
    AIR_POLLUTION = "AIR_POLLUTION"
    TREATMENT_CHANGED_FOR_BUDGET = "TREATMENT_CHANGED_FOR_BUDGET"
    LIKED_PRODUCT_LEFT_OUT = "LIKED_PRODUCT_LEFT_OUT"
    NO_PRODUCT_FITS_STEP = "NO_PRODUCT_FITS_STEP"
    OVER_BUDGET = "OVER_BUDGET"

    @property
    def text(self) -> str:
        return NOTE_TEXT[self]

    @staticmethod
    def for_treatment(active: str | None) -> "Note | None":
        """The note a treatment with this active always comes with, or None."""
        return {"RETINOID": Note.RETINOID_START_SLOWLY, "VITAMIN_C": Note.VITAMIN_C_NEEDS_SUNSCREEN,
                "AHA": Note.AHA_SUNBURN_ALERT}.get(active)


NOTE_TEXT = {
    Note.RETINOID_START_SLOWLY: "Use your retinoid at night only. Start every other night and build up as your skin "
                                "tolerates it, and wear sunscreen every day.",
    Note.VITAMIN_C_NEEDS_SUNSCREEN: "Vitamin C does not protect you from the sun. Use it together with a "
                                    "broad-spectrum SPF 30+ sunscreen every morning.",
    Note.AHA_SUNBURN_ALERT: "This routine contains an alpha hydroxy acid (AHA), which may increase your skin's "
                            "sensitivity to the sun. Use sunscreen and limit sun exposure while using it and for a "
                            "week afterwards.",
    Note.SEE_DERMATOLOGIST_FOR_PATCHES: "If patches are itchy, scaly, spreading, or don't improve within 2-3 weeks, "
                                        "see a dermatologist. This can be a skin condition (such as eczema, psoriasis "
                                        "or seborrheic dermatitis) rather than dryness.",
    Note.NO_RETINOIDS_IN_PREGNANCY: "Retinoids are left out because they should not be used during pregnancy or "
                                    "while breastfeeding.",
    Note.NO_TREATMENT_FITS: "No treatment in our catalog fits all your answers, so your night routine has no "
                            "treatment step.",
    Note.HIGH_UV_REAPPLY: "The sun is very strong where you live. Reapply sunscreen every 2 hours when outdoors, "
                          "and right after swimming or sweating.",
    Note.AIR_POLLUTION: "Air pollution in your city is linked to dark spots and wrinkles. Daily sunscreen and "
                        "cleansing at night are the basics that help.",
    Note.TREATMENT_CHANGED_FOR_BUDGET: "To fit your budget, your night treatment is a different type than our first "
                                       "choice for your concern. It still targets the same concern.",
    Note.LIKED_PRODUCT_LEFT_OUT: "A product you liked isn't in this routine because it doesn't fit your current "
                                 "answers.",
    Note.NO_PRODUCT_FITS_STEP: "For at least one step, no product in our catalog fits all your answers and past "
                               "reactions, so that step is left empty.",
    Note.OVER_BUDGET: "Even the lowest-priced routine that fits your answers costs more than your budget. "
                      "This is that routine.",
}


@dataclass(frozen=True)
class RoutinePlan:
    """Ranked candidates for each step, before the budget is applied."""

    candidates: Mapping[Step, list[Product]]
    treatment_active: str | None
    notes: list[Note]
    # Treatments for the concern's next actives, in order of preference; used only when the budget can't be met.
    budget_fallbacks: Mapping[str, list[Product]] = field(default_factory=dict)
    owned: frozenset[Product] = frozenset()  # liked products in the routine: $0 upfront
    avoided: list[str] = field(default_factory=list)  # what past reactions rule out, in words
