package com.nexttoskin.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SizeTest {

    @ParameterizedTest
    @CsvSource({
            "16,    fl oz, 473.18, ml",
            "1.7,   oz,    48.19,  g",
            "50,    ml,    50.00,  ml",
            "30,    G,     30.00,  g",
            "1,     FL OZ, 29.57,  ml",
    })
    void convertsLabelSizesToMetric(String amount, String unit, String expectedAmount, String expectedUnit) {
        assertThat(Size.fromLabel(new BigDecimal(amount), unit))
                .isEqualTo(new Size(new BigDecimal(expectedAmount), expectedUnit));
    }

    @ParameterizedTest
    @CsvSource({"16, cups", "0, ml", "-5, g"})
    void rejectsUnknownUnitsAndNonPositiveSizes(String amount, String unit) {
        assertThatThrownBy(() -> Size.fromLabel(new BigDecimal(amount), unit))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
