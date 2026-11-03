package com.nexttoskin.core.routine;

import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Estimates what a routine costs per month, from typical amounts per use (docs/routine-rules.md, section 5).
 * Grams are treated as millilitres: skin-care products are close to the density of water.
 */
final class MonthlyCost {

    static final int DAYS = 30;

    /** Typical millilitres per use, by category. */
    static final Map<String, BigDecimal> ML_PER_USE = Map.of(
            "cleanser", new BigDecimal("1.0"),
            "treatment", new BigDecimal("0.3"),
            "moisturizer", new BigDecimal("0.5"),
            "sunscreen", new BigDecimal("1.2")); // the "two-finger" amount for the face

    private MonthlyCost() {
    }

    /**
     * @return the estimated monthly cost in cents, or null if any picked product has no known size
     */
    static Integer estimate(Map<Step, Routine.Pick> picks) {
        Map<RoutineProduct, Integer> usesPerDay = new LinkedHashMap<>();
        picks.values().forEach(pick -> usesPerDay.merge(pick.product(), 1, Integer::sum)); // AM + PM = 2
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<RoutineProduct, Integer> entry : usesPerDay.entrySet()) {
            RoutineProduct.Offer offer = entry.getKey().offer();
            if (offer.sizeAmount() == null) {
                return null;
            }
            BigDecimal mlPerMonth = ML_PER_USE.get(entry.getKey().category())
                    .multiply(BigDecimal.valueOf((long) entry.getValue() * DAYS));
            total = total.add(BigDecimal.valueOf(offer.priceCents())
                    .multiply(mlPerMonth)
                    .divide(offer.sizeAmount(), 2, RoundingMode.HALF_UP));
        }
        return total.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
