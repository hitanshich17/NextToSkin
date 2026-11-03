package com.skinvidhi.core.explanation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skinvidhi.core.client.AiServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * "Why this routine": the LLM's explanation when it gives a valid one in time, otherwise the template.
 * LLM explanations are cached in Redis by their facts, so the same profile is explained once (and paid for once).
 */
@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);
    static final Duration CACHE_FOR = Duration.ofDays(30);
    /** Bump when the AI service's prompt or checks change, so explanations written under the old ones are dropped. */
    static final String PROMPT_VERSION = "v2";

    public enum Source { AI, TEMPLATE }

    public record Explanation(String text, Source source) {
    }

    private final AiServiceClient ai;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public ExplanationService(AiServiceClient ai, StringRedisTemplate redis, ObjectMapper json) {
        this.ai = ai;
        this.redis = redis;
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public Explanation explain(ExplanationFacts facts) {
        String key = "explanation:" + PROMPT_VERSION + ":" + sha256(facts);
        String cached = readCache(key);
        if (cached != null) {
            return new Explanation(cached, Source.AI);
        }
        Optional<String> text = ai.explain(facts);
        if (text.isPresent()) {
            writeCache(key, text.get());
            return new Explanation(text.get(), Source.AI);
        }
        return new Explanation(ExplanationTemplate.explain(facts), Source.TEMPLATE);
    }

    private String sha256(ExplanationFacts facts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(facts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Explanation cache unavailable: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, String text) {
        try {
            redis.opsForValue().set(key, text, CACHE_FOR);
        } catch (RuntimeException e) {
            log.warn("Explanation cache unavailable: {}", e.getMessage());
        }
    }
}
