package com.nexttoskin.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiServiceClientTest {

    private MockRestServiceServer server;
    private AiServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiServiceClient(builder.build());
    }

    @Test
    void returnsTheExplanation() {
        server.expect(requestTo("http://ai.test/explanations")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"text\": \"Because...\"}", MediaType.APPLICATION_JSON));
        assertThat(client.explain(Map.of("skinType", "dry"))).contains("Because...");
    }

    @Test
    void noExplanationWhenTheAiServiceSaysNo() {
        server.expect(requestTo("http://ai.test/explanations")).andRespond(withServiceUnavailable());
        assertThat(client.explain(Map.of())).isEmpty();
    }
}
