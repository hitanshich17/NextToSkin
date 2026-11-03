package com.nexttoskin.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.nexttoskin.core.catalog.CatalogCsvReader.Active;
import com.nexttoskin.core.catalog.CatalogCsvReader.Catalog;
import com.nexttoskin.core.catalog.CatalogCsvReader.CatalogOffer;
import com.nexttoskin.core.catalog.CatalogCsvReader.CatalogProduct;
import com.nexttoskin.core.catalog.CatalogCsvReader.InvalidCatalogException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogCsvReaderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);
    private static final String PRODUCTS_HEADER = "id,brand,name,category,actives,ingredients,image_url,source_url,import,spf\n";
    private static final String OFFERS_HEADER = "product_id,retailer,price_usd,size,unit,url,checked_on\n";
    private static final String CLEANSER =
            "gentle-cleanser,Brand A,Gentle Cleanser,cleanser,,\"Aqua, Glycerin, Cetearyl Alcohol\",,https://brand-a.example/cleanser\n";

    static Catalog read(String products, String offers) throws IOException {
        return CatalogCsvReader.read(new StringReader(PRODUCTS_HEADER + products),
                new StringReader(OFFERS_HEADER + offers), TODAY);
    }

    private static List<String> errors(String products, String offers) {
        return catchThrowableOfType(InvalidCatalogException.class, () -> read(products, offers)).errors();
    }

    @Test
    void readsValidCatalog() throws IOException {
        Catalog catalog = read(CLEANSER, """
                gentle-cleanser,target,15.99,16,fl oz,https://target.example/p/1,2026-09-20
                gentle-cleanser,brand,18,,,https://brand-a.example/cleanser,2026-09-23
                """);

        assertThat(catalog.products()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo("gentle-cleanser");
            assertThat(p.ingredients()).isEqualTo("Aqua, Glycerin, Cetearyl Alcohol");
            assertThat(p.imageUrl()).isNull();
        });
        assertThat(catalog.offers()).containsExactly(
                new CatalogOffer("gentle-cleanser", "target", 1599, new Size(new BigDecimal("473.18"), "ml"),
                        "https://target.example/p/1", LocalDate.of(2026, 9, 20)),
                new CatalogOffer("gentle-cleanser", "brand", 1800, null,
                        "https://brand-a.example/cleanser", TODAY));
    }

    @Test
    void reportsEveryProductProblemWithItsLine() {
        assertThat(errors("""
                Bad Id,Brand,Name,cleanser,,"Aqua, Glycerin",,https://x.example/a
                ok-id,,Name,toner,,"Aqua",,not-a-link
                ok-id-2,Brand,Name,serum,,,,https://x.example/b
                """, "")).containsExactlyInAnyOrder(
                "products.csv:2: id must be lowercase words joined by '-', e.g. cerave-hydrating-cleanser",
                "products.csv:3: brand is empty",
                "products.csv:3: category must be one of cleanser, moisturizer, sunscreen, treatment",
                "products.csv:3: source_url must be a web link starting with https://",
                "products.csv:4: ingredients is empty",
                "products.csv:4: category must be one of cleanser, moisturizer, sunscreen, treatment");
    }

    @Test
    void reportsEveryOfferProblemWithItsLine() {
        assertThat(errors(CLEANSER, """
                unknown-product,target,15.99,16,fl oz,https://t.example/1,2026-09-20
                gentle-cleanser,Target,$15.99,16,,https://t.example/1,2026-09-20
                gentle-cleanser,ulta,12.5,2,cups,https://u.example/1,2027-01-01
                """)).containsExactlyInAnyOrder(
                "offers.csv:2: product_id 'unknown-product' is not in products.csv",
                "offers.csv:3: retailer must be lowercase, e.g. target, ulta, brand",
                "offers.csv:3: price_usd must be a positive amount like 15.99, not '$15.99'",
                "offers.csv:3: size and unit must be filled in together",
                "offers.csv:4: unit must be one of ml, g, fl oz, oz: 'cups'",
                "offers.csv:4: checked_on is in the future: 2027-01-01");
    }

    @Test
    void readsActivesWithPercentages() throws IOException {
        Catalog catalog = read("""
                acne-wash,Brand B,Acne Wash,cleanser,Benzoyl Peroxide 4%,"Water, Glycerin",,https://b.example/wash
                mineral-spf,Brand C,Mineral SPF 30,sunscreen,Zinc Oxide 9%; Titanium Dioxide 3.5 %,"Water, Glycerin",,https://c.example/spf,,30
                """, "");

        assertThat(catalog.products()).extracting(CatalogProduct::actives).containsExactly(
                List.of(new Active("Benzoyl Peroxide", new BigDecimal("4"))),
                List.of(new Active("Zinc Oxide", new BigDecimal("9")), new Active("Titanium Dioxide", new BigDecimal("3.5"))));
    }

    @Test
    void readsImportFlag() throws IOException {
        Catalog catalog = read("""
                korean-spf,Brand K,Sun Cream,sunscreen,,"Water, Glycerin",,https://k.example/sun,yes,50
                """, "");

        assertThat(catalog.products()).singleElement().extracting(CatalogProduct::imported).isEqualTo(true);
    }

    @Test
    void usSunscreenNeedsActivesAndImportMustBeYes() {
        assertThat(errors("""
                us-spf,Brand U,Sun Lotion,sunscreen,,"Water, Glycerin",,https://u.example/sun,,30
                other-spf,Brand U,Sun Gel,sunscreen,Zinc Oxide 9%,"Water, Glycerin",,https://u.example/gel,true,30
                """, "")).containsExactly(
                "products.csv:2: a US sunscreen must list its UV filters in actives, e.g. 'Zinc Oxide 9%' (or set import to 'yes')",
                "products.csv:3: import must be 'yes' or empty, not 'true'");
    }

    @Test
    void readsSpfAndRequiresThirtyOrMoreForSunscreens() throws IOException {
        Catalog catalog = read("""
                spf-50,Brand S,Sun 50,sunscreen,Zinc Oxide 20%,"Water",,https://s.example/50,,50
                """, "");
        assertThat(catalog.products()).singleElement().extracting(CatalogProduct::spf).isEqualTo(50);

        assertThat(errors("""
                no-spf,Brand S,Sun,sunscreen,Zinc Oxide 20%,"Water",,https://s.example/a,,
                low-spf,Brand S,Sun 15,sunscreen,Zinc Oxide 20%,"Water",,https://s.example/b,,15
                text-spf,Brand S,Sun,sunscreen,Zinc Oxide 20%,"Water",,https://s.example/c,,50+
                cream-spf,Brand S,Cream,moisturizer,,"Water",,https://s.example/d,,30
                """, "")).containsExactly(
                "products.csv:2: a sunscreen needs its SPF in the spf column, e.g. 50",
                "products.csv:3: spf must be between 30 and 100 (NextToSkin only uses SPF 30+), not 15",
                "products.csv:4: spf must be a whole number like 50, not '50+'",
                "products.csv:5: spf is only for sunscreens");
    }

    @Test
    void rejectsActivesWithoutPercentage() {
        assertThat(errors("""
                acne-wash,Brand B,Acne Wash,cleanser,Benzoyl Peroxide; Salicylic Acid 120%; Zinc Oxide 5%; zinc oxide 6%,"Water, Glycerin",,https://b.example/wash
                """, "")).containsExactly(
                "products.csv:2: actives must look like 'Benzoyl Peroxide 4%; Zinc Oxide 9%', not 'Benzoyl Peroxide'",
                "products.csv:2: actives must look like 'Benzoyl Peroxide 4%; Zinc Oxide 9%', not 'Salicylic Acid 120%'",
                "products.csv:2: active 'zinc oxide' is listed twice");
    }

    @Test
    void toleratesSpacesAroundValuesAndBlankLines() throws IOException {
        Catalog catalog = read("""
                spaced, Brand A , Spaced Cleanser, cleanser,, "Aqua, Glycerin" ,, https://a.example/p

                """, "spaced, target, 9.99, 8, fl oz, https://t.example/p, 2026-09-20\n\n");

        assertThat(catalog.products()).singleElement().satisfies(p -> {
            assertThat(p.brand()).isEqualTo("Brand A");
            assertThat(p.ingredients()).isEqualTo("Aqua, Glycerin");
        });
        assertThat(catalog.offers()).singleElement().extracting(CatalogOffer::priceCents).isEqualTo(999);
    }

    @Test
    void lineNumbersHoldWithoutTrailingNewline() {
        assertThat(errors(CLEANSER, "gentle-cleanser,target,abc,,,https://t.example/1,2026-09-20"))
                .containsExactly("offers.csv:2: price_usd must be a positive amount like 15.99, not 'abc'");
    }

    @Test
    void rejectsDuplicates() {
        assertThat(errors(CLEANSER + CLEANSER, """
                gentle-cleanser,target,15.99,16,fl oz,https://t.example/1,2026-09-20
                gentle-cleanser,target,14.99,16,fl oz,https://t.example/1,2026-09-21
                """)).containsExactly(
                "products.csv:3: duplicate id 'gentle-cleanser'",
                "offers.csv:3: duplicate offer for 'gentle-cleanser' at target in this size");
    }

    @Test
    void rejectsMissingColumns() {
        List<String> errors = catchThrowableOfType(InvalidCatalogException.class, () -> CatalogCsvReader.read(
                new StringReader("id,brand,name\nx,y,z\n"), new StringReader(OFFERS_HEADER), TODAY)).errors();

        assertThat(errors).containsExactly(
                "products.csv: header must contain id,brand,name,category,actives,ingredients,image_url,source_url,import,spf");
    }
}
