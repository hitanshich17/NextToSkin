package com.nexttoskin.core.climate;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexttoskin.core.climate.Climate.Signal;
import org.junit.jupiter.api.Test;

/** Thresholds from docs/routine-rules.md, section 6, on real 30-day averages from Open-Meteo. */
class ClimateTest {

    @Test
    void humidCityByDewPoint() {
        assertThat(new Climate("Miami", "Florida", 6.9, 23.6, 83.3, 6.2).signals()).containsExactly(Signal.HUMID);
    }

    @Test
    void coldAirIsDryEvenWhenRelativeHumidityIsHigh() {
        assertThat(new Climate("Minneapolis", "Minnesota", 1.5, -14.4, 78.1, 7.0).signals())
                .containsExactly(Signal.DRY_AIR);
    }

    @Test
    void desertIsDryByRelativeHumidity() {
        assertThat(new Climate("Phoenix", "Arizona", 6.9, 14.5, 38.4, 10.4).signals())
                .containsExactlyInAnyOrder(Signal.DRY_AIR, Signal.POLLUTED);
    }

    @Test
    void mildCityHasNoSignals() {
        assertThat(new Climate("Seattle", "Washington", 4.2, 10.3, 78.7, 8.9).signals()).isEmpty();
    }

    @Test
    void uvOf8IsVeryHigh() {
        assertThat(new Climate("Honolulu", "Hawaii", 8.0, 19.0, 70.0, null).signals())
                .containsExactlyInAnyOrder(Signal.HIGH_UV, Signal.HUMID);
        assertThat(new Climate("Austin", "Texas", 7.9, 15.0, 60.0, 9.0).signals()).isEmpty(); // PM2.5 must be above 9
    }
}
