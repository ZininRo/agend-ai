package com.example.agend_ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agend_ai.entity.Item;
import com.example.agend_ai.entity.enums.ItemType;
import com.example.agend_ai.repository.ItemRepository;
import com.example.agend_ai.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.test.context.support.WithAnonymousUser;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthApiTest extends DatabaseTestSupport {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired UserRepository users;
  @Autowired ItemRepository items;

  @Test
  @WithAnonymousUser
  void registerLoginRefreshLogoutAndDuplicateEmail() throws Exception {
    String email = "p1-" + UUID.randomUUID() + "@example.com";
    MvcResult registered =
        mvc.perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        mapper.writeValueAsBytes(
                            Map.of(
                                "email", email,
                                "password", "correct-horse-42",
                                "display_name", "P1 User",
                                "timezone", "Europe/Vilnius"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.display_name").value("P1 User"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("agendai_refresh=")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("HttpOnly")))
            .andReturn();

    Cookie firstCookie = refreshCookie(registered.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    JsonNode registeredJson = mapper.readTree(registered.getResponse().getContentAsByteArray());
    String firstAccessToken = registeredJson.path("access_token").asText();

    mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstAccessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.display_name").value("P1 User"));

    mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email.toUpperCase(), "password", "another-password"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ACCOUNT_EXISTS"));

    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("email", email, "password", "wrong-password"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_INVALID"));

    MvcResult refreshed =
        mvc.perform(post("/api/auth/refresh").cookie(firstCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andReturn();
    Cookie secondCookie = refreshCookie(refreshed.getResponse().getHeader(HttpHeaders.SET_COOKIE));
    assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

    mvc.perform(post("/api/auth/logout").cookie(secondCookie))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/auth/refresh").cookie(secondCookie))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_SESSION_EXPIRED"));
  }

  @Test
  @WithAnonymousUser
  void protectedApiRequiresAuthentication() throws Exception {
    mvc.perform(
            get("/api/items")
                .param("from", "2026-11-01")
                .param("to", "2026-11-30")
                .param("timezone", "Europe/Vilnius"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
  }

  @Test
  @WithAnonymousUser
  void bearerTokenIsolatesItemsBetweenAccounts() throws Exception {
    Account a = register("a-" + UUID.randomUUID() + "@example.com");
    Account b = register("b-" + UUID.randomUUID() + "@example.com");

    Item item = new Item();
    item.setUser(users.getReferenceById(UUID.fromString(a.userId())));
    item.setType(ItemType.TASK);
    item.setTitle("Секретная задача A");
    item.setDescription("Видна только владельцу");
    item.setDueAt(Instant.parse("2026-11-10T20:59:00Z"));
    items.saveAndFlush(item);

    mvc.perform(
            get("/api/items")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + a.accessToken())
                .param("from", "2026-11-01")
                .param("to", "2026-11-30")
                .param("timezone", "Europe/Vilnius"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].title").value("Секретная задача A"));

    mvc.perform(
            get("/api/items")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + b.accessToken())
                .param("from", "2026-11-01")
                .param("to", "2026-11-30")
                .param("timezone", "Europe/Vilnius"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  private Account register(String email) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(Map.of("email", email, "password", "correct-horse-42"))))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode json = mapper.readTree(result.getResponse().getContentAsByteArray());
    return new Account(json.path("user").path("id").asText(), json.path("access_token").asText());
  }

  private static Cookie refreshCookie(String setCookie) {
    assertThat(setCookie).isNotBlank();
    String pair = setCookie.substring(0, setCookie.indexOf(';'));
    int separator = pair.indexOf('=');
    assertThat(separator).isGreaterThan(0);
    return new Cookie(pair.substring(0, separator), pair.substring(separator + 1));
  }

  private record Account(String userId, String accessToken) {}
}
