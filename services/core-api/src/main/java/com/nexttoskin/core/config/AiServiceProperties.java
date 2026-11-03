package com.nexttoskin.core.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection settings for the Python AI service, bound from nexttoskin.ai-service.* */
@ConfigurationProperties(prefix = "nexttoskin.ai-service")
public record AiServiceProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
