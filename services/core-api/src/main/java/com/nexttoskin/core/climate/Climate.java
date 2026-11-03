package com.nexttoskin.core.climate;

import java.util.EnumSet;
import java.util.Set;

/**
 * A city's climate over the last 30 days (docs/routine-rules.md, section 6).
 *
 * @param uvIndexMax average of the daily maximum UV index
 * @param dewPointC average dew point in °C; tells how much water the air really holds
 * @param humidity average relative humidity in %
 * @param pm25 average fine particulate matter in µg/m³, or null if not available
 */
public record Climate(String city, String state, double uvIndexMax, double dewPointC, double humidity, Double pm25) {

    /** EPA UV index "very high" starts at 8. */
    static final double HIGH_UV = 8.0;
    /** NWS: dew points of 65 °F and up feel humid. */
    static final double HUMID_DEW_POINT_C = 18.3;
    /** 40 °F; author's threshold for dry air. */
    static final double DRY_DEW_POINT_C = 4.4;
    static final double DRY_HUMIDITY = 40.0;
    /** EPA annual PM2.5 standard (2024), where the AQI "Moderate" range begins. */
    static final double POLLUTED_PM25 = 9.0;

    public enum Signal { HIGH_UV, HUMID, DRY_AIR, POLLUTED }

    public Set<Signal> signals() {
        Set<Signal> signals = EnumSet.noneOf(Signal.class);
        if (uvIndexMax >= HIGH_UV) {
            signals.add(Signal.HIGH_UV);
        }
        if (dewPointC >= HUMID_DEW_POINT_C) {
            signals.add(Signal.HUMID);
        } else if (dewPointC < DRY_DEW_POINT_C || humidity < DRY_HUMIDITY) {
            signals.add(Signal.DRY_AIR);
        }
        if (pm25 != null && pm25 > POLLUTED_PM25) {
            signals.add(Signal.POLLUTED);
        }
        return signals;
    }

    public boolean has(Signal signal) {
        return signals().contains(signal);
    }
}
