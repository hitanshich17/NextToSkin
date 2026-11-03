package com.nexttoskin.core.climate;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Open-Meteo endpoints and timeouts, bound from nexttoskin.open-meteo.* */
@ConfigurationProperties(prefix = "nexttoskin.open-meteo")
public record OpenMeteoProperties(String geocodingUrl, String forecastUrl, String airQualityUrl,
                                  Duration connectTimeout, Duration readTimeout, Duration cacheFor) {
}
