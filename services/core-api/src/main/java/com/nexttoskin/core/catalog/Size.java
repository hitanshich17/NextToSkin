package com.nexttoskin.core.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/** A package size in metric units: liquids in ml, solids in g. US labels are converted on the way in. */
public record Size(BigDecimal amount, String unit) {

    private static final BigDecimal ML_PER_FL_OZ = new BigDecimal("29.5735");
    private static final BigDecimal G_PER_OZ = new BigDecimal("28.3495");

    /** Label unit -> (metric unit, factor). */
    private static final Map<String, Map.Entry<String, BigDecimal>> UNITS = Map.of(
            "ml", Map.entry("ml", BigDecimal.ONE),
            "g", Map.entry("g", BigDecimal.ONE),
            "fl oz", Map.entry("ml", ML_PER_FL_OZ),
            "oz", Map.entry("g", G_PER_OZ));

    /**
     * Converts a label size such as (16, "fl oz") to metric, rounded to 2 decimals.
     *
     * @throws IllegalArgumentException if the unit is not ml, g, fl oz or oz, or the amount is not positive
     */
    public static Size fromLabel(BigDecimal amount, String labelUnit) {
        var conversion = UNITS.get(labelUnit.strip().toLowerCase(Locale.ROOT));
        if (conversion == null) {
            throw new IllegalArgumentException("unit must be one of ml, g, fl oz, oz: '" + labelUnit + "'");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("size must be positive: " + amount);
        }
        return new Size(amount.multiply(conversion.getValue()).setScale(2, RoundingMode.HALF_UP), conversion.getKey());
    }
}
