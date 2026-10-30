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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ItemExportApiTest extends DatabaseTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ItemRepository items;
    @Autowired UserRepository users;
    @Autowired com.example.agend_ai.repository.MessageRepository messages;
    @Autowired com.example.agend_ai.repository.ChatSessionRepository sessions;

    @BeforeEach void clean() { messages.deleteAll(); sessions.deleteAll(); items.deleteAll(); }

    @Test void exportsCsvForExcelWithSamePeriodRules() throws Exception {
        save(ItemType.MEETING, "=опасная формула", Instant.parse("2026-10-24T12:00:00Z"), null);
        mvc.perform(get("/api/items/export").param("from", "2026-10-24").param("to", "2026-10-24").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("agendai-items.csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("Тип;Название")))
                .andExpect(content().string(containsString("'=опасная формула")));
    }

    @Test void exportsIcsWithEventAndTodo() throws Exception {
        save(ItemType.MEETING, "Встреча", Instant.parse("2026-10-24T12:00:00Z"), null);
        save(ItemType.TASK, "Отчёт", null, Instant.parse("2026-10-24T20:59:00Z"));
        mvc.perform(get("/api/items/export").param("from", "2026-10-24").param("to", "2026-10-24").param("format", "ics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar"))
                .andExpect(content().string(containsString("BEGIN:VEVENT")))
                .andExpect(content().string(containsString("BEGIN:VTODO")));
    }

    @Test void rejectsUnknownFormat() throws Exception {
        mvc.perform(get("/api/items/export").param("from", "2026-10-24").param("to", "2026-10-24").param("format", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("REQUEST_INVALID"));
    }

    private void save(ItemType type, String title, Instant startAt, Instant dueAt) {
        Item item = new Item();
        item.setUser(users.getReferenceById(DefaultUser.ID));
        item.setType(type); item.setTitle(title); item.setStartAt(startAt); item.setDueAt(dueAt);
        items.save(item);
    }
}
