package com.nexttoskin.core.ingredient;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns an ingredient name as written on a label into the lookup key stored in
 * {@code ingredient_aliases.alias_normalized}. "AQUA", "Aqua." and " aqua " all become "aqua".
 */
public final class IngredientNames {

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern MARKERS = Pattern.compile("[*†°®™]");
    private static final Pattern SPACED_SLASH = Pattern.compile("\\s*/\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EDGE_JUNK = Pattern.compile("^[^\\p{L}\\p{N}(]+|[^\\p{L}\\p{N})]+$");

    private IngredientNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD);
        s = ACCENTS.matcher(s).replaceAll("");
        s = s.toLowerCase(Locale.ROOT);
        s = MARKERS.matcher(s).replaceAll("");
        s = SPACED_SLASH.matcher(s).replaceAll("/");
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();
        return EDGE_JUNK.matcher(s).replaceAll("");
    }
}
