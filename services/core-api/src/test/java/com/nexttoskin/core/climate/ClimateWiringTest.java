package com.nexttoskin.core.climate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Spring can build the climate beans from application.yml-style properties (no database or Docker needed). */
class ClimateWiringTest {

    @EnableConfigurationProperties(OpenMeteoProperties.class)
    static class Properties {
    }

    @Test
    void springCanCreateTheClimateBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(Properties.class, OpenMeteoClient.class, ClimateService.class)
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("nexttoskin.open-meteo.geocoding-url=https://geo.test",
                        "nexttoskin.open-meteo.forecast-url=https://wx.test", "nexttoskin.open-meteo.air-quality-url=https://air.test",
                        "nexttoskin.open-meteo.connect-timeout=2s", "nexttoskin.open-meteo.read-timeout=5s",
                        "nexttoskin.open-meteo.cache-for=24h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ClimateService.class);
                });
    }
}
