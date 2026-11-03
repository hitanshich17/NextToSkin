package com.nexttoskin.core.feedback;

/**
 * A client's verdict on a product they tried (docs/feedback.md).
 *
 * @param reason why it was disliked, or null (always null for a like)
 */
public record Feedback(String productId, Verdict verdict, Reason reason) {

    public enum Verdict { LIKED, DISLIKED }

    /** Why a product was disliked; decides the replacement. */
    public enum Reason { IRRITATED, DIDNT_WORK, TEXTURE, TOO_PRICEY }

    public Feedback {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required");
        }
        if (verdict == Verdict.LIKED && reason != null) {
            throw new IllegalArgumentException("only a dislike has a reason");
        }
    }

    /** A dislike without a reason is treated as a reaction: the safest choice. */
    public Reason effectiveReason() {
        return verdict == Verdict.DISLIKED && reason == null ? Reason.IRRITATED : reason;
    }
}
