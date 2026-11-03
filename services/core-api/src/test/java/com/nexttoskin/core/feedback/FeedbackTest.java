package com.nexttoskin.core.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.feedback.Feedback.Reason;
import com.nexttoskin.core.feedback.Feedback.Verdict;
import com.nexttoskin.core.routine.QuizAnswers;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeedbackTest {

    @Test
    void dislikeWithoutAReasonCountsAsIrritated() {
        assertThat(new Feedback("x", Verdict.DISLIKED, null).effectiveReason()).isEqualTo(Reason.IRRITATED);
        assertThat(new Feedback("x", Verdict.DISLIKED, Reason.TOO_PRICEY).effectiveReason()).isEqualTo(Reason.TOO_PRICEY);
        assertThat(new Feedback("x", Verdict.LIKED, null).effectiveReason()).isNull();
    }

    @Test
    void storedAnswersLeaveOutPregnancyAndCity() {
        QuizAnswers answers = new QuizAnswers(QuizAnswers.SkinType.OILY, List.of(QuizAnswers.Concern.BREAKOUTS),
                QuizAnswers.Reactivity.SOMETIMES, Set.of(QuizAnswers.Avoid.SOY, QuizAnswers.Avoid.FRAGRANCE),
                QuizAnswers.ActivesExperience.NEVER, QuizAnswers.Pregnancy.YES, 6000, "Austin, TX", 4);

        var stored = QuizSessionRepository.storedAnswers(answers);

        assertThat(stored).doesNotContainKeys("pregnancy", "city");
        assertThat(stored).containsEntry("skinType", QuizAnswers.SkinType.OILY)
                .containsEntry("avoid", List.of("FRAGRANCE", "SOY"))
                .containsEntry("budgetCents", 6000)
                .containsEntry("skinTone", 4);
    }
}
