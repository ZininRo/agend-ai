package com.example.agend_ai.api;

import com.example.agend_ai.common.DefaultUser;
import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ItemsApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    ItemRepository items;
    @Autowired
    UserRepository users;

    @BeforeEach
    void clean() {
        items.deleteAll();
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").value("UP"));
    }

    @Test
    void missingParamsGiveRequestInvalid() throws Exception {
        mvc.perform(get("/api/items"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"))
                .andExpect(jsonPath("$.error.fallback").value("reload"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.request_id").isNotEmpty());
    }

    @Test
    void rangeLongerThan62DaysIsRejected() throws Exception {
        mvc.perform(get("/api/items").param("from", "2026-01-01").param("to", "2026-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
    }

    @Test
    void unknownTypeIsRejected() throws Exception {
        mvc.perform(get("/api/items").param("from", "2026-10-01").param("to", "2026-10-31").param("type", "foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
    }

    @Test
    void returnsItemsByPrimaryDateSortedWithUserOffset() throws Exception {
        save(ItemType.MEETING, "Встреча с командой", Instant.parse("2026-10-24T12:00:00Z"), null);   // 15:00 MSK
        save(ItemType.TASK, "Отправить отчёт", null, Instant.parse("2026-10-25T20:59:00Z"));          // 25.10 23:59 MSK
        save(ItemType.REMINDER, "Позвонить Ивану", Instant.parse("2026-11-05T07:00:00Z"), null);      // вне периода

        mvc.perform(get("/api/items").param("from", "2026-10-24").param("to", "2026-10-25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Встреча с командой"))
                .andExpect(jsonPath("$.items[0].type").value("meeting"))
                .andExpect(jsonPath("$.items[0].start_at").value("2026-10-24T15:00:00+03:00"))
                .andExpect(jsonPath("$.items[1].type").value("task"))
                .andExpect(jsonPath("$.items[1].due_at").value("2026-10-25T23:59:00+03:00"))
                .andExpect(jsonPath("$.items[1].status").value("todo"));
    }

    @Test
    void typeFilterWorks() throws Exception {
        save(ItemType.MEETING, "Встреча", Instant.parse("2026-10-24T12:00:00Z"), null);
        save(ItemType.TASK, "Задача", null, Instant.parse("2026-10-24T20:59:00Z"));

        mvc.perform(get("/api/items").param("from", "2026-10-24").param("to", "2026-10-24").param("type", "task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Задача"));
    }

    @Test
    void timezoneChangesDayBoundaries() throws Exception {
        // 24.10 21:30 UTC = 25.10 00:30 MSK
        save(ItemType.MEETING, "Ночная встреча", Instant.parse("2026-10-24T21:30:00Z"), null);

        mvc.perform(get("/api/items").param("from", "2026-10-24").param("to", "2026-10-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(get("/api/items").param("from", "2026-10-24").param("to", "2026-10-24").param("timezone", "UTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].start_at").value("2026-10-24T21:30:00Z"));
    }

    private void save(ItemType type, String title, Instant startAt, Instant dueAt) {
        Item item = new Item();
        item.setUser(users.getReferenceById(DefaultUser.ID));
        item.setType(type);
        item.setTitle(title);
        item.setStartAt(startAt);
        item.setDueAt(dueAt);
        items.save(item);
    }
}
