package com.nexttoskin.core.importer.obf;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** The fields we use from one line of the Open Beauty Facts JSONL dump (each line has ~150 fields). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ObfProduct(
        String code,
        String productName,
        String productNameEn,
        String brands,
        List<String> categoriesTags,
        String ingredientsText,
        String ingredientsTextEn) {
}
