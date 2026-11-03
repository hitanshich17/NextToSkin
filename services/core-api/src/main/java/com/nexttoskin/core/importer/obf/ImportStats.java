package com.nexttoskin.core.importer.obf;

import java.util.Map;

/** Summary of one import run. */
public record ImportStats(int linesRead, Map<ObfImporter.Outcome, Integer> outcomes, int ingredientsCreated) {

    public ImportStats {
        outcomes = Map.copyOf(outcomes);
    }

    public int count(ObfImporter.Outcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }
}
