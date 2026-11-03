package com.nexttoskin.core.catalog;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs the curated catalog import at startup when {@code nexttoskin.import.catalog-dir} is set (scripts/import-catalog.sh). */
@Component
@ConditionalOnProperty("nexttoskin.import.catalog-dir")
class CatalogImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportRunner.class);

    private final CatalogImporter importer;
    private final Path dir;

    CatalogImportRunner(CatalogImporter importer, @Value("${nexttoskin.import.catalog-dir}") Path dir) {
        this.importer = importer;
        this.dir = dir;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        CatalogImporter.Result result = importer.importDirectory(dir);
        log.info("Catalog imported: {} products, {} offers, {} new ingredients",
                result.products(), result.offers(), result.ingredientsCreated());
    }
}
