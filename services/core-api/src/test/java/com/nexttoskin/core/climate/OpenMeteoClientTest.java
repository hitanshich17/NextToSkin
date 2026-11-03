package com.nexttoskin.core.climate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenMeteoClientTest {

    private static final OpenMeteoProperties PROPS = new OpenMeteoProperties("https://geo.test/search",
            "https://wx.test/forecast", "https://air.test/air-quality", Duration.ofSeconds(1), Duration.ofSeconds(1),
            Duration.ofHours(24));

    private static final String PORTLANDS = """
            {"results": [
              {"name": "Portland", "admin1": "Oregon", "latitude": 45.52, "longitude": -122.68},
              {"name": "Portland", "admin1": "Maine", "latitude": 43.66, "longitude": -70.26}]}
            """;

    private MockRestServiceServer server;
    private OpenMeteoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenMeteoClient(builder.build(), PROPS);
    }

    private void geocoding(String body) {
        server.expect(requestTo(startsWith("https://geo.test/search")))
                .andExpect(queryParam("countryCode", "US"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void cityAloneMeansTheMostPopulousOne() {
        geocoding(PORTLANDS);
        assertThat(client.find("Portland")).get().extracting(OpenMeteoClient.Place::state).isEqualTo("Oregon");
    }

    @Test
    void stateAbbreviationPicksTheRightCity() {
        geocoding(PORTLANDS);
        assertThat(client.find("Portland, ME")).get().extracting(OpenMeteoClient.Place::state).isEqualTo("Maine");
    }

    @Test
    void unknownCityIsEmpty() {
        geocoding("{}");
        assertThat(client.climate("Atlantis")).isEmpty();
    }

    @Test
    void averagesTheLast30DaysSkippingMissingValues() {
        geocoding(PORTLANDS);
        server.expect(requestTo(startsWith("https://wx.test/forecast")))
                .andExpect(queryParam("past_days", "30"))
                .andRespond(withSuccess("""
                        {"daily": {"uv_index_max": [4.0, 5.0, null], "dew_point_2m_mean": [10.0, 11.0, null],
                                   "relative_humidity_2m_mean": [70.0, 80.0, null]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://air.test/air-quality")))
                .andRespond(withSuccess("""
                        {"hourly": {"pm2_5": [8.0, 9.0, 10.0, null]}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.climate("Portland")).contains(new Climate("Portland", "Oregon", 4.5, 10.5, 75.0, 9.0));
        server.verify();
    }
}
