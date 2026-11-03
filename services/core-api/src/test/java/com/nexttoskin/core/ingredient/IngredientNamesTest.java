package com.nexttoskin.core.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IngredientNamesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "AQUA                          | aqua",
            "' Alcohol Denat. '            | alcohol denat",
            "Caprylic / Capric Triglyceride | caprylic/capric triglyceride",
            "Huile Minérale                | huile minerale",
            "Aloe Barbadensis Leaf Juice*  | aloe barbadensis leaf juice",
            "Toluene-2,5-Diamine           | toluene-2,5-diamine",
            "'Sodium   Laureth  Sulfate'   | sodium laureth sulfate",
            "CI 77891.                     | ci 77891",
    })
    void normalizesLabelSpellingsToOneKey(String label, String expected) {
        assertThat(IngredientNames.normalize(label)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {"NULL | ''", "'  ' | ''", "'***' | ''"})
    void emptyInputGivesEmptyKey(String label, String expected) {
        assertThat(IngredientNames.normalize(label)).isEqualTo(expected);
    }
}
