package com.nexttoskin.core.feedback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexttoskin.core.feedback.Feedback.Reason;
import com.nexttoskin.core.feedback.Feedback.Verdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(FeedbackController.class)
class FeedbackControllerTest {

    private static final UUID CLIENT = UUID.fromString("7d4f0c2e-5b7a-4a51-9c1e-0a2b3c4d5e6f");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FeedbackRepository repository;

    private ResultActions putFeedback(String productId, String json) throws Exception {
        return mockMvc.perform(put("/api/v1/feedback/" + productId).header("X-Client-Id", CLIENT.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void savesADislikeWithItsReason() throws Exception {
        when(repository.save(eq(CLIENT), any(), eq(7L))).thenReturn(true);

        putFeedback("rich-cream", """
                {"verdict": "DISLIKED", "reason": "TEXTURE", "sessionId": 7}
                """).andExpect(status().isNoContent());
        verify(repository).save(CLIENT, new Feedback("rich-cream", Verdict.DISLIKED, Reason.TEXTURE), 7L);
    }

    @Test
    void unknownProductIs404() throws Exception {
        when(repository.save(eq(CLIENT), any(), isNull())).thenReturn(false);

        putFeedback("no-such-product", """
                {"verdict": "LIKED"}
                """).andExpect(status().isNotFound());
    }

    @Test
    void aLikeCannotHaveAReason() throws Exception {
        putFeedback("rich-cream", """
                {"verdict": "LIKED", "reason": "TEXTURE"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("only a dislike has a reason"));
        verifyNoInteractions(repository);
    }

    @Test
    void missingOrInvalidClientIdIs400() throws Exception {
        mockMvc.perform(get("/api/v1/feedback")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/feedback").header("X-Client-Id", "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void listsAndUndoes() throws Exception {
        when(repository.findAll(CLIENT)).thenReturn(List.of(new Feedback("rich-cream", Verdict.LIKED, null)));

        mockMvc.perform(get("/api/v1/feedback").header("X-Client-Id", CLIENT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value("rich-cream"))
                .andExpect(jsonPath("$[0].verdict").value("LIKED"));
        mockMvc.perform(delete("/api/v1/feedback/rich-cream").header("X-Client-Id", CLIENT.toString()))
                .andExpect(status().isNoContent());
        verify(repository).delete(CLIENT, "rich-cream");
    }
}
