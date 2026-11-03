package com.nexttoskin.core.routine;

import com.nexttoskin.core.ingredient.IngredientTag;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * A catalog product as the routine rules see it.
 *
 * @param firstPositions for each tag, the earliest label position of an ingredient with that tag;
 *                       0 means the ingredient is a declared OTC active (adapalene, benzoyl peroxide, UV filters)
 * @param offer the cheapest offer, or null if the product has no offer (it can't be bought, so it's never picked)
 * @param ingredients canonical ingredient names on the label (used to find suspect ingredients)
 */
public record RoutineProduct(
        String id,
        String brand,
        String name,
        String category,
        Integer spf,
        boolean imported,
        Map<IngredientTag, Integer> firstPositions,
        Offer offer,
        Set<String> ingredients) {

    /** Where to buy, and for how much. {@code sizeAmount} is in ml or g, or null if unknown. */
    public record Offer(String retailer, int priceCents, BigDecimal sizeAmount, String sizeUnit, String url) {
    }

    /** Actives below this label position are minor (e.g. vitamin C used as an antioxidant preservative). */
    static final int MAIN_ACTIVE_MAX_POSITION = 10;

    /** Retinoids and peptides work at tiny concentrations, so their position says little about their role. */
    private static final Set<IngredientTag> POTENT_AT_LOW_LEVELS = Set.of(IngredientTag.RETINOID, IngredientTag.PEPTIDE);

    public RoutineProduct {
        firstPositions = Map.copyOf(firstPositions);
        ingredients = Set.copyOf(ingredients);
    }

    public RoutineProduct(String id, String brand, String name, String category, Integer spf, boolean imported,
                          Map<IngredientTag, Integer> firstPositions, Offer offer) {
        this(id, brand, name, category, spf, imported, firstPositions, offer, Set.of());
    }

    public Integer cheapestPriceCents() {
        return offer == null ? null : offer.priceCents();
    }

    public boolean has(IngredientTag tag) {
        return firstPositions.containsKey(tag);
    }

    public boolean hasAny(Set<IngredientTag> tags) {
        return tags.stream().anyMatch(this::has);
    }

    /** True if the tag is a main active of this product rather than a minor ingredient. */
    public boolean hasMainActive(IngredientTag tag) {
        Integer position = firstPositions.get(tag);
        return position != null
                && (position <= MAIN_ACTIVE_MAX_POSITION || POTENT_AT_LOW_LEVELS.contains(tag));
    }

    /** Declared as an OTC drug active (e.g. adapalene 0.1%) rather than a cosmetic ingredient. */
    public boolean isOtcActive(IngredientTag tag) {
        return Integer.valueOf(0).equals(firstPositions.get(tag));
    }

    /** Mineral sunscreen: zinc oxide / titanium dioxide only, no chemical UV filters. */
    public boolean mineral() {
        return !has(IngredientTag.CHEMICAL_UV_FILTER);
    }

    public boolean tinted() {
        return has(IngredientTag.IRON_OXIDE);
    }

    /** Texture guessed from the product name until the catalog stores it. */
    public Texture texture() {
        String n = name.toLowerCase();
        if (n.matches(".*\\b(gel|water|fluid|essence|jelly|foam|whip|lotion|serum|liquid)\\b.*")) {
            return Texture.LIGHT;
        }
        if (n.matches(".*\\b(cream|balm|butter)\\b.*")) {
            return Texture.RICH;
        }
        return Texture.UNKNOWN;
    }

    public enum Texture { LIGHT, RICH, UNKNOWN }

    /** Cleansing balms and oils remove makeup and sunscreen; they are never picked as the routine cleanser. */
    public boolean makeupRemover() {
        return "cleanser".equals(category)
                && name.toLowerCase().matches(".*\\b(cleansing (balm|oil)|(balm|oil) cleanser)\\b.*");
    }
}
