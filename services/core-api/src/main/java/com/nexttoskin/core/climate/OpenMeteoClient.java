package com.nexttoskin.core.climate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads a US city's last 30 days of weather and air quality from Open-Meteo (free, no API key, non-commercial
 * use; data CC BY 4.0, so the results page credits Open-Meteo).
 */
@Component
public class OpenMeteoClient {

    static final int DAYS = 30;

    private final RestClient http;
    private final OpenMeteoProperties props;

    @Autowired // the constructor Spring uses; the other one is for tests
    public OpenMeteoClient(OpenMeteoProperties props) {
        this(RestClient.builder().requestFactory(timeouts(props)).build(), props);
    }

    OpenMeteoClient(RestClient http, OpenMeteoProperties props) {
        this.http = http;
        this.props = props;
    }

    private static SimpleClientHttpRequestFactory timeouts(OpenMeteoProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return factory;
    }

    /** The climate for "Austin", "Austin, TX" or "Austin, Texas", or empty if the city isn't found in the US. */
    public Optional<Climate> climate(String cityAndState) {
        return find(cityAndState).map(this::climate);
    }

    record Place(String name, String state, double latitude, double longitude) {
    }

    Optional<Place> find(String cityAndState) {
        String[] parts = cityAndState.split(",", 2);
        String city = parts[0].strip();
        String state = parts.length > 1 ? UsStates.fullName(parts[1].strip()) : null;
        JsonNode results = http.get()
                .uri(props.geocodingUrl() + "?name={name}&count=10&countryCode=US&language=en", city)
                .retrieve().body(JsonNode.class)
                .path("results");
        // Results come most populous first, so "Portland" alone means Portland, Oregon.
        return StreamSupport.stream(results.spliterator(), false)
                .filter(r -> state == null || state.equalsIgnoreCase(r.path("admin1").asText()))
                .findFirst()
                .map(r -> new Place(r.path("name").asText(), r.path("admin1").asText(),
                        r.path("latitude").asDouble(), r.path("longitude").asDouble()));
    }

    private Climate climate(Place place) {
        Map<String, Object> at = Map.of("lat", place.latitude(), "lon", place.longitude(), "days", DAYS);
        JsonNode daily = http.get()
                .uri(props.forecastUrl() + "?latitude={lat}&longitude={lon}&past_days={days}&forecast_days=1"
                        + "&daily=uv_index_max,dew_point_2m_mean,relative_humidity_2m_mean&timezone=auto", at)
                .retrieve().body(JsonNode.class)
                .path("daily");
        JsonNode hourly = http.get()
                .uri(props.airQualityUrl() + "?latitude={lat}&longitude={lon}&past_days={days}&forecast_days=1"
                        + "&hourly=pm2_5", at)
                .retrieve().body(JsonNode.class)
                .path("hourly");
        return new Climate(place.name(), place.state(),
                average(daily.path("uv_index_max")).orElseThrow(),
                average(daily.path("dew_point_2m_mean")).orElseThrow(),
                average(daily.path("relative_humidity_2m_mean")).orElseThrow(),
                average(hourly.path("pm2_5")).orElse(null));
    }

    /** Average of the non-null numbers, rounded to one decimal; the newest days can still be null. */
    private static Optional<Double> average(JsonNode values) {
        var stats = StreamSupport.stream(values.spliterator(), false)
                .filter(JsonNode::isNumber)
                .mapToDouble(JsonNode::asDouble)
                .summaryStatistics();
        return stats.getCount() == 0 ? Optional.empty()
                : Optional.of(Math.round(stats.getAverage() * 10) / 10.0);
    }
}
