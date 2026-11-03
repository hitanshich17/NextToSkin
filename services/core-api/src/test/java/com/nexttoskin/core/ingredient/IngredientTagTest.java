package com.nexttoskin.core.ingredient;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Names are real ingredient names from the curated catalog. */
class IngredientTagTest {

    private static Set<IngredientTag> tags(String... names) {
        return IngredientTag.tagsFor(List.of(names));
    }

    @Test
    void fragranceAndItsAllergens() {
        assertThat(tags("PARFUM/FRAGRANCE")).containsExactly(FRAGRANCE);
        assertThat(tags("Fragrance/Parfum")).containsExactly(FRAGRANCE);
        assertThat(tags("Linalool")).containsExactly(FRAGRANCE_ALLERGEN);
        assertThat(tags("Alpha-Isomethyl Ionone")).containsExactly(FRAGRANCE_ALLERGEN);
        assertThat(tags("Benzyl Alcohol")).isEmpty(); // preservative in skincare
    }

    @Test
    void essentialOilsButNotCarrierOilsExtractsOrWaters() {
        assertThat(tags("Citrus Aurantium Dulcis Oil")).containsExactly(ESSENTIAL_OIL);
        assertThat(tags("Melaleuca Alternifolia Leaf Oil")).containsExactly(ESSENTIAL_OIL);
        assertThat(tags("Pelargonium Graveolens Flower Oil")).containsExactly(ESSENTIAL_OIL);
        assertThat(tags("LAVANDULA OIL/EXTRACT")).containsExactly(ESSENTIAL_OIL);
        assertThat(tags("Olea Europaea Fruit Oil")).isEmpty();
        assertThat(tags("Hippophae Rhamnoides Fruit Oil")).isEmpty();
        assertThat(tags("Melaleuca Alternifolia (Tea Tree) Leaf Water")).isEmpty();
        assertThat(tags("Rosmarinus Officinalis Leaf Extract")).isEmpty();
    }

    @Test
    void dryingAlcoholsButNotFattyAlcohols() {
        assertThat(tags("Alcohol Denat.")).containsExactly(DRYING_ALCOHOL);
        assertThat(tags("ethanol")).containsExactly(DRYING_ALCOHOL);
        assertThat(tags("Cetearyl Alcohol")).isEmpty();
        assertThat(tags("Stearyl Alcohol")).isEmpty();
    }

    @Test
    void nutsIncludeSheaButNotCoconutOrWitchHazel() {
        assertThat(tags("Prunus Amygdalus Dulcis Oil")).containsExactly(NUT);
        assertThat(tags("Ethyl Macadamiate")).containsExactly(NUT);
        assertThat(tags("Butyrospermum Parkii Butter")).containsExactly(NUT);
        assertThat(tags("Sclerocarya Birrea Seed Oil")).containsExactly(NUT);
        assertThat(tags("Cocos Nucifera Oil")).isEmpty();
        assertThat(tags("Coconut Alkanes")).isEmpty();
        assertThat(tags("Hamamelis Virginiana (Witch Hazel) Extract")).isEmpty();
    }

    @Test
    void soy() {
        assertThat(tags("Glycine Soja Oil")).containsExactly(SOY);
        assertThat(tags("Hydrolyzed Soy Protein")).containsExactly(SOY);
        assertThat(tags("Glycine Max (Soybean) Seed Extract")).containsExactly(SOY);
    }

    @Test
    void activesOnlyWhenTheyAreTheRealThing() {
        assertThat(tags("Retinol")).containsExactly(RETINOID);
        assertThat(tags("Adapalene")).containsExactly(RETINOID);
        assertThat(tags("Hydroxypinacolone Retinoate")).containsExactly(RETINOID);
        assertThat(tags("Glycolic Acid")).containsExactly(AHA);
        assertThat(tags("Citric Acid")).isEmpty();
        assertThat(tags("Lactic Acid/Glycolic Acid Copolymer")).isEmpty();
        assertThat(tags("Salicylic Acid")).containsExactly(BHA);
        assertThat(tags("Betaine Salicylate")).isEmpty(); // a milder relative, not counted as a BHA
        assertThat(tags("Butyloctyl Salicylate")).isEmpty(); // an emollient, not an exfoliant
        assertThat(tags("3-O-Ethyl Ascorbic Acid")).containsExactly(VITAMIN_C);
        assertThat(tags("Ascorbic Acid")).containsExactly(VITAMIN_C, L_ASCORBIC_ACID);
        assertThat(tags("Ascorbyl Glucoside")).containsExactly(VITAMIN_C);
        assertThat(tags("Tetrahexyldecyl Ascorbate")).containsExactly(VITAMIN_C);
        assertThat(tags("Benzoyl Peroxide")).containsExactly(BENZOYL_PEROXIDE);
        assertThat(tags("Niacinamide")).containsExactly(NIACINAMIDE);
        assertThat(tags("Cetyl Tranexamate Mesylate")).containsExactly(TRANEXAMIC_ACID);
        assertThat(tags("Azelaic Acid")).containsExactly(AZELAIC_ACID);
        assertThat(tags("Ceramide NP")).containsExactly(CERAMIDE);
    }

    @Test
    void rosaceaIrritants() {
        assertThat(tags("Menthol")).containsExactly(MENTHOL);
        assertThat(tags("MENTHOXYPROPANEDIOL")).containsExactly(MENTHOL);
        assertThat(tags("Camphor")).containsExactly(CAMPHOR);
        assertThat(tags("Sodium Lauryl Sulfate")).containsExactly(SODIUM_LAURYL_SULFATE);
        assertThat(tags("Sodium Laureth Sulfate")).isEmpty(); // milder, not on the AAD list
        assertThat(tags("Urea")).containsExactly(UREA);
        assertThat(tags("Hydroxyethyl Urea")).isEmpty(); // a different, non-irritating humectant
    }

    @Test
    void ironOxidesInTintedSunscreens() {
        assertThat(tags("Iron Oxides (CI 77492)")).contains(IRON_OXIDE);
        assertThat(tags("CI 77491")).containsExactly(IRON_OXIDE);
        assertThat(tags("iron oxides")).containsExactly(IRON_OXIDE);
        assertThat(tags("CI 77891")).isEmpty(); // titanium dioxide
    }

    @Test
    void hydrationAndSoothingIngredients() {
        assertThat(tags("Sodium Hyaluronate")).containsExactly(HYALURONIC_ACID);
        assertThat(tags("Hydrolyzed Hyaluronic Acid")).containsExactly(HYALURONIC_ACID);
        assertThat(tags("Palmitoyl Tripeptide-1")).containsExactly(PEPTIDE);
        assertThat(tags("Acetyl Hexapeptide-8")).containsExactly(PEPTIDE);
        assertThat(tags("sh-Oligopeptide-1")).containsExactly(PEPTIDE);
        assertThat(tags("Centella Asiatica Extract")).containsExactly(CENTELLA);
        assertThat(tags("Madecassoside")).containsExactly(CENTELLA);
    }

    @Test
    void chemicalUvFiltersButNotMinerals() {
        assertThat(tags("Avobenzone")).containsExactly(CHEMICAL_UV_FILTER);
        assertThat(tags("Bis-Ethylhexyloxyphenol Methoxyphenyl Triazine")).containsExactly(CHEMICAL_UV_FILTER);
        assertThat(tags("Ethylhexyl Methoxycinnamate")).containsExactly(CHEMICAL_UV_FILTER);
        assertThat(tags("Zinc Oxide")).isEmpty();
        assertThat(tags("Titanium Dioxide")).isEmpty();
        assertThat(tags("Butyloctyl Salicylate")).isEmpty(); // an emollient often used in sunscreens
    }

    @Test
    void anyNameOfTheIngredientCounts() {
        assertThat(tags("Aqua", "water")).isEmpty();
        assertThat(tags("Parfum", "fragrance", "perfume")).containsExactly(FRAGRANCE);
    }
}
