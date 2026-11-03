package com.nexttoskin.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Validates the real catalog/ files in the repo, so a bad edit fails CI instead of the import. */
class CatalogFilesTest {

    private static final Path CATALOG = Path.of("../../catalog");

    @Test
    void catalogFilesAreValid() throws IOException {
        try (Reader products = Files.newBufferedReader(CATALOG.resolve("products.csv"));
             Reader offers = Files.newBufferedReader(CATALOG.resolve("offers.csv"))) {
            // Throws InvalidCatalogException listing every problem
            var catalog = CatalogCsvReader.read(products, offers, LocalDate.now(ZoneOffset.UTC));
            assertThat(catalog.products()).isNotEmpty();
        }
    }
}
