package com.nexttoskin.core.ingredient;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kinds of ingredients the quiz and routine rules care about. Each tag is a pattern over normalized
 * ingredient names (see {@link IngredientNames#normalize}); an ingredient gets a tag if any of its names match.
 *
 * <p>Decisions behind the patterns (made by the product owner):
 * <ul>
 *   <li>Coconut and its derivatives are not nuts; shea is.</li>
 *   <li>Avoiding "fragrance" also avoids the labelled fragrance allergens (limonene, linalool, ...).</li>
 *   <li>Essential oils are distilled plant oils (tea tree, lavender, citrus peel, rose, ...), not carrier
 *       oils (olive, seed and kernel oils) and not extracts or waters.</li>
 *   <li>AHAs are the exfoliating acids only; citric/malic acid are pH adjusters here, and copolymers are not acids.</li>
 * </ul>
 */
public enum IngredientTag {

    FRAGRANCE("\\b(fragrance|parfum|perfume)\\b"),

    /** EU-labelled fragrance allergens. Benzyl alcohol is left out: in skincare it is almost always a preservative. */
    FRAGRANCE_ALLERGEN("\\b(limonene|linalool|linalyl acetate|citronellol|geraniol|citral|eugenol|isoeugenol|coumarin"
            + "|hexyl cinnamal|amyl cinnamal|cinnamal|cinnamyl alcohol|benzyl benzoate|benzyl salicylate|benzyl cinnamate"
            + "|farnesol|hydroxycitronellal|alpha-isomethyl ionone|butylphenyl methylpropional|anise alcohol"
            + "|evernia prunastri|evernia furfuracea)\\b"),

    ESSENTIAL_OIL(null) {
        private final Pattern plants = Pattern.compile("\\b(tea tree|melaleuca alternifolia|lavandula|lavender|mentha"
                + "|peppermint|spearmint|eucalyptus|citrus|bergamia|bergamot|lemon|lime|orange|mandarin|grapefruit"
                + "|pelargonium|geranium|litsea cubeba|cymbopogon|lemongrass|rosmarinus|rosemary|rosa damascena"
                + "|anthemis nobilis|chamomilla|pinus sylvestris|cananga|ylang|santalum|sandalwood|cedrus|cedarwood"
                + "|jasminum|neroli|pogostemon|patchouli|salvia sclarea|thymus|eugenia caryophyllus|clove|cinnamomum)\\b");
        private final Pattern oil = Pattern.compile("\\boil\\b");
        private final Pattern carrier = Pattern.compile("\\b(seed|kernel|nut) oil\\b");

        @Override
        boolean matches(String name) {
            return oil.matcher(name).find() && plants.matcher(name).find() && !carrier.matcher(name).find();
        }
    },

    /** Short-chain, drying alcohols; fatty alcohols (cetyl, cetearyl, stearyl) moisturize and are not included. */
    DRYING_ALCOHOL("^(alcohol|alcohol denat|denatured alcohol|ethanol|isopropyl alcohol|sd alcohol( [0-9a-z-]+)?)$"),

    NUT("\\b(almond|amygdalus|macadamia|macadamiate|walnut|juglans|hazelnut|corylus|cashew|anacardium|pistachio"
            + "|pistacia vera|pecan|carya|argan|argania|brazil nut|bertholletia|shea|butyrospermum|vitellaria"
            + "|sclerocarya|marula|apricot kernel|armeniaca)\\b"),

    SOY("\\b(soy|soja|soybean|glycine max)\\b"),

    RETINOID("\\b(retinol|retinal|retinaldehyde|adapalene|tretinoin|tazarotene|retinyl [a-z]+|retinoate)\\b"),

    AHA("^(glycolic acid|lactic acid|mandelic acid)$"),

    /** Salicylic acid only: betaine salicylate is a much milder relative and doesn't count (author's decision). */
    BHA("^salicylic acid$"),

    VITAMIN_C("\\b(ascorbic acid|ascorbyl|ascorbate)\\b"),

    /**
     * Pure L-ascorbic acid (also tagged VITAMIN_C). It needs a low-pH formula that can sting reactive skin;
     * derivatives such as ascorbyl glucoside are gentler.
     */
    L_ASCORBIC_ACID("^(l-)?ascorbic acid$"),

    BENZOYL_PEROXIDE("\\bbenzoyl peroxide\\b"),

    NIACINAMIDE("^(niacinamide|nicotinamide)$"),

    TRANEXAMIC_ACID("\\btranexam(ic acid|ate)"),

    AZELAIC_ACID("\\bazelaic acid\\b"),

    CERAMIDE("\\bceramide\\b"),

    /** Rosacea irritants named by the AAD (with drying alcohol, fragrance and glycolic/lactic acid). */
    MENTHOL("\\b(menthol|menthyl [a-z]+|menthoxypropanediol)\\b"),

    CAMPHOR("\\bcamphor\\b"),

    SODIUM_LAURYL_SULFATE("^(sodium lauryl sulfate|sls)$"),

    UREA("^urea$"),

    /** Pigments in tinted sunscreens; they block visible light, which matters for discoloration. */
    IRON_OXIDE("\\biron oxides?\\b|^ci ?7749[129]$"),

    HYALURONIC_ACID("\\bhyaluron(ic acid|ate)\\b"),

    PEPTIDE("\\b([a-z]*peptide-?[0-9]*|oligopeptide|polypeptide)\\b"),

    CENTELLA("\\b(centella asiatica|madecassoside|madecassic acid|asiaticoside|asiatic acid)\\b"),

    /** Organic ("chemical") UV filters, US and international names. Sunscreens without any are mineral. */
    CHEMICAL_UV_FILTER("\\b(avobenzone|butyl methoxydibenzoylmethane|homosalate|octisalate|ethylhexyl salicylate"
            + "|octocrylene|octinoxate|ethylhexyl methoxycinnamate|oxybenzone|benzophenone-3|ensulizole"
            + "|phenylbenzimidazole sulfonic acid|bis-ethylhexyloxyphenol methoxyphenyl triazine"
            + "|methylene bis-benzotriazolyl tetramethylbutylphenol|diethylamino hydroxybenzoyl hexyl benzoate"
            + "|ethylhexyl triazone|diethylhexyl butamido triazone|isoamyl p-methoxycinnamate|polysilicone-15"
            + "|drometrizole trisiloxane|terephthalylidene dicamphor sulfonic acid)\\b");

    private final Pattern pattern;

    IngredientTag(String regex) {
        this.pattern = regex == null ? null : Pattern.compile(regex);
    }

    /** {@code name} must already be normalized (lowercase, single spaces). */
    boolean matches(String name) {
        return pattern.matcher(name).find();
    }

    /** All tags for one ingredient, given all of its names. */
    public static Set<IngredientTag> tagsFor(Iterable<String> names) {
        Set<IngredientTag> tags = EnumSet.noneOf(IngredientTag.class);
        for (String raw : names) {
            String name = IngredientNames.normalize(raw);
            for (IngredientTag tag : values()) {
                if (!name.isEmpty() && tag.matches(name)) {
                    tags.add(tag);
                }
            }
        }
        return tags;
    }
}
