package com.meterly.ingestion.usage;

import com.meterly.ingestion.security.ApiKeyFilter;
import com.meterly.ingestion.security.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsageEventController.class)
@Import({SecurityConfiguration.class, ApiKeyFilter.class})
class UsageEventControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaTemplate<String, UsageEventMessage> kafkaTemplate;

    @Test
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(post("/v1/usage-events")
                        .contentType("application/json")
                        .content("{\"idempotency_key\":\"10000000-0000-0000-0000-000000000001\",\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidUsageEvent() throws Exception {
        when(kafkaTemplate.send(eq("usage-events"), any(String.class), any(UsageEventMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(post("/v1/usage-events")
                        .header("X-Api-Key", "meterly-poc-api-key")
                        .contentType("application/json")
                        .content("{\"idempotency_key\":\"10000000-0000-0000-0000-000000000001\",\"quantity\":1}"))
                .andExpect(status().isAccepted());
    }
}

