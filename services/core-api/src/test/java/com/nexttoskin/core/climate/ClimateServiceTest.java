package com.nexttoskin.core.climate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.ResourceAccessException;

class ClimateServiceTest {

    private static final Climate MIAMI = new Climate("Miami", "Florida", 6.9, 23.6, 83.3, 6.2);
    private static final Duration DAY = Duration.ofHours(24);

    private final OpenMeteoClient openMeteo = mock(OpenMeteoClient.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> cache = mock(ValueOperations.class);
    private final ObjectMapper json = new ObjectMapper();
    private ClimateService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(cache);
        service = new ClimateService(openMeteo, redis, json, new OpenMeteoProperties("g", "f", "a", DAY, DAY, DAY));
    }

    @Test
    void cachedClimateSkipsOpenMeteo() throws Exception {
        when(cache.get("climate:miami, fl")).thenReturn(json.writeValueAsString(MIAMI));

        assertThat(service.climateFor("  Miami,  FL ")).contains(MIAMI);
        verify(openMeteo, never()).climate(anyString());
    }

    @Test
    void lookupIsCachedForADay() throws Exception {
        when(openMeteo.climate("Miami")).thenReturn(Optional.of(MIAMI));

        assertThat(service.climateFor("Miami")).contains(MIAMI);
        verify(cache).set("climate:miami", json.writeValueAsString(MIAMI), DAY);
    }

    @Test
    void unknownCityIsCachedToo() {
        when(openMeteo.climate("Atlantis")).thenReturn(Optional.empty());

        assertThat(service.climateFor("Atlantis")).isEmpty();
        verify(cache).set("climate:atlantis", "not-found", DAY);
    }

    @Test
    void openMeteoDownMeansNoClimateAndNothingCached() {
        when(openMeteo.climate("Miami")).thenThrow(new ResourceAccessException("timeout"));

        assertThat(service.climateFor("Miami")).isEmpty();
        verify(cache, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisDownStillLooksUpTheClimate() {
        when(cache.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
                .when(cache).set(anyString(), anyString(), eq(DAY));
        when(openMeteo.climate("Miami")).thenReturn(Optional.of(MIAMI));

        assertThat(service.climateFor("Miami")).contains(MIAMI);
    }

    @Test
    void noCityNoClimate() {
        assertThat(service.climateFor(null)).isEmpty();
        assertThat(service.climateFor(" ")).isEmpty();
    }
}
