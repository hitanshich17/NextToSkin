package com.nexttoskin.core.feedback;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Tried it?" verdicts. The browser identifies itself with a random UUID in the X-Client-Id header:
 * no accounts and nothing personal (docs/feedback.md).
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    static final String CLIENT_ID = "X-Client-Id";

    private final FeedbackRepository feedback;

    public FeedbackController(FeedbackRepository feedback) {
        this.feedback = feedback;
    }

    @GetMapping
    public List<Feedback> list(@RequestHeader(CLIENT_ID) UUID clientId) {
        return feedback.findAll(clientId);
    }

    /** @param sessionId optional: the routine the product was shown in */
    public record FeedbackRequest(Feedback.Verdict verdict, Feedback.Reason reason, Long sessionId) {
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Void> save(@RequestHeader(CLIENT_ID) UUID clientId, @PathVariable String productId,
                                     @RequestBody FeedbackRequest request) {
        Feedback verdict = new Feedback(productId, request.verdict(), request.reason());
        if (!feedback.save(clientId, verdict, request.sessionId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> undo(@RequestHeader(CLIENT_ID) UUID clientId, @PathVariable String productId) {
        feedback.delete(clientId, productId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badFeedback(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "unreadable feedback");
    }
}
