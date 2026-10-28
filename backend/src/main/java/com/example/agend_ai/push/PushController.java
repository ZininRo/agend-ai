package com.example.agend_ai.push;

import com.example.agend_ai.common.DefaultUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/push")
public class PushController {
    private final JdbcTemplate jdbc;
    private final PushSender sender;

    public PushController(JdbcTemplate jdbc, PushSender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
    }

    public record Keys(@NotBlank String p256dh, @NotBlank String auth) {}
    public record Subscription(@NotBlank String endpoint, @NotNull @Valid Keys keys) {}

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", sender.enabled(), "public_key", sender.publicKey());
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@RequestBody @Valid Subscription body) {
        if (!sender.enabled()) throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Web Push is not configured");
        if (!body.endpoint().startsWith("https://") || body.endpoint().length() > 2048
                || body.keys().p256dh().length() > 512 || body.keys().auth().length() > 512) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid subscription");
        }
        jdbc.update("""
                INSERT INTO push_subscriptions(id,user_id,endpoint,p256dh,auth_secret)
                VALUES (?,?,?,?,?) ON CONFLICT(endpoint) DO UPDATE
                SET p256dh=EXCLUDED.p256dh, auth_secret=EXCLUDED.auth_secret, user_id=EXCLUDED.user_id
                """, UUID.randomUUID(), DefaultUser.ID, body.endpoint(), body.keys().p256dh(), body.keys().auth());
    }

    @PostMapping("/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestBody @Valid Subscription body) {
        jdbc.update("DELETE FROM push_subscriptions WHERE endpoint=? AND user_id=?", body.endpoint(), DefaultUser.ID);
    }
}
