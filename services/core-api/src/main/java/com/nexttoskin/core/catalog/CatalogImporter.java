package com.nexttoskin.core.catalog;

import com.nexttoskin.core.catalog.CatalogCsvReader.Active;
import com.nexttoskin.core.catalog.CatalogCsvReader.Catalog;
import com.nexttoskin.core.catalog.CatalogCsvReader.CatalogOffer;
import com.nexttoskin.core.catalog.CatalogCsvReader.CatalogProduct;
import com.nexttoskin.core.catalog.ProductWriter.ProductData;
import com.nexttoskin.core.ingredient.IngredientListParser;
import com.nexttoskin.core.ingredient.IngredientResolver;
import com.nexttoskin.core.ingredient.IngredientTagger;
import com.nexttoskin.core.similarity.ProductVectors;
import com.nexttoskin.core.ingredient.LabelIngredient;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports the hand-curated catalog: products with their ingredient lists and OTC actives, and their offers.
 * The CSV files are the source of truth, so each product's offers are replaced on every import.
 */
@Service
public class CatalogImporter {

    static final String SOURCE = "curated";

    public record Result(int products, int offers, int ingredientsCreated) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProductWriter productWriter;
    private final IngredientTagger tagger;
    private final ProductVectors vectors;

    public CatalogImporter(JdbcTemplate jdbc, TransactionTemplate tx, ProductWriter productWriter,
                           IngredientTagger tagger, ProductVectors vectors) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.productWriter = productWriter;
        this.tagger = tagger;
        this.vectors = vectors;
    }

    /** Imports catalog/products.csv and catalog/offers.csv from the given directory. */
    public Result importDirectory(Path dir) throws IOException {
        try (Reader products = Files.newBufferedReader(dir.resolve("products.csv"), StandardCharsets.UTF_8);
             Reader offers = Files.newBufferedReader(dir.resolve("offers.csv"), StandardCharsets.UTF_8)) {
            return importCatalog(CatalogCsvReader.read(products, offers, LocalDate.now(ZoneOffset.UTC)));
        }
    }

    public Result importCatalog(Catalog catalog) {
        // Resolve ingredients before the transaction (see ObfImporter for why).
        IngredientResolver resolver = new IngredientResolver(jdbc);
        Map<String, List<Long>> ingredientIds = new HashMap<>();
        Map<Active, Long> activeIds = new HashMap<>();
        for (CatalogProduct p : catalog.products()) {
            ingredientIds.put(p.id(), IngredientListParser.parse(p.ingredients()).stream()
                    .flatMap(i -> resolver.resolve(i).stream()).distinct().toList());
            for (Active a : p.actives()) {
                activeIds.put(a, resolver.resolve(LabelIngredient.of(a.name())).getFirst());
            }
        }

        tx.executeWithoutResult(status -> {
            Map<String, Long> productIds = new HashMap<>();
            for (CatalogProduct p : catalog.products()) {
                long id = productWriter.upsert(new ProductData(SOURCE, p.id(), p.brand(), p.name(), p.category(),
                        p.ingredients(), p.imageUrl(), p.sourceUrl(), p.imported(), p.spf()));
                productWriter.replaceIngredients(id, ingredientIds.get(p.id()));
                jdbc.update("DELETE FROM product_actives WHERE product_id = ?", id);
                for (Active a : p.actives()) {
                    jdbc.update("INSERT INTO product_actives (product_id, ingredient_id, percent) VALUES (?, ?, ?)",
                            id, activeIds.get(a), a.percent());
                }
                jdbc.update("DELETE FROM product_offers WHERE product_id = ?", id);
                productIds.put(p.id(), id);
            }
            for (CatalogOffer o : catalog.offers()) {
                jdbc.update("""
                        INSERT INTO product_offers
                            (product_id, retailer, price_cents, size_amount, size_unit, url, checked_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, productIds.get(o.productId()), o.retailer(), o.priceCents(),
                        o.size() == null ? null : o.size().amount(), o.size() == null ? null : o.size().unit(),
                        o.url(), o.checkedOn().atStartOfDay().atOffset(ZoneOffset.UTC));
            }
        });
        tagger.retagAll();
        vectors.rebuildAll();
        return new Result(catalog.products().size(), catalog.offers().size(), resolver.ingredientsCreated());
    }
}
