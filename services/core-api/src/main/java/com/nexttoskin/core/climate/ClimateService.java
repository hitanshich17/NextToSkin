package com.nexttoskin.core.climate;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * A city's climate, cached in Redis for a day per city. Many quiz takers share a city, the 30-day averages
 * barely move within a day, and the cache keeps us far below Open-Meteo's free-tier limits.
 *
 * <p>Climate only fine-tunes a routine, so any failure (Open-Meteo or Redis down) gives "no climate"
 * instead of an error.
 */
@Service
public class ClimateService {

    private static final Logger log = LoggerFactory.getLogger(ClimateService.class);
    /** Cached when a city isn't found, so repeated typos don't call Open-Meteo again. */
    private static final String NOT_FOUND = "not-found";

    private final OpenMeteoClient openMeteo;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final OpenMeteoProperties props;

    public ClimateService(OpenMeteoClient openMeteo, StringRedisTemplate redis, ObjectMapper json,
                          OpenMeteoProperties props) {
        this.openMeteo = openMeteo;
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public Optional<Climate> climateFor(String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        String key = "climate:" + city.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String cached = readCache(key);
        if (cached != null) {
            return cached.equals(NOT_FOUND) ? Optional.empty() : parse(cached);
        }
        Optional<Climate> climate;
        try {
            climate = openMeteo.climate(city);
        } catch (RuntimeException e) {
            log.warn("Climate lookup failed for '{}': {}", city, e.getMessage());
            return Optional.empty(); // not cached: try again next time
        }
        writeCache(key, climate.map(this::toJson).orElse(NOT_FOUND));
        return climate;
    }

    private String readCache(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Climate cache unavailable: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, String value) {
        try {
            redis.opsForValue().set(key, value, props.cacheFor());
        } catch (RuntimeException e) {
            log.warn("Climate cache unavailable: {}", e.getMessage());
        }
    }

    private String toJson(Climate climate) {
        try {
            return json.writeValueAsString(climate);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Climate> parse(String cached) {
        try {
            return Optional.of(json.readValue(cached, Climate.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
