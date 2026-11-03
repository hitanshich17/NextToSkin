package com.nexttoskin.core.importer.obf;

import java.util.List;
import java.util.Map;

/**
 * Maps Open Beauty Facts category tags ("en:facial-creams", "en:in-sun-protections", ...) to NextToSkin's
 * small set of product categories.
 *
 * <p>Routines use four face categories: cleanser, treatment, moisturizer, sunscreen. Every other
 * category is kept in the catalog but never picked for a routine.
 */
public final class ObfCategories {

    /** Checked in order; the first match wins, so more specific categories come first. */
    private static final List<Map.Entry<String, List<String>>> RULES = List.of(
            Map.entry("sunscreen", List.of("sunscreen", "sun-protection", "solar-cream")),
            Map.entry("hair-dye", List.of("hair-dye")),
            Map.entry("shampoo", List.of("shampoo")),
            Map.entry("conditioner", List.of("hair-conditioner", "hair-mask")),
            Map.entry("oral-care", List.of("toothpaste", "mouthwash")),
            Map.entry("deodorant", List.of("deodorant")),
            Map.entry("fragrance", List.of("perfume", "eau-de-toilette", "eau-de-parfum")),
            Map.entry("nail", List.of("nail-")),
            Map.entry("lip-care", List.of("lip-")),
            Map.entry("makeup-remover", List.of("makeup-remover")),
            Map.entry("cleanser", List.of("cleanser", "cleansing", "face-wash")),
            Map.entry("shaving", List.of("shaving", "aftershave")),
            Map.entry("body-wash", List.of("shower-gel", "showers-and-baths")),
            Map.entry("soap", List.of("soap")),
            Map.entry("face-mask", List.of("face-mask")),
            Map.entry("exfoliant", List.of("scrub")),
            Map.entry("treatment", List.of("serum")),
            Map.entry("toner", List.of("toner", "face-lotion")),
            Map.entry("makeup", List.of("makeup", "foundation", "mascara", "bb-cream", "cc-cream", "dd-cream")),
            Map.entry("body-care", List.of("body-", "hand-cream", "foot-")),
            Map.entry("moisturizer", List.of("cream", "moisturi")),
            Map.entry("oil", List.of("-oils", "baby-oil")));

    private ObfCategories() {
    }

    /** Returns a NextToSkin category, or null if none of the tags match. */
    public static String categorize(List<String> tags) {
        if (tags == null) {
            return null;
        }
        List<String> english = tags.stream().filter(t -> t.startsWith("en:")).toList();
        for (var rule : RULES) {
            for (String tag : english) {
                if (rule.getValue().stream().anyMatch(tag::contains)) {
                    return rule.getKey();
                }
            }
        }
        return null;
    }
}
