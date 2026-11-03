package com.nexttoskin.core.importer.obf;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the import at startup when {@code nexttoskin.import.obf-file} is set, e.g. via scripts/import-obf.sh.
 * The bean does not exist otherwise, so the normal API server never imports anything.
 */
@Component
@ConditionalOnProperty("nexttoskin.import.obf-file")
class ObfImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ObfImportRunner.class);

    private final ObfImporter importer;
    private final Path file;

    ObfImportRunner(ObfImporter importer, @Value("${nexttoskin.import.obf-file}") Path file) {
        this.importer = importer;
        this.file = file;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Importing Open Beauty Facts products from {}", file);
        ImportStats stats = importer.importFile(file);
        log.info("Import finished: {} lines, outcomes {}, {} new ingredients",
                stats.linesRead(), stats.outcomes(), stats.ingredientsCreated());
    }
}
