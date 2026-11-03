package com.nexttoskin.core.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductVectorsTest {

    // 10 products: ingredient 1 (water) in all, 2 in 5, 3 in 1.
    private static final Map<Long, Integer> PRODUCTS_WITH = Map.of(1L, 10, 2L, 5, 3L, 1);

    @Test
    void anIngredientInEveryProductWeighsNothing() {
        assertThat(ProductVectors.weights(Map.of(1L, 1, 2L, 2), PRODUCTS_WITH, 10)).containsOnlyKeys(2L);
    }

    @Test
    void rarerIngredientsWeighMore() {
        var w = ProductVectors.weights(Map.of(2L, 4, 3L, 4), PRODUCTS_WITH, 10);
        assertThat(w.get(3L)).isGreaterThan(w.get(2L));
    }

    @Test
    void earlierOnTheLabelWeighsMore() {
        var early = ProductVectors.weights(Map.of(3L, 1), PRODUCTS_WITH, 10).get(3L);
        var late = ProductVectors.weights(Map.of(3L, 16), PRODUCTS_WITH, 10).get(3L);
        assertThat(late).isEqualTo(early / 4, org.assertj.core.data.Offset.offset(1e-9)); // 1 / sqrt(16)
    }

    @Test
    void sparsevecLiteralHasAscendingIndicesAndTheDimension() {
        assertThat(ProductVectors.sparsevec(new java.util.TreeMap<>(Map.of(17L, 1.2, 3L, 0.5))))
                .isEqualTo("{3:0.500000,17:1.200000}/1000000");
        assertThatThrownBy(() -> ProductVectors.sparsevec(Map.of(2_000_000L, 1.0)))
                .isInstanceOf(IllegalStateException.class);
    }
}
