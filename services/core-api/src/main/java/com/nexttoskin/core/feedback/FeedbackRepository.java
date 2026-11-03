package com.nexttoskin.core.feedback;

import com.nexttoskin.core.feedback.Feedback.Reason;
import com.nexttoskin.core.feedback.Feedback.Verdict;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Feedback per client and product. Products are referred to by their catalog id (e.g. "cerave-..."). */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Saves or replaces the client's verdict.
     *
     * @param sessionId the routine the product was shown in; ignored unless it belongs to this client
     * @return false if there is no such catalog product
     */
    public boolean save(UUID clientId, Feedback feedback, Long sessionId) {
        return jdbc.update("""
                INSERT INTO product_feedback (client_id, product_id, verdict, reason, session_id)
                SELECT ?, p.id, ?, ?, (SELECT s.id FROM quiz_sessions s WHERE s.id = ? AND s.client_id = ?)
                FROM products p WHERE p.source = 'curated' AND p.source_id = ?
                ON CONFLICT (client_id, product_id) DO UPDATE SET
                    verdict = EXCLUDED.verdict, reason = EXCLUDED.reason,
                    session_id = EXCLUDED.session_id, updated_at = now()
                """, clientId, feedback.verdict().name(), feedback.reason() == null ? null : feedback.reason().name(),
                sessionId, clientId, feedback.productId()) == 1;
    }

    /** Undoes a verdict; deleting one that doesn't exist is fine. */
    public void delete(UUID clientId, String productId) {
        jdbc.update("""
                DELETE FROM product_feedback f USING products p
                WHERE f.product_id = p.id AND f.client_id = ? AND p.source = 'curated' AND p.source_id = ?
                """, clientId, productId);
    }

    public List<Feedback> findAll(UUID clientId) {
        return jdbc.query("""
                SELECT p.source_id, f.verdict, f.reason
                FROM product_feedback f JOIN products p ON p.id = f.product_id
                WHERE f.client_id = ? ORDER BY f.updated_at, p.source_id
                """, (rs, i) -> new Feedback(rs.getString("source_id"), Verdict.valueOf(rs.getString("verdict")),
                rs.getString("reason") == null ? null : Reason.valueOf(rs.getString("reason"))), clientId);
    }
}
