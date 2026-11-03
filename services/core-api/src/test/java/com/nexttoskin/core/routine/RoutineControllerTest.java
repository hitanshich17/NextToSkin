package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(RoutineController.class)
class RoutineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoutineCatalog catalog;

    @MockitoBean
    private com.nexttoskin.core.climate.ClimateService climateService;

    @MockitoBean
    private com.nexttoskin.core.feedback.QuizSessionRepository sessions;

    @MockitoBean
    private com.nexttoskin.core.feedback.FeedbackRepository feedback;

    @MockitoBean
    private com.nexttoskin.core.similarity.SimilarityRepository similarity;

    @MockitoBean
    private com.nexttoskin.core.explanation.ExplanationService explanations;

    private static RoutineProduct p(String id, String category, Integer spf, int priceCents, Object... tagPositions) {
        Map<com.nexttoskin.core.ingredient.IngredientTag, Integer> positions = new HashMap<>();
        for (int i = 0; i < tagPositions.length; i += 2) {
            positions.put((com.nexttoskin.core.ingredient.IngredientTag) tagPositions[i], (Integer) tagPositions[i + 1]);
        }
        return new RoutineProduct(id, "Brand", id, category, spf, false, positions,
                new RoutineProduct.Offer("brand", priceCents, BigDecimal.valueOf(100), "ml", "https://example.com/" + id));
    }

    @BeforeEach
    void catalog() {
        when(catalog.load()).thenReturn(List.of(
                p("cleanser", "cleanser", null, 1000, CERAMIDE, 5),
                p("azelaic", "treatment", null, 1200, AZELAIC_ACID, 4),
                p("cream", "moisturizer", null, 1500, CERAMIDE, 6),
                p("cheap-cream", "moisturizer", null, 900, CERAMIDE, 8),
                p("water-gel", "moisturizer", null, 2500, CERAMIDE, 7),
                p("spf", "sunscreen", 50, 1600)));
    }

    private ResultActions postAnswers(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/routines").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void returnsAmAndPmStepsInOrder() throws Exception {
        postAnswers("""
                {"skinType": "NORMAL", "concerns": ["REDNESS"], "reactivity": "SOMETIMES",
                 "activesExperience": "A_LITTLE", "pregnancy": "NO", "budgetCents": 10000}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.am[*].step").value(org.hamcrest.Matchers.contains("cleanser", "moisturizer", "sunscreen")))
                .andExpect(jsonPath("$.pm[*].step").value(org.hamcrest.Matchers.contains("cleanser", "treatment", "moisturizer")))
                .andExpect(jsonPath("$.pm[1].product.id").value("azelaic"))
                .andExpect(jsonPath("$.treatmentActive").value("AZELAIC_ACID"))
                .andExpect(jsonPath("$.am[0].product.offer.priceCents").value(1000))
                .andExpect(jsonPath("$.totalCents").value(1000 + 1200 + 900 + 1600))
                .andExpect(jsonPath("$.withinBudget").value(true))
                .andExpect(jsonPath("$.monthlyCents").isNumber());
    }

    @Test
    void explanationIsIncluded() throws Exception {
        when(explanations.explain(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.nexttoskin.core.explanation.ExplanationService.Explanation("Because...",
                        com.nexttoskin.core.explanation.ExplanationService.Source.TEMPLATE));

        postAnswers("""
                {"skinType": "NORMAL", "concerns": ["REDNESS"], "reactivity": "RARELY",
                 "activesExperience": "A_LITTLE", "pregnancy": "YES"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation.text").value("Because..."))
                .andExpect(jsonPath("$.explanation.source").value("TEMPLATE"));
        org.mockito.Mockito.verify(explanations).explain(org.mockito.ArgumentMatchers.argThat(facts ->
                facts.notes().stream().noneMatch(n -> n.toLowerCase().contains("pregnan"))));
    }

    @Test
    void cheaperAlternativesAreListed() throws Exception {
        postAnswers("""
                {"skinType": "OILY", "concerns": ["REDNESS"], "reactivity": "RARELY",
                 "activesExperience": "NEVER", "pregnancy": "NO"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetCents").doesNotExist())
                .andExpect(jsonPath("$.am[1].product.id").value("water-gel")) // light texture for oily skin
                .andExpect(jsonPath("$.am[1].cheaperAlternatives[*].id")
                        .value(org.hamcrest.Matchers.contains("cheap-cream", "cream")));
    }

    @Test
    void notesComeWithTheirText() throws Exception {
        postAnswers("""
                {"skinType": "DRY", "concerns": ["FLAKY_PATCHES"], "reactivity": "RARELY",
                 "activesExperience": "NEVER", "pregnancy": "NO"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[?(@.code == 'SEE_DERMATOLOGIST_FOR_PATCHES')].text").isNotEmpty());
    }

    @Test
    void cityClimateIsAppliedAndReturned() throws Exception {
        when(climateService.climateFor("Phoenix, AZ")).thenReturn(java.util.Optional.of(
                new com.nexttoskin.core.climate.Climate("Phoenix", "Arizona", 9.5, 14.5, 38.4, 10.4)));

        postAnswers("""
                {"skinType": "NORMAL", "concerns": ["DULLNESS"], "reactivity": "RARELY",
                 "activesExperience": "A_LITTLE", "pregnancy": "NO", "city": "Phoenix, AZ"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.climate.city").value("Phoenix"))
                .andExpect(jsonPath("$.climate.signals").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "HIGH_UV", "DRY_AIR", "POLLUTED")))
                .andExpect(jsonPath("$.notes[*].code").value(org.hamcrest.Matchers.hasItems(
                        "HIGH_UV_REAPPLY", "AIR_POLLUTION")));
    }

    @Test
    void unknownCityStillGivesARoutine() throws Exception {
        when(climateService.climateFor("Atlantis")).thenReturn(java.util.Optional.empty());

        postAnswers("""
                {"skinType": "NORMAL", "concerns": ["DULLNESS"], "reactivity": "RARELY",
                 "activesExperience": "A_LITTLE", "pregnancy": "NO", "city": "Atlantis"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.climate").doesNotExist())
                .andExpect(jsonPath("$.am", hasSize(3)));
    }

    @Test
    void withoutAClientIdNothingIsStored() throws Exception {
        postAnswers("""
                {"skinType": "NORMAL", "concerns": ["DULLNESS"], "reactivity": "RARELY",
                 "activesExperience": "A_LITTLE", "pregnancy": "NO"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").doesNotExist());
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    @Test
    void withAClientIdTheRoutineIsStored() throws Exception {
        java.util.UUID client = java.util.UUID.randomUUID();
        when(sessions.save(org.mockito.ArgumentMatchers.eq(client), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/routines").header("X-Client-Id", client.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"skinType": "NORMAL", "concerns": ["DULLNESS"], "reactivity": "RARELY",
                         "activesExperience": "A_LITTLE", "pregnancy": "NO"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(42));
        org.mockito.Mockito.verify(sessions).save(org.mockito.ArgumentMatchers.eq(client),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(java.util.Set.of()),
                org.mockito.ArgumentMatchers.argThat(shown -> !shown.containsKey("PM_TREATMENT")
                        && "cleanser".equals(shown.get("AM_CLEANSER"))));
    }

    @Test
    void brokenRuleIsA400WithTheReason() throws Exception {
        postAnswers("""
                {"skinType": "OILY", "concerns": ["BREAKOUTS", "REDNESS", "DULLNESS"], "reactivity": "RARELY",
                 "activesExperience": "NEVER", "pregnancy": "NO"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("pick 1 or 2 concerns"));
    }

    @Test
    void missingAnswerIsA400() throws Exception {
        postAnswers("""
                {"concerns": ["BREAKOUTS"], "reactivity": "RARELY", "activesExperience": "NEVER", "pregnancy": "NO"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("skinType is required"));
    }

    @Test
    void unknownAnswerIsA400() throws Exception {
        postAnswers("""
                {"skinType": "SHINY", "concerns": ["BREAKOUTS"], "reactivity": "RARELY",
                 "activesExperience": "NEVER", "pregnancy": "NO"}
                """)
                .andExpect(status().isBadRequest());
    }
}
