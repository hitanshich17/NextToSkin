package com.nexttoskin.core.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.PostgresTestConfiguration;
import com.nexttoskin.core.climate.Climate;
import com.nexttoskin.core.feedback.Feedback.Reason;
import com.nexttoskin.core.feedback.Feedback.Verdict;
import com.nexttoskin.core.routine.QuizAnswers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V9 schema, upserts and session ownership against real PostgreSQL. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class FeedbackIntegrationTest {

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private QuizSessionRepository sessions;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID client = UUID.randomUUID();
    private final UUID otherClient = UUID.randomUUID();

    @BeforeEach
    void products() {
        jdbc.update("DELETE FROM product_feedback");
        jdbc.update("DELETE FROM quiz_sessions");
        for (String id : List.of("fb-cream", "fb-cleanser")) {
            jdbc.update("""
                    INSERT INTO products (brand, name, category, source, source_id) VALUES ('B', ?, 'moisturizer', 'curated', ?)
                    ON CONFLICT (source, source_id) DO NOTHING
                    """, id, id);
        }
    }

    private static QuizAnswers answers() {
        return new QuizAnswers(QuizAnswers.SkinType.DRY, List.of(QuizAnswers.Concern.REDNESS),
                QuizAnswers.Reactivity.OFTEN, Set.of(), QuizAnswers.ActivesExperience.NEVER,
                QuizAnswers.Pregnancy.YES, null, "Miami", null);
    }

    @Test
    void sessionStoresAnswersWithoutPregnancyOrCity() {
        long id = sessions.save(client, answers(), Set.of(Climate.Signal.HUMID), Map.of("AM_CLEANSER", "fb-cleanser"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT answers::text AS answers, array_to_string(climate_signals, ',') AS signals, routine::text AS routine "
                        + "FROM quiz_sessions WHERE id = ?", id);
        assertThat((String) row.get("answers")).contains("\"skinType\": \"DRY\"").doesNotContain("pregnan", "Miami");
        assertThat(row.get("signals")).isEqualTo("HUMID");
        assertThat((String) row.get("routine")).contains("fb-cleanser");
        assertThat(sessions.save(client, answers(), Set.of(), Map.of())).isGreaterThan(id); // no signals is fine
    }

    @Test
    void newVerdictReplacesTheOldOne() {
        assertThat(feedback.save(client, new Feedback("fb-cream", Verdict.LIKED, null), null)).isTrue();
        assertThat(feedback.save(client, new Feedback("fb-cream", Verdict.DISLIKED, Reason.TEXTURE), null)).isTrue();

        assertThat(feedback.findAll(client)).containsExactly(new Feedback("fb-cream", Verdict.DISLIKED, Reason.TEXTURE));
        assertThat(feedback.findAll(otherClient)).isEmpty();
    }

    @Test
    void unknownProductIsNotSaved() {
        assertThat(feedback.save(client, new Feedback("no-such-product", Verdict.LIKED, null), null)).isFalse();
    }

    @Test
    void sessionIsLinkedOnlyWhenItBelongsToTheClient() {
        long mine = sessions.save(client, answers(), Set.of(), Map.of());
        long theirs = sessions.save(otherClient, answers(), Set.of(), Map.of());

        feedback.save(client, new Feedback("fb-cream", Verdict.LIKED, null), mine);
        feedback.save(client, new Feedback("fb-cleanser", Verdict.LIKED, null), theirs);

        assertThat(jdbc.queryForList("SELECT session_id FROM product_feedback ORDER BY session_id NULLS LAST", Long.class))
                .containsExactly(mine, null);
    }

    @Test
    void undoRemovesTheVerdict() {
        feedback.save(client, new Feedback("fb-cream", Verdict.LIKED, null), null);
        feedback.delete(client, "fb-cream");
        feedback.delete(client, "fb-cream"); // twice is fine

        assertThat(feedback.findAll(client)).isEmpty();
    }

    @Test
    void oldAccountTablesAreGone() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('users') IS NULL", Boolean.class)).isTrue();
    }
}
