"""Plain-language "why this routine" from the rules engine's own output.

The LLM only explains decisions the rules already made; it never picks products or makes safety calls.
Safety notes are shown on the page word for word, so the LLM is told to leave them out entirely.
Its answer is checked before use, and core-api falls back to a template explanation otherwise.
"""

import difflib
import json
import re

from pydantic import BaseModel, Field

from app.llm import Llm


class StepFacts(BaseModel):
    time: str  # AM or PM
    step: str  # cleanser, treatment, moisturizer, sunscreen
    product: str  # brand and name
    key_ingredients: list[str] = Field(default_factory=list, alias="keyIngredients")
    already_owned: bool = Field(default=False, alias="alreadyOwned")
    reasons: list[str] = Field(default_factory=list)  # why it won its step, in the rules' own words


class ExplanationFacts(BaseModel):
    """What the rules decided. Contains no pregnancy answer and no city (privacy, docs/feedback.md)."""

    skin_type: str = Field(alias="skinType")
    concerns: list[str]
    reactivity: str  # rarely, sometimes, often
    avoid: list[str] = Field(default_factory=list)
    actives_experience: str = Field(alias="activesExperience")  # never, a little, regularly
    climate: list[str] = Field(default_factory=list)  # e.g. "humid air"
    treatment_active: str | None = Field(default=None, alias="treatmentActive")
    treatment_changed_for_budget: bool = Field(default=False, alias="treatmentChangedForBudget")
    steps: list[StepFacts]
    avoided_for_you: list[str] = Field(default_factory=list, alias="avoidedForYou")
    notes: list[str] = Field(default_factory=list)  # shown on the page as they are; never sent to the LLM
    within_budget: bool = Field(default=True, alias="withinBudget")


# What each key ingredient is for, in the modest wording of docs/routine-rules.md (section 1). The LLM may only
# describe ingredients with these phrases, so it can't invent effects or side effects.
# Keys must match the names core-api sends (ExplanationFacts.ACTIVES and lower-cased tag names).
ROLES = {
    "a retinoid": "one of the best-studied ingredients for breakouts and fine lines",
    "azelaic acid": "calms redness and fades dark marks",
    "an AHA (alpha hydroxy acid)": "exfoliates the surface for smoother, brighter-looking skin",
    "salicylic acid (a BHA)": "clears out pores",
    "vitamin C": "brightens and fades dark spots",
    "niacinamide": "helps with oil, pores and uneven tone",
    "tranexamic acid": "fades discoloration",
    "benzoyl peroxide": "fights the bacteria behind breakouts",
    "ceramides": "support the skin barrier",
    "hyaluronic acid": "draws water into the skin",
    "peptides": "support firmness",
    "centella": "soothes the skin",
    "urea": "softens dry, rough skin",
}

# Unambiguous wording, so "a little" isn't retold as "new to actives".
EXPERIENCE = {
    "never": "Has never used actives such as retinoids or acids",
    "a little": "Has used actives such as retinoids or acids a little (not new to them)",
    "regularly": "Uses actives such as retinoids or acids regularly",
}

SYSTEM_PROMPT = """You write a short explanation of a skincare routine that a rules engine has already chosen, for a \
general US audience, in plain, friendly American English.

Explain why: why the night treatment fits the main concern, and why the other products were picked.

Rules:
- Every statement must come from the facts you are given. Describe an ingredient only with its role from \
"ingredient roles"; never add other benefits, side effects or advice.
- Refer to each product only by its placeholder, copied exactly with its braces, e.g. {PM treatment}. Never \
write a brand or product name yourself.
- Give each product only the reasons listed for it. If a product has no reasons, name it without saying why, \
or leave it out. Never describe how a product feels, cleans or protects beyond its reasons.
- Describe the person only with the profile lines given; don't add traits.
- Safety notes (sun sensitivity, sunscreen reapplication, how often to use a product, pregnancy) are shown to the \
reader separately. Do not mention, repeat or reword any of them.
- This is not medical advice: never diagnose, never promise results, never use words like "cure" or "guarantee".
- Speak to the reader as "you", warmly and naturally. Never mention facts, lists, reasons, placeholders or rules.
- At most 4 short sentences and under 80 words, one paragraph, no lists, headings or links."""

MIN_LENGTH = 80
MAX_LENGTH = 700
MAX_SENTENCES = 4
# Room for thinking on models that think by default (Haiku 5.5); the length check keeps the answer short.
MAX_TOKENS = 2000
# Words an explanation must never use: medical claims and promises.
BANNED = re.compile(r"\b(cure[sd]?|diagnos\w*|prescri\w*|guarantee\w*|miracle|clinically proven)\b", re.IGNORECASE)
# The safety notes' territory: the page shows those notes word for word, so the LLM must not touch them.
SAFETY_NOTE_TOPICS = re.compile(
    r"sun[- ]?sensitiv|sensitiv\w* to (the )?sun|reapply|every other night|limit (your )?sun|pregnan|breastfeed",
    re.IGNORECASE)
REACTIVE_WORDS = re.compile(r"\b(sensitive|reactive|sensitivity|reactivity)\b", re.IGNORECASE)
LINK = re.compile(r"https?://|www\.", re.IGNORECASE)
# Words that show the instructions through ("which the facts list as", "listed without reasons").
LEAKS = re.compile(r"\b(the facts|facts list|listed (as|for|without)|is listed|are listed|placeholder)\b", re.IGNORECASE)
SENTENCE_END = re.compile(r"[.!?](\s|$)")
WORD = re.compile(r"[A-Za-z][A-Za-z'-]{3,}")
PLACEHOLDER = re.compile(r"\{([^{}]*)\}")


class InvalidExplanation(Exception):
    def __init__(self, reason: str, text: str = ""):
        super().__init__(reason)
        self.text = text  # the rejected answer, for the log (facts and product names only, nothing personal)


def misspelled_product_word(text: str, facts: ExplanationFacts) -> str | None:
    """A word that is almost, but not exactly, a word of a product name (e.g. "Neutrogela"); users search for these."""
    name_words = {w for s in facts.steps for w in WORD.findall(s.product)}
    for word in set(WORD.findall(text)) - name_words:
        if word[0].isupper() and difflib.get_close_matches(word, name_words, n=1, cutoff=0.8):
            return word
    return None


def user_message(facts: ExplanationFacts) -> str:
    """The facts as the LLM sees them: profile lines, the picks, and ingredient roles. No notes."""
    profile = [
        f"Skin type: {facts.skin_type}",
        f"Main concern: {facts.concerns[0]}",
        *([f"Second concern: {facts.concerns[1]}"] if len(facts.concerns) > 1 else []),
        f"Reacts to new products: {facts.reactivity}",
        EXPERIENCE.get(facts.actives_experience, f"Experience with actives: {facts.actives_experience}"),
        *([f"Avoids: {', '.join(facts.avoid)}"] if facts.avoid else []),
        *([f"Climate where they live: {', '.join(facts.climate)}"] if facts.climate else []),
        *([f"Left out because of past reactions: {', '.join(facts.avoided_for_you)}"] if facts.avoided_for_you else []),
    ]
    if facts.treatment_active is None:
        treatment = "No treatment fits all the answers, so the night routine has none."
    elif facts.treatment_changed_for_budget:
        treatment = (f"The night treatment uses {facts.treatment_active}, the next option for the main concern, "
                     "picked so the routine fits the budget.")
    else:
        treatment = f"The night treatment uses {facts.treatment_active}, the first choice for the main concern."
    ingredients = sorted({i for s in facts.steps for i in s.key_ingredients})
    # Product names stay out: the LLM writes placeholders, and fill_placeholders() puts the exact names back,
    # so a name can never be misspelled.
    # One entry per product: a cleanser used morning and night is one {cleanser}, not two.
    steps = []
    for s in facts.steps:
        if any(p["placeholder"] == placeholder(s, facts) for p in steps):
            continue
        times = sorted({o.time for o in facts.steps if o.product == s.product and o.step == s.step})
        steps.append({"placeholder": placeholder(s, facts), "used": "morning and night" if len(times) == 2
                      else ("morning" if times == ["AM"] else "night"), "key ingredients": s.key_ingredients,
                      "reasons": s.reasons, **({"already owned": True} if s.already_owned else {})})
    content = {
        "profile": profile,
        "treatment": treatment,
        "steps": steps,
        "ingredient roles": {i: ROLES[i] for i in ingredients if i in ROLES},
        "budget": "fits the budget" if facts.within_budget else "the cheapest routine is over the budget",
    }
    return "Facts:\n" + json.dumps(content, indent=2)


def placeholder(step: StepFacts, facts: ExplanationFacts) -> str:
    """{cleanser} for a product used morning and night, {AM cleanser} / {PM cleanser} when they differ."""
    both = sum(1 for o in facts.steps if o.step == step.step and o.product == step.product) == 2
    return "{" + (step.step if both else f"{step.time} {step.step}") + "}"


def fill_placeholders(text: str, facts: ExplanationFacts) -> str:
    """Replaces each placeholder with the exact product name; unknown ones are rejected."""
    names = {placeholder(s, facts).lower(): s.product for s in facts.steps}

    def name(match: re.Match) -> str:
        key = match.group(0).lower()  # {Moisturizer} at the start of a sentence is still {moisturizer}
        if key not in names:
            raise InvalidExplanation(f"unknown placeholder: {match.group(0)}")
        return names[key]

    text = PLACEHOLDER.sub(name, text)
    # "Your {moisturizer}" or "the {moisturizer}" -> "The Ordinary ...", not "Your The ..." / "the The ..."
    return re.sub(r"\b([Yy]our|[Tt]he) (The )", r"\2", text)


def explain(facts: ExplanationFacts, llm: Llm) -> str:
    text = llm.complete(SYSTEM_PROMPT, user_message(facts), MAX_TOKENS).strip()
    text = fill_placeholders(text, facts)
    try:
        check(text, facts)
    except InvalidExplanation as e:
        raise InvalidExplanation(str(e), text) from None
    return text


def check(text: str, facts: ExplanationFacts) -> None:
    """Raises InvalidExplanation if the text breaks a rule we can check mechanically."""
    if not MIN_LENGTH <= len(text) <= MAX_LENGTH:
        raise InvalidExplanation(f"length {len(text)}")
    if len(SENTENCE_END.findall(text)) > MAX_SENTENCES:
        raise InvalidExplanation("too many sentences")
    if match := BANNED.search(text):
        raise InvalidExplanation(f"banned word: {match.group(0)}")
    if match := SAFETY_NOTE_TOPICS.search(text):
        raise InvalidExplanation(f"restates a safety note: {match.group(0)}")
    if facts.reactivity == "rarely" and (match := REACTIVE_WORDS.search(text)):
        raise InvalidExplanation(f"calls skin {match.group(0)} that rarely reacts")
    if LINK.search(text):
        raise InvalidExplanation("contains a link")
    if match := LEAKS.search(text):
        raise InvalidExplanation(f"mentions its instructions: {match.group(0)}")
    if typo := misspelled_product_word(text, facts):
        raise InvalidExplanation(f"misspelled product name: {typo}")
    if "\n-" in text or text.lstrip().startswith(("-", "*", "#")):
        raise InvalidExplanation("not a paragraph")
