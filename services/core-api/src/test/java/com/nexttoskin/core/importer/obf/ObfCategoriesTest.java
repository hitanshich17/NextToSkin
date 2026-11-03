package com.nexttoskin.core.importer.obf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObfCategoriesTest {

    @Test
    void mapsTagsToCategory() {
        assertThat(ObfCategories.categorize(List.of("en:face", "en:facial-creams"))).isEqualTo("moisturizer");
        assertThat(ObfCategories.categorize(List.of("en:hair", "en:shampoos", "en:anti-dandruff-shampoos")))
                .isEqualTo("shampoo");
        assertThat(ObfCategories.categorize(List.of("en:lip-cosmetics", "en:lip-balms"))).isEqualTo("lip-care");
    }

    @Test
    void routineCategoriesAreFaceProductsOnly() {
        assertThat(ObfCategories.categorize(List.of("en:face", "en:serums"))).isEqualTo("treatment");
        assertThat(ObfCategories.categorize(List.of("en:body", "en:body-creams"))).isEqualTo("body-care");
        assertThat(ObfCategories.categorize(List.of("en:hand-creams"))).isEqualTo("body-care");
        assertThat(ObfCategories.categorize(List.of("en:face-makeup", "en:bb-creams"))).isEqualTo("makeup");
        assertThat(ObfCategories.categorize(List.of("en:night-creams"))).isEqualTo("moisturizer");
    }

    @Test
    void specificCategoryBeatsGenericOne() {
        // A day cream with SPF is chosen for its sun protection
        assertThat(ObfCategories.categorize(List.of("en:facial-creams", "en:day-creams", "en:in-sun-protections")))
                .isEqualTo("sunscreen");
    }

    @Test
    void unknownOrNonEnglishTagsGiveNull() {
        assertThat(ObfCategories.categorize(List.of("fr:cremes-douche", "en:hygiene"))).isNull();
        assertThat(ObfCategories.categorize(null)).isNull();
    }
}
