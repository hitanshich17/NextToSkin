package com.nexttoskin.core.routine;

import com.nexttoskin.core.feedback.Feedback;
import com.nexttoskin.core.feedback.Feedback.Reason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a client said about products they tried (docs/feedback.md), by catalog product id.
 *
 * @param disliked product id -> reason; a dislike without a reason counts as IRRITATED
 * @param similarity product id -> resemblance to liked products minus resemblance to disliked ones (pgvector)
 */
public record TriedProducts(Set<String> liked, Map<String, Reason> disliked, Map<String, Double> similarity) {

    public static final TriedProducts NONE = new TriedProducts(Set.of(), Map.of(), Map.of());

    public TriedProducts {
        liked = Set.copyOf(liked);
        disliked = Map.copyOf(disliked);
        similarity = Map.copyOf(similarity);
    }

    public TriedProducts(Set<String> liked, Map<String, Reason> disliked) {
        this(liked, disliked, Map.of());
    }

    public static TriedProducts from(List<Feedback> feedback) {
        return from(feedback, Map.of());
    }

    /** Similarity rounded to 0.1, so tiny differences count as a tie and price decides. */
    public double similarityTo(RoutineProduct p) {
        return Math.round(similarity.getOrDefault(p.id(), 0.0) * 10) / 10.0;
    }

    public static TriedProducts from(List<Feedback> feedback, Map<String, Double> similarity) {
        Set<String> liked = feedback.stream().filter(f -> f.verdict() == Feedback.Verdict.LIKED)
                .map(Feedback::productId).collect(Collectors.toSet());
        Map<String, Reason> disliked = new LinkedHashMap<>();
        feedback.stream().filter(f -> f.verdict() == Feedback.Verdict.DISLIKED)
                .forEach(f -> disliked.put(f.productId(), f.effectiveReason()));
        return new TriedProducts(liked, disliked, similarity);
    }

    public boolean likes(RoutineProduct p) {
        return liked.contains(p.id());
    }

    public boolean dislikes(RoutineProduct p) {
        return disliked.containsKey(p.id());
    }

    /** Disliked catalog products with this reason. */
    List<RoutineProduct> disliked(List<RoutineProduct> catalog, Reason reason) {
        return catalog.stream().filter(p -> disliked.get(p.id()) == reason).toList();
    }
}
