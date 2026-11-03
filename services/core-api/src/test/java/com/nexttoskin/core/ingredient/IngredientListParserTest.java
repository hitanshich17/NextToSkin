package com.nexttoskin.core.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Examples are taken from real Open Beauty Facts labels. */
class IngredientListParserTest {

    private static List<String> names(String text) {
        return IngredientListParser.parse(text).stream().map(LabelIngredient::name).toList();
    }

    @Test
    void splitsCommaSeparatedListInOrder() {
        assertThat(names("Aqua, propylene glycol, sorbitol, hydroxyethylcellulose, benzoic acid."))
                .containsExactly("Aqua", "propylene glycol", "sorbitol", "hydroxyethylcellulose", "benzoic acid");
    }

    @Test
    void keepsCommaInsideChemicalName() {
        assertThat(names("Glycerin, 1,2-Hexanediol, Caprylyl Glycol"))
                .containsExactly("Glycerin", "1,2-Hexanediol", "Caprylyl Glycol");
    }

    @Test
    void splitsOnPeriodsAndSpacedDashes() {
        assertThat(names("AQUA/WATER/EAU. GLYCERIN. ALCOHOL DENAT. CYCLOPENTASILOXANE."))
                .containsExactly("AQUA/WATER/EAU", "GLYCERIN", "ALCOHOL DENAT", "CYCLOPENTASILOXANE");
        assertThat(names("AQUA - COCAMIDOPROPYL BETAINE - PEG-7 GLYCERYL COCOATE - C12-15 ALKYL BENZOATE"))
                .containsExactly("AQUA", "COCAMIDOPROPYL BETAINE", "PEG-7 GLYCERYL COCOATE", "C12-15 ALKYL BENZOATE");
    }

    @Test
    void dropsHeadingBeforeColon() {
        assertThat(names("Ingrédients : Aqua, Parfum")).containsExactly("Aqua", "Parfum");
        assertThat(names("/ COCTaB: AQUA, GLYCERIN")).containsExactly("AQUA", "GLYCERIN");
    }

    @Test
    void trailingParenthesesAreSynonyms() {
        assertThat(IngredientListParser.parse("Water (Aqua), PARFUM (FRAGRANCE)")).containsExactly(
                LabelIngredient.of("Water", "Aqua"),
                LabelIngredient.of("PARFUM", "FRAGRANCE"));
    }

    @Test
    void parenthesesInsideNameAreDropped() {
        assertThat(IngredientListParser.parse(
                "Butyrospermum Parkii (Shea) Butter (Butyrospermum Parkii Butter), SESAMUM INDICUM (SESAME) SEED OIL"))
                .containsExactly(
                        LabelIngredient.of("Butyrospermum Parkii Butter"),
                        LabelIngredient.of("SESAMUM INDICUM SEED OIL"));
    }

    @Test
    void parenthesesWithCommasListSubIngredients() {
        assertThat(names("Parfum (Limonene, Linalool), Glycerin"))
                .containsExactly("Parfum", "Limonene", "Linalool", "Glycerin");
    }

    @Test
    void descriptorsAreNotSynonyms() {
        assertThat(IngredientListParser.parse("Glycerin (Vegetable), Titanium Dioxide (nano)"))
                .containsExactly(LabelIngredient.of("Glycerin"), LabelIngredient.of("Titanium Dioxide"));
        assertThat(names("[nano] ZINC OXIDE, ALCOHOL")).containsExactly("ZINC OXIDE", "ALCOHOL");
    }

    @Test
    void dropsMayContainSection() {
        assertThat(names("Talc, Mica, Dimethicone [+/- CI 77491, CI 77492]"))
                .containsExactly("Talc", "Mica", "Dimethicone");
        assertThat(names("Talc, Mica, May contain: CI 77491, CI 77492")).containsExactly("Talc", "Mica");
    }

    @Test
    void dropsFootnotesMarkersAndPercentages() {
        assertThat(names("Aloe Barbadensis Leaf Juice*, Niacinamide 5%, Tocopherol. *Ingredients from organic farming"))
                .containsExactly("Aloe Barbadensis Leaf Juice", "Niacinamide", "Tocopherol");
        assertThat(names("Aqua, Glycerin, 99% of total ingredients are of natural origin"))
                .containsExactly("Aqua", "Glycerin");
    }

    @Test
    void dropsBatchCodes() {
        assertThat(names("CITRAL, GERANIOL, (C0547C)")).containsExactly("CITRAL", "GERANIOL");
        assertThat(names("Aqua, C0547C, CI 77891")).containsExactly("Aqua", "CI 77891");
    }

    @Test
    void dropsNonLatinTextAndHtmlEntities() {
        assertThat(names("Aqua, Наурызба батыр көш, Glycerin")).containsExactly("Aqua", "Glycerin");
        assertThat(names("Coffea Arabica Seed Oil&quot;, Tocopherol")).containsExactly("Coffea Arabica Seed Oil", "Tocopherol");
    }

    @Test
    void emptyTextGivesNoIngredients() {
        assertThat(IngredientListParser.parse(null)).isEmpty();
        assertThat(IngredientListParser.parse("   ")).isEmpty();
    }
}
