package com.nexttoskin.core.explanation;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexttoskin.core.client.AiServiceClient;
import com.nexttoskin.core.climate.Climate;
import com.nexttoskin.core.explanation.ExplanationService.Source;
import com.nexttoskin.core.ingredient.IngredientTag;
import com.nexttoskin.core.routine.QuizAnswers;
import com.nexttoskin.core.routine.QuizAnswers.ActivesExperience;
import com.nexttoskin.core.routine.QuizAnswers.Avoid;
import com.nexttoskin.core.routine.QuizAnswers.Concern;
import com.nexttoskin.core.routine.QuizAnswers.Pregnancy;
import com.nexttoskin.core.routine.QuizAnswers.Reactivity;
import com.nexttoskin.core.routine.QuizAnswers.SkinType;
import com.nexttoskin.core.routine.Routine;
import com.nexttoskin.core.routine.RoutinePlan;
import com.nexttoskin.core.routine.RoutineRules;
import com.nexttoskin.core.routine.RoutineSelector;
import com.nexttoskin.core.routine.RoutineProduct;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ExplanationTest {

    private static RoutineProduct p(String id, String brand, String category, Integer spf, int price, Object... tags) {
        Map<IngredientTag, Integer> positions = new HashMap<>();
        for (int i = 0; i < tags.length; i += 2) {
            positions.put((IngredientTag) tags[i], (Integer) tags[i + 1]);
        }
        return new RoutineProduct(id, brand, id, category, spf, false, positions,
                new RoutineProduct.Offer("brand", price, BigDecimal.TEN, "ml", "https://example.com/" + id));
    }

    static final List<RoutineProduct> CATALOG = List.of(
            p("gentle-cleanser", "CeraVe", "cleanser", null, 1500, CERAMIDE, 5),
            p("azelaic-suspension", "The Ordinary", "treatment", null, 1200, AZELAIC_ACID, 3),
            p("barrier-cream", "Vanicream", "moisturizer", null, 1400, CERAMIDE, 6),
            p("mineral-spf", "EltaMD", "sunscreen", 46, 4000, IRON_OXIDE, 9));

    private static Routine routine(QuizAnswers answers, Climate climate) {
        RoutinePlan plan = RoutineRules.plan(answers, CATALOG, climate);
        return RoutineSelector.select(plan, null);
    }

    private static QuizAnswers rednessPregnant() {
        return new QuizAnswers(SkinType.DRY, List.of(Concern.REDNESS, Concern.DARK_SPOTS), Reactivity.OFTEN,
                Set.of(Avoid.FRAGRANCE), ActivesExperience.NEVER, Pregnancy.YES, null, "Phoenix, AZ", null);
    }

    static final Climate PHOENIX = new Climate("Phoenix", "Arizona", 9.5, 14.5, 38.4, 10.4);

    @Test
    void factsDescribeTheRoutineInWordsWithoutPregnancyOrCity() throws Exception {
        ExplanationFacts facts = ExplanationFacts.of(rednessPregnant(), routine(rednessPregnant(), PHOENIX), PHOENIX);

        assertThat(facts.concerns()).containsExactly("redness", "dark spots and marks");
        assertThat(facts.treatmentActive()).isEqualTo("azelaic acid");
        assertThat(facts.climate()).containsExactly("dry air", "polluted air", "very strong sun");
        assertThat(facts.steps()).extracting(ExplanationFacts.StepFacts::time).containsExactly("AM", "AM", "AM", "PM", "PM", "PM");
        assertThat(facts.steps().get(2).keyIngredients()).contains("mineral filters (zinc oxide, titanium dioxide)",
                "iron oxides (tint)");
        String json = new ObjectMapper().writeValueAsString(facts).toLowerCase();
        assertThat(json).doesNotContain("pregnan", "phoenix", "arizona");
    }

    @Test
    void templateExplainsTheMainDecisions() {
        ExplanationFacts facts = ExplanationFacts.of(rednessPregnant(), routine(rednessPregnant(), PHOENIX), PHOENIX);

        assertThat(ExplanationTemplate.explain(facts)).isEqualTo(
                "This routine is built for dry skin, with redness and dark spots and marks as your main concerns. "
                        + "Your night treatment, The Ordinary azelaic-suspension, uses azelaic acid, one of the "
                        + "best-supported options for redness. The products are also chosen for the dry air, polluted "
                        + "air and very strong sun where you live. Every product leaves out fragrance.");
    }

    @Test
    void templateWithoutTreatmentSaysSo() {
        var answers = new QuizAnswers(SkinType.NOT_SURE, List.of(Concern.FINE_LINES), Reactivity.RARELY, Set.of(),
                ActivesExperience.A_LITTLE, Pregnancy.NO, null, null, null);
        String text = ExplanationTemplate.explain(ExplanationFacts.of(answers, routine(answers, null), null));

        assertThat(text).startsWith("This routine is built for your skin, with fine lines as your main concern.")
                .contains("No treatment in our catalog fits all your answers");
    }

    // ---- ExplanationService: AI when available, cached; template otherwise ----

    private final AiServiceClient ai = mock(AiServiceClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> cache = mock(ValueOperations.class);

    private ExplanationService service() {
        when(redis.opsForValue()).thenReturn(cache);
        return new ExplanationService(ai, redis, new ObjectMapper());
    }

    private ExplanationFacts facts() {
        return ExplanationFacts.of(rednessPregnant(), routine(rednessPregnant(), PHOENIX), PHOENIX);
    }

    @Test
    void aiExplanationIsUsedAndCached() {
        when(ai.explain(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of("Because your skin..."));
        var explanation = service().explain(facts());

        assertThat(explanation).isEqualTo(new ExplanationService.Explanation("Because your skin...", Source.AI));
        verify(cache).set(anyString(), eq("Because your skin..."), eq(ExplanationService.CACHE_FOR));
    }

    @Test
    void cachedExplanationSkipsTheAiService() {
        ExplanationService service = service();
        when(cache.get(anyString())).thenReturn("Cached.");

        assertThat(service.explain(facts()).text()).isEqualTo("Cached.");
        verify(ai, never()).explain(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sameFactsSameCacheKey() {
        ExplanationService service = service();
        when(ai.explain(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of("A."));
        service.explain(facts());
        service.explain(facts());
        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cache, org.mockito.Mockito.times(2)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1)).startsWith("explanation:");
    }

    @Test
    void noAiExplanationMeansTemplateAndNothingCached() {
        when(ai.explain(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        var explanation = service().explain(facts());

        assertThat(explanation.source()).isEqualTo(Source.TEMPLATE);
        assertThat(explanation.text()).startsWith("This routine is built for dry skin");
        verify(cache, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    void aBudgetSwapIsAReason() {
        // Dark spots prefer a tinted sunscreen, so the tinted EltaMD ranks first.
        var answers = new QuizAnswers(SkinType.NORMAL, List.of(Concern.REDNESS, Concern.DARK_SPOTS),
                Reactivity.RARELY, Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO, null, null, null);
        var cheapSpf = p("cheap-mineral-spf", "Brand", "sunscreen", 30, 1000);
        var catalog = new java.util.ArrayList<>(CATALOG);
        catalog.add(cheapSpf);
        // 1500 + 1200 + 1400 + 4000 = $81 with the EltaMD; a $75 budget forces the cheaper sunscreen.
        Routine routine = RoutineSelector.select(RoutineRules.plan(answers, catalog, null), 7500);
        ExplanationFacts facts = ExplanationFacts.of(answers, routine, null);

        var sunscreen = facts.steps().stream().filter(s -> s.step().equals("sunscreen")).findFirst().orElseThrow();
        assertThat(sunscreen.product()).isEqualTo("Brand cheap-mineral-spf");
        assertThat(sunscreen.reasons()).contains("a cheaper option, picked to fit your budget");
    }
}
