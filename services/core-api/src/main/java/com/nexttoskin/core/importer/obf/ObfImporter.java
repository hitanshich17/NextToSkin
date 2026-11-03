package com.nexttoskin.core.importer.obf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexttoskin.core.catalog.ProductWriter;
import com.nexttoskin.core.catalog.ProductWriter.ProductData;
import com.nexttoskin.core.ingredient.IngredientListParser;
import com.nexttoskin.core.ingredient.IngredientResolver;
import com.nexttoskin.core.ingredient.IngredientTagger;
import com.nexttoskin.core.ingredient.LabelIngredient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports products from the Open Beauty Facts JSONL dump (one JSON product per line, optionally gzipped).
 *
 * <p>The file is streamed line by line, so the ~1 GB uncompressed dump never sits in memory.
 * Re-running is safe: products are upserted by barcode and their ingredient lists replaced.
 */
@Service
public class ObfImporter {

    public enum Outcome { IMPORTED, NO_BARCODE, NO_NAME, NO_INGREDIENTS, UNPARSEABLE, FAILED }

    static final String SOURCE = "open_beauty_facts";
    private static final String PRODUCT_PAGE = "https://world.openbeautyfacts.org/product/";

    private static final Logger log = LoggerFactory.getLogger(ObfImporter.class);
    private static final int MAX_LOGGED_FAILURES = 20;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ProductWriter productWriter;
    private final IngredientTagger tagger;

    public ObfImporter(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, ProductWriter productWriter,
                       IngredientTagger tagger) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.productWriter = productWriter;
        this.tagger = tagger;
    }

    public ImportStats importFile(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = file.toString().endsWith(".gz") ? new GZIPInputStream(raw, 1 << 16) : raw) {
            return importStream(in);
        }
    }

    public ImportStats importStream(InputStream in) throws IOException {
        IngredientResolver resolver = new IngredientResolver(jdbc);
        Map<Outcome, Integer> outcomes = new EnumMap<>(Outcome.class);
        int lines = 0;

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            lines++;
            Outcome outcome;
            try {
                outcome = importLine(line, resolver);
            } catch (IOException | RuntimeException e) {
                outcome = Outcome.FAILED;
                if (outcomes.getOrDefault(Outcome.FAILED, 0) < MAX_LOGGED_FAILURES) {
                    log.warn("Line {} failed: {}", lines, e.getMessage());
                }
            }
            outcomes.merge(outcome, 1, Integer::sum);
            if (lines % 10_000 == 0) {
                log.info("{} lines read, {} products imported", lines, outcomes.getOrDefault(Outcome.IMPORTED, 0));
            }
        }
        tagger.retagAll();
        return new ImportStats(lines, outcomes, resolver.ingredientsCreated());
    }

    private Outcome importLine(String line, IngredientResolver resolver) throws IOException {
        ObfProduct p = mapper.readValue(line, ObfProduct.class);
        String code = firstNonBlank(p.code());
        String name = firstNonBlank(p.productNameEn(), p.productName());
        String ingredientsText = firstNonBlank(p.ingredientsTextEn(), p.ingredientsText());
        if (code == null) {
            return Outcome.NO_BARCODE;
        }
        if (ingredientsText == null) {
            return Outcome.NO_INGREDIENTS;
        }
        if (name == null) {
            return Outcome.NO_NAME;
        }
        List<LabelIngredient> parsed = IngredientListParser.parse(ingredientsText);
        if (parsed.isEmpty()) {
            return Outcome.UNPARSEABLE;
        }

        // Resolve outside the product transaction: new ingredients are kept even if saving this product
        // fails, which keeps the resolver's in-memory cache in step with the database.
        List<Long> ingredientIds = parsed.stream()
                .flatMap(i -> resolver.resolve(i).stream()).distinct().toList();

        ProductData product = new ProductData(SOURCE, code, firstBrand(p.brands()), name,
                ObfCategories.categorize(p.categoriesTags()), ingredientsText, null, PRODUCT_PAGE + code, false, null);
        tx.executeWithoutResult(status ->
                productWriter.replaceIngredients(productWriter.upsert(product), ingredientIds));
        return Outcome.IMPORTED;
    }

    /** OBF can list several brands ("Johnson & Johnson, Neutrogena"); we keep the first. */
    private static String firstBrand(String brands) {
        return brands == null ? null : firstNonBlank(brands.split(",")[0]);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }
}
