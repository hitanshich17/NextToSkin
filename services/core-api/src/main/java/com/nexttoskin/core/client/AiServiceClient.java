package com.nexttoskin.core.client;

import com.nexttoskin.core.config.AiServiceProperties;
import org.slf4j.Logger;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the Python AI service.
 * Timeouts are explicit so a slow or down AI service can never hang the core API.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient restClient;

    @Autowired // the constructor Spring uses; the other one is for tests
    public AiServiceClient(AiServiceProperties props) {
        this(RestClient.builder().baseUrl(props.baseUrl()).requestFactory(timeouts(props)).build());
    }

    AiServiceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeouts(AiServiceProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return factory;
    }

    record ExplanationReply(String text) {
    }

    /** The LLM's explanation, or empty if the AI service has none (not configured, down, slow or rejected). */
    public Optional<String> explain(Object facts) {
        try {
            ExplanationReply reply = restClient.post().uri("/explanations").body(facts).retrieve()
                    .body(ExplanationReply.class);
            return Optional.ofNullable(reply).map(ExplanationReply::text).filter(t -> !t.isBlank());
        } catch (RestClientException e) {
            log.info("No AI explanation: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns true if the AI service answers its health check, false on any failure. */
    public boolean isHealthy() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("AI service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
