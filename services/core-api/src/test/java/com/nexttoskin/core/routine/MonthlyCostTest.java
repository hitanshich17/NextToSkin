package com.nexttoskin.core.routine;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MonthlyCostTest {

    private static RoutineProduct p(String id, String category, int priceCents, String sizeMl) {
        return new RoutineProduct(id, "Brand", id, category, null, false, Map.of(),
                new RoutineProduct.Offer("brand", priceCents, sizeMl == null ? null : new BigDecimal(sizeMl),
                        sizeMl == null ? null : "ml", "https://example.com/" + id));
    }

    private static Routine.Pick pick(RoutineProduct product) {
        return new Routine.Pick(product, List.of());
    }

    @Test
    void productUsedAmAndPmIsUsedTwiceAsFast() {
        RoutineProduct cleanser = p("cleanser", "cleanser", 1200, "240"); // 1 ml x 2 x 30 = 60 ml = a quarter
        RoutineProduct sunscreen = p("sunscreen", "sunscreen", 1800, "50"); // 1.2 ml x 30 = 36 ml

        Integer monthly = MonthlyCost.estimate(Map.of(Step.AM_CLEANSER, pick(cleanser), Step.PM_CLEANSER,
                pick(cleanser), Step.AM_SUNSCREEN, pick(sunscreen)));

        assertThat(monthly).isEqualTo(300 + 1296);
    }

    @Test
    void unknownSizeMeansNoEstimate() {
        assertThat(MonthlyCost.estimate(Map.of(Step.PM_TREATMENT, pick(p("serum", "treatment", 2000, null)))))
                .isNull();
    }
}
