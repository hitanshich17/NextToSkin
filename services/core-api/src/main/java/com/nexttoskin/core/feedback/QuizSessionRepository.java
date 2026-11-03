package com.nexttoskin.core.feedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexttoskin.core.climate.Climate;
import com.nexttoskin.core.routine.QuizAnswers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores each routine built for a client, as training data for the ranking model (step 6). */
@Repository
public class QuizSessionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public QuizSessionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param routine step name -> catalog product id shown
     * @return the new session's id
     */
    public long save(UUID clientId, QuizAnswers answers, Set<Climate.Signal> climateSignals,
                     Map<String, String> routine) {
        return jdbc.queryForObject("""
                INSERT INTO quiz_sessions (client_id, answers, climate_signals, routine)
                VALUES (?, ?::jsonb, string_to_array(?, ','), ?::jsonb) RETURNING id
                """, Long.class, clientId, toJson(storedAnswers(answers)),
                climateSignals.stream().map(Enum::name).sorted().collect(Collectors.joining(",")), toJson(routine));
    }

    /** The answers without pregnancy (sensitive health data) and city (kept only as climate signals). */
    static Map<String, Object> storedAnswers(QuizAnswers a) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("skinType", a.skinType());
        stored.put("concerns", a.concerns());
        stored.put("reactivity", a.reactivity());
        stored.put("avoid", a.avoid().stream().map(Enum::name).sorted().toList());
        stored.put("activesExperience", a.activesExperience());
        stored.put("budgetCents", a.budgetCents());
        stored.put("skinTone", a.skinTone());
        return stored;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
