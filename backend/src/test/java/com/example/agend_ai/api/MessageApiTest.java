package com.example.agend_ai.api;

import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.MessageRepository;
import com.example.agend_ai.repository.ChatSessionRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class MessageApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static WireMockServer wireMockServer;

    @Autowired
    MockMvc mvc;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    ChatSessionRepository sessionRepository;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("llm.base-url", () -> wireMockServer.baseUrl());
    }

    @BeforeEach
    void clean() {
        messageRepository.deleteAll();
        sessionRepository.deleteAll();
        itemRepository.deleteAll();
        wireMockServer.resetAll();
    }

    @Test
    void testCreateReminderScenarioT1() throws Exception {
        String lmmJsonResponse = """
                {
                  "intent": "create_item",
                  "confidence": 0.98,
                  "slots": {
                    "item_type": "reminder",
                    "title": "позвонить Ивану",
                    "date_expr": "завтра",
                    "time_expr": "10:00"
                  }
                }
                """;

        stubFor(post(urlEqualTo("/foundationModels/v1/completion"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(formatYandexGptResponse(lmmJsonResponse))));

        String requestBody = """
                {
                  "session_id": "%s",
                  "text": "Напомни мне завтра в 10 утра позвонить Ивану",
                  "timezone": "Europe/Moscow",
                  "source": "text"
                }
                """.formatted(UUID.randomUUID());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("done"))
                .andExpect(jsonPath("$.intent").value("create_item"))
                .andExpect(jsonPath("$.reply_text").value("Готово: напомню завтра позвонить ивану."))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("reminder"))
                .andExpect(jsonPath("$.data_changed").value(true));
    }

    private String formatYandexGptResponse(String textJson) {
        return """
                {
                  "result": {
                    "alternatives": [
                      {
                        "message": {
                          "role": "assistant",
                          "text": %s
                        },
                        "status": "ALTERNATIVE_STATUS_FINAL"
                      }
                    ],
                    "usage": {
                      "inputTextTokens": "10",
                      "completionTokens": "10",
                      "totalTokens": "20"
                    }
                  }
                }
                """.formatted(objectToJsonString(textJson));
    }

    private String objectToJsonString(String val) {
        return "\"" + val.replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}