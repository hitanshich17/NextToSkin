package com.nexttoskin.core.catalog;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.nexttoskin.core.ingredient.IngredientListParser;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates the curated catalog (catalog/products.csv and catalog/offers.csv).
 *
 * <p>Every row is checked before anything is written, and all problems are reported together with
 * their file and line, so a typo can never leave the catalog half-imported.
 */
public final class CatalogCsvReader {

    /** Products in the curated catalog are the ones routines are built from. */
    public static final Set<String> ROUTINE_CATEGORIES = Set.of("cleanser", "treatment", "moisturizer", "sunscreen");

    static final List<String> PRODUCT_COLUMNS =
            List.of("id", "brand", "name", "category", "actives", "ingredients", "image_url", "source_url", "import", "spf");
    static final List<String> OFFER_COLUMNS =
            List.of("product_id", "retailer", "price_usd", "size", "unit", "url", "checked_on");

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern PRICE = Pattern.compile("^\\d+(\\.\\d{1,2})?$");
    /** One active ingredient with its concentration, e.g. "Benzoyl Peroxide 4%". */
    private static final Pattern ACTIVE = Pattern.compile("^(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s*%$");

    /** Lowest SPF a sunscreen may have to be in the catalog (AAD recommends SPF 30 or higher). */
    public static final int MIN_SPF = 30;

    /**
     * {@code imported}: sold in the US only as an import (for sunscreens: not FDA-approved).
     * {@code spf}: sunscreens only; "SPF 50+" is written as 50.
     */
    public record CatalogProduct(String id, String brand, String name, String category, List<Active> actives,
                                 String ingredients, String imageUrl, String sourceUrl, boolean imported, Integer spf) {
    }

    /** An OTC drug active ingredient, listed on the label separately from the other ingredients. */
    public record Active(String name, BigDecimal percent) {
    }

    public record CatalogOffer(String productId, String retailer, int priceCents, Size size, String url,
                               LocalDate checkedOn) {
    }

    public record Catalog(List<CatalogProduct> products, List<CatalogOffer> offers) {
    }

    /** Thrown with every problem found; nothing should be imported. */
    public static class InvalidCatalogException extends RuntimeException {
        private final List<String> errors;

        InvalidCatalogException(List<String> errors) {
            super("Catalog has " + errors.size() + " problem(s):\n  " + String.join("\n  ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    private final List<String> errors = new ArrayList<>();
    private final LocalDate today;

    private CatalogCsvReader(LocalDate today) {
        this.today = today;
    }

    public static Catalog read(Reader products, Reader offers, LocalDate today) throws IOException {
        return new CatalogCsvReader(today).readBoth(products, offers);
    }

    private Catalog readBoth(Reader productsCsv, Reader offersCsv) throws IOException {
        List<CatalogProduct> products = new ArrayList<>();
        Set<String> productIds = new HashSet<>();
        for (var row : rows("products.csv", productsCsv, PRODUCT_COLUMNS).entrySet()) {
            CatalogProduct p = product(row.getKey(), row.getValue());
            if (p != null && !productIds.add(p.id())) {
                errors.add(row.getKey() + ": duplicate id '" + p.id() + "'");
            } else if (p != null) {
                products.add(p);
            }
        }

        List<CatalogOffer> offers = new ArrayList<>();
        Set<String> offerKeys = new HashSet<>();
        for (var row : rows("offers.csv", offersCsv, OFFER_COLUMNS).entrySet()) {
            CatalogOffer o = offer(row.getKey(), row.getValue(), productIds);
            if (o == null) {
                continue;
            }
            String key = o.productId() + "|" + o.retailer() + "|" + o.size();
            if (!offerKeys.add(key)) {
                errors.add(row.getKey() + ": duplicate offer for '" + o.productId() + "' at " + o.retailer()
                        + " in this size");
            } else {
                offers.add(o);
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidCatalogException(errors);
        }
        return new Catalog(products, offers);
    }

    /** Rows keyed by "file:line", in file order. Line 1 is the header. */
    private Map<String, Map<String, String>> rows(String file, Reader reader, List<String> expected)
            throws IOException {
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        // Forgiving for hand edits: spaces around values (and before quotes) and blank lines are ignored.
        CsvMapper mapper = CsvMapper.builder()
                .enable(CsvParser.Feature.TRIM_SPACES)
                .enable(CsvParser.Feature.SKIP_EMPTY_LINES)
                .build();
        try (MappingIterator<Map<String, String>> it = mapper.readerForMapOf(String.class)
                .with(CsvSchema.emptySchema().withHeader())
                .readValues(reader)) {
            boolean headerChecked = false;
            while (it.hasNext()) {
                Map<String, String> row = it.next();
                if (!headerChecked) {
                    // Check the header line itself: a row can omit trailing empty values.
                    List<String> header = new ArrayList<>();
                    ((CsvSchema) it.getParser().getSchema()).forEach(column -> header.add(column.getName()));
                    if (!header.containsAll(expected)) {
                        errors.add(file + ": header must contain " + String.join(",", expected));
                        return rows;
                    }
                    headerChecked = true;
                }
                // Line of the row's last value (for a quoted value spanning lines, where it starts).
                rows.put(file + ":" + it.getParser().currentTokenLocation().getLineNr(), row);
            }
        }
        return rows;
    }

    private CatalogProduct product(String where, Map<String, String> row) {
        int before = errors.size();
        String id = required(where, row, "id");
        String brand = required(where, row, "brand");
        String name = required(where, row, "name");
        String category = required(where, row, "category");
        String ingredients = required(where, row, "ingredients");
        String sourceUrl = required(where, row, "source_url");
        String imageUrl = optional(row, "image_url");
        List<Active> actives = actives(where, optional(row, "actives"));
        String importText = optional(row, "import");
        if (importText != null && !importText.equals("yes")) {
            errors.add(where + ": import must be 'yes' or empty, not '" + importText + "'");
        }
        boolean imported = "yes".equals(importText);
        Integer spf = spf(where, category, optional(row, "spf"));
        if ("sunscreen".equals(category) && !imported && actives.isEmpty()) {
            errors.add(where + ": a US sunscreen must list its UV filters in actives, e.g. 'Zinc Oxide 9%'"
                    + " (or set import to 'yes')");
        }

        if (id != null && !SLUG.matcher(id).matches()) {
            errors.add(where + ": id must be lowercase words joined by '-', e.g. cerave-hydrating-cleanser");
        }
        if (category != null && !ROUTINE_CATEGORIES.contains(category)) {
            errors.add(where + ": category must be one of " + String.join(", ", ROUTINE_CATEGORIES.stream().sorted().toList()));
        }
        if (ingredients != null && IngredientListParser.parse(ingredients).isEmpty()) {
            errors.add(where + ": no ingredients could be read from the ingredients column");
        }
        checkUrl(where, "source_url", sourceUrl);
        checkUrl(where, "image_url", imageUrl);
        return errors.size() == before
                ? new CatalogProduct(id, brand, name, category, actives, ingredients, imageUrl, sourceUrl, imported, spf)
                : null;
    }

    private CatalogOffer offer(String where, Map<String, String> row, Set<String> productIds) {
        int before = errors.size();
        String productId = required(where, row, "product_id");
        String retailer = required(where, row, "retailer");
        String price = required(where, row, "price_usd");
        String url = required(where, row, "url");
        String checked = required(where, row, "checked_on");
        String sizeText = optional(row, "size");
        String unit = optional(row, "unit");

        if (productId != null && !productIds.contains(productId)) {
            errors.add(where + ": product_id '" + productId + "' is not in products.csv");
        }
        if (retailer != null && !SLUG.matcher(retailer).matches()) {
            errors.add(where + ": retailer must be lowercase, e.g. target, ulta, brand");
        }
        Integer priceCents = null;
        if (price != null) {
            if (PRICE.matcher(price).matches() && new BigDecimal(price).signum() > 0) {
                priceCents = new BigDecimal(price).movePointRight(2).intValueExact();
            } else {
                errors.add(where + ": price_usd must be a positive amount like 15.99, not '" + price + "'");
            }
        }
        Size size = null;
        if ((sizeText == null) != (unit == null)) {
            errors.add(where + ": size and unit must be filled in together");
        } else if (sizeText != null) {
            try {
                size = Size.fromLabel(new BigDecimal(sizeText), unit);
            } catch (NumberFormatException e) {
                errors.add(where + ": size must be a number, not '" + sizeText + "'");
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
            }
        }
        checkUrl(where, "url", url);
        LocalDate checkedOn = null;
        if (checked != null) {
            try {
                checkedOn = LocalDate.parse(checked);
                if (checkedOn.isAfter(today)) {
                    errors.add(where + ": checked_on is in the future: " + checked);
                }
            } catch (DateTimeParseException e) {
                errors.add(where + ": checked_on must be a date like 2026-09-23, not '" + checked + "'");
            }
        }
        return errors.size() == before
                ? new CatalogOffer(productId, retailer, priceCents, size, url, checkedOn)
                : null;
    }

    private Integer spf(String where, String category, String text) {
        boolean sunscreen = "sunscreen".equals(category);
        if (text == null) {
            if (sunscreen) {
                errors.add(where + ": a sunscreen needs its SPF in the spf column, e.g. 50");
            }
            return null;
        }
        if (!sunscreen) {
            errors.add(where + ": spf is only for sunscreens");
            return null;
        }
        if (!text.matches("\\d{1,3}")) {
            errors.add(where + ": spf must be a whole number like 50, not '" + text + "'");
            return null;
        }
        int spf = Integer.parseInt(text);
        if (spf < MIN_SPF || spf > 100) {
            errors.add(where + ": spf must be between " + MIN_SPF + " and 100 (NextToSkin only uses SPF 30+), not " + spf);
            return null;
        }
        return spf;
    }

    /** Parses "Zinc Oxide 9%; Titanium Dioxide 3%". */
    private List<Active> actives(String where, String text) {
        if (text == null) {
            return List.of();
        }
        List<Active> actives = new ArrayList<>();
        for (String part : text.split(";")) {
            var m = ACTIVE.matcher(part.strip());
            BigDecimal percent = m.matches() ? new BigDecimal(m.group(2)) : null;
            if (percent == null || percent.signum() <= 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                errors.add(where + ": actives must look like 'Benzoyl Peroxide 4%; Zinc Oxide 9%', not '"
                        + part.strip() + "'");
            } else if (actives.stream().anyMatch(a -> a.name().equalsIgnoreCase(m.group(1).strip()))) {
                errors.add(where + ": active '" + m.group(1).strip() + "' is listed twice");
            } else {
                actives.add(new Active(m.group(1).strip(), percent));
            }
        }
        return actives;
    }

    private String required(String where, Map<String, String> row, String column) {
        String value = optional(row, column);
        if (value == null) {
            errors.add(where + ": " + column + " is empty");
        }
        return value;
    }

    private static String optional(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void checkUrl(String where, String column, String value) {
        if (value == null) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
                errors.add(where + ": " + column + " must be a web link starting with https://");
            }
        } catch (IllegalArgumentException e) {
            errors.add(where + ": " + column + " is not a valid link");
        }
    }
}
