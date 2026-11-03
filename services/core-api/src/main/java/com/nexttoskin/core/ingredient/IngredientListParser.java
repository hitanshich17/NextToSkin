package com.nexttoskin.core.ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a label's ingredient text into ingredients, in label order.
 *
 * <p>Real labels (seen in Open Beauty Facts) separate ingredients with commas, periods, " - " or bullets,
 * wrap synonyms in parentheses ("Aqua (Water)"), prefix headings ("Ingrédients :"), and end with
 * footnotes ("*organic"). This parser handles those; it does not decide which names are the same
 * ingredient. That happens in {@link IngredientResolver}, which knows the alias dictionary.
 */
public final class IngredientListParser {

    /** "Ingredients:", "INCI :", or a stray code before the list. Headings never contain commas. */
    private static final Pattern LEADING_LABEL = Pattern.compile("^[^:,]{0,60}:");

    /** Makeup lists end with optional colorants ("[+/- CI 77491, ...]"). Presence is uncertain, so we drop them. */
    private static final Pattern MAY_CONTAIN = Pattern.compile(
            "(?i)[\\[(]?\\s*\\+\\s*/\\s*-|\\bmay contain\\b|\\bpeut contenir\\b|\\bkann enthalten\\b");

    private static final Pattern HTML_ENTITY = Pattern.compile("&(quot|amp|apos|#39|nbsp);?");
    private static final Pattern NANO = Pattern.compile("(?i)[\\[(]\\s*nano\\s*[\\])]");
    private static final Pattern MARKERS = Pattern.compile("[*†°®™]");
    private static final Pattern PERCENTAGE = Pattern.compile("\\s*\\d+(?:[.,]\\d+)?\\s*%");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern EDGE_JUNK = Pattern.compile("^[^\\p{L}\\p{N}(]+|[^\\p{L}\\p{N})]+$");

    /** Footnotes and marketing text that end up between separators. Matched against normalized text. */
    private static final Pattern FOOTNOTE = Pattern.compile(
            "\\b(ingredients?|agriculture|farming|certified|certifie|origin|origine|biologique|www|http)\\b");

    /** Batch/lot codes such as "C0547C". Colour index numbers ("CI77891") are real ingredients. */
    private static final Pattern CODE = Pattern.compile("^(?!ci\\d)(?=.*\\d)[a-z0-9]{4,}$");

    /** Parenthesised words that describe an ingredient rather than name it: "Glycerin (Vegetable)". */
    private static final Pattern DESCRIPTOR = Pattern.compile(
            "^(nano|vegetable|vegetal|vegetale|organic|bio|natural|naturel|naturelle|and|or|et)$");

    private static final int MAX_NAME_LENGTH = 90;
    private static final int MAX_NAME_WORDS = 8;

    private IngredientListParser() {
    }

    public static List<LabelIngredient> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String s = text.strip();
        var mayContain = MAY_CONTAIN.matcher(s);
        if (mayContain.find()) {
            s = s.substring(0, mayContain.start());
        }
        s = LEADING_LABEL.matcher(s).replaceFirst("");
        s = NANO.matcher(s).replaceAll(" ");
        s = HTML_ENTITY.matcher(s).replaceAll(" ");

        List<LabelIngredient> result = new ArrayList<>();
        for (String token : splitTopLevel(s)) {
            parseToken(token, result);
        }
        return result;
    }

    /** Splits on separators that are not inside brackets. */
    static List<String> splitTopLevel(String s) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && isSeparator(s, i)) {
                tokens.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        tokens.add(current.toString());
        tokens.removeIf(String::isBlank);
        return tokens;
    }

    private static boolean isSeparator(String s, int i) {
        char prev = i > 0 ? s.charAt(i - 1) : ' ';
        char next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
        return switch (s.charAt(i)) {
            // "1,2-Hexanediol" keeps its comma
            case ',' -> !(Character.isDigit(prev) && Character.isDigit(next));
            case ';', '•', '|', '\n', '\r' -> true;
            // "Aqua. Glycerin." but not "0.5%"
            case '.' -> Character.isWhitespace(next);
            // "Aqua - Glycerin" but not "PEG-100"
            case '-', '–' -> Character.isWhitespace(prev) && Character.isWhitespace(next);
            default -> false;
        };
    }

    private static void parseToken(String token, List<LabelIngredient> out) {
        String t = token.strip();
        if (t.startsWith("*")) {
            return; // footnote such as "*organic ingredients"
        }

        // Pull out bracketed groups, remembering whether each one ends the token.
        StringBuilder outer = new StringBuilder();
        List<String> groups = new ArrayList<>();
        List<Integer> groupEnds = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(' || c == '[') {
                if (depth++ > 0) {
                    group.append(c);
                }
            } else if ((c == ')' || c == ']') && depth > 0) {
                if (--depth == 0) {
                    groups.add(group.toString());
                    groupEnds.add(outer.length());
                    group.setLength(0);
                } else {
                    group.append(c);
                }
            } else if (depth > 0) {
                group.append(c);
            } else {
                outer.append(c);
            }
        }
        if (depth > 0) { // unclosed bracket: treat the rest as a group
            groups.add(group.toString());
            groupEnds.add(outer.length());
        }

        String name = clean(outer.toString());
        String nameKey = IngredientNames.normalize(name);
        List<String> synonyms = new ArrayList<>();
        List<LabelIngredient> subIngredients = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            String content = groups.get(g);
            boolean trailing = outer.substring(groupEnds.get(g)).isBlank();
            if (splitTopLevel(content).size() > 1) {
                // "Parfum (Limonene, Linalool)": the brackets list sub-ingredients
                for (String sub : splitTopLevel(content)) {
                    parseToken(sub, subIngredients);
                }
            } else if (trailing) {
                // "Aqua (Water)": another name for the same ingredient
                String synonym = clean(content);
                String synonymKey = IngredientNames.normalize(synonym);
                if (isPlausibleName(synonym) && !synonymKey.equals(nameKey)
                        && !DESCRIPTOR.matcher(synonymKey).matches()) {
                    synonyms.add(synonym);
                }
            }
            // Otherwise a common name inside the INCI name, "Butyrospermum Parkii (Shea) Butter": dropped.
        }

        if (isPlausibleName(name)) {
            out.add(new LabelIngredient(name, synonyms));
        }
        out.addAll(subIngredients);
    }

    private static String clean(String s) {
        s = MARKERS.matcher(s).replaceAll("");
        s = PERCENTAGE.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();
        return EDGE_JUNK.matcher(s).replaceAll("");
    }

    /** INCI names are always in Latin script; anything else is label text in another language. */
    private static boolean isLatinScript(String s) {
        return s.codePoints().filter(Character::isLetter)
                .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
    }

    static boolean isPlausibleName(String name) {
        String n = IngredientNames.normalize(name);
        return !n.isEmpty()
                && n.chars().anyMatch(Character::isLetter)
                && isLatinScript(n)
                && n.length() <= MAX_NAME_LENGTH
                && n.split(" ").length <= MAX_NAME_WORDS
                && !FOOTNOTE.matcher(n).find()
                && !CODE.matcher(n).matches();
    }
}
