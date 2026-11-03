package com.nexttoskin.core.routine;

import static com.nexttoskin.core.ingredient.IngredientTag.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.PostgresTestConfiguration;
import com.nexttoskin.core.catalog.CatalogImporter;
import com.nexttoskin.core.routine.QuizAnswers.ActivesExperience;
import com.nexttoskin.core.routine.QuizAnswers.Avoid;
import com.nexttoskin.core.routine.QuizAnswers.Concern;
import com.nexttoskin.core.routine.QuizAnswers.Pregnancy;
import com.nexttoskin.core.routine.QuizAnswers.Reactivity;
import com.nexttoskin.core.routine.QuizAnswers.SkinType;
import com.nexttoskin.core.routine.RoutinePlan.Step;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the routine rules on the real curated catalog and checks the safety rules hold. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutineCatalogIntegrationTest {

    @Autowired
    private CatalogImporter importer;

    @Autowired
    private RoutineCatalog routineCatalog;

    @Autowired
    private JdbcTemplate jdbc;

    private List<RoutineProduct> catalog;

    @BeforeAll
    void importRealCatalog() throws Exception {
        jdbc.execute("TRUNCATE products CASCADE");
        importer.importDirectory(Path.of("../../catalog"));
        catalog = routineCatalog.load();
    }

    private static QuizAnswers answers(SkinType type, List<Concern> concerns, Reactivity reactivity,
                                       Set<Avoid> avoid, ActivesExperience experience, Pregnancy pregnancy) {
        return new QuizAnswers(type, concerns, reactivity, avoid, experience, pregnancy, null, null, null);
    }

    @Test
    void loadsEveryRoutineProductWithTagsAndPrices() {
        assertThat(catalog).hasSizeGreaterThan(90);
        assertThat(catalog).filteredOn(p -> p.id().equals("differin-adapalene-gel")).singleElement()
                .satisfies(p -> assertThat(p.isOtcActive(RETINOID)).isTrue());
        assertThat(catalog).filteredOn(p -> p.category().equals("sunscreen"))
                .allSatisfy(p -> assertThat(p.spf()).isGreaterThanOrEqualTo(30));
    }

    @Test
    void typicalUserGetsEveryStep() {
        RoutinePlan plan = RoutineRules.plan(answers(SkinType.COMBINATION, List.of(Concern.BREAKOUTS, Concern.DARK_SPOTS),
                Reactivity.SOMETIMES, Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO), catalog);

        assertThat(plan.candidates()).allSatisfy((step, products) -> assertThat(products).as(step.name()).isNotEmpty());
        assertThat(plan.treatmentActive()).isEqualTo(RETINOID);
    }

    @Test
    void pregnantUserNeverSeesARetinoid() {
        RoutinePlan plan = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.FINE_LINES), Reactivity.RARELY,
                Set.of(), ActivesExperience.REGULARLY, Pregnancy.YES), catalog);

        assertThat(plan.candidates().values()).allSatisfy(ps -> assertThat(ps).noneMatch(p -> p.has(RETINOID)));
        assertThat(plan.candidates().get(Step.AM_SUNSCREEN)).isNotEmpty().allMatch(RoutineProduct::mineral);
    }

    @Test
    void rosaceaProneUserGetsNoAadListedIrritants() {
        RoutinePlan plan = RoutineRules.plan(answers(SkinType.NORMAL, List.of(Concern.REDNESS), Reactivity.OFTEN,
                Set.of(Avoid.NUTS), ActivesExperience.NEVER, Pregnancy.NO), catalog);

        Set<com.nexttoskin.core.ingredient.IngredientTag> banned = Set.of(FRAGRANCE, FRAGRANCE_ALLERGEN, ESSENTIAL_OIL,
                DRYING_ALCOHOL, MENTHOL, CAMPHOR, SODIUM_LAURYL_SULFATE, UREA, AHA, NUT);
        assertThat(plan.candidates().values()).allSatisfy(ps -> assertThat(ps).noneMatch(p -> p.hasAny(banned)));
        assertThat(plan.candidates().get(Step.AM_SUNSCREEN)).isNotEmpty();
    }

    @Test
    void budgetIsRespectedOrReported() {
        RoutinePlan plan = RoutineRules.plan(answers(SkinType.COMBINATION, List.of(Concern.BREAKOUTS, Concern.DARK_SPOTS),
                Reactivity.SOMETIMES, Set.of(), ActivesExperience.A_LITTLE, Pregnancy.NO), catalog);

        Routine unlimited = RoutineSelector.select(plan, null);
        assertThat(unlimited.picks()).hasSize(Step.values().length);

        Routine tight = RoutineSelector.select(plan, unlimited.totalCents() - 1000);
        assertThat(tight.totalCents()).isLessThan(unlimited.totalCents());
        assertThat(tight.withinBudget() || tight.notes().contains(RoutinePlan.Note.OVER_BUDGET)).isTrue();
    }
}
