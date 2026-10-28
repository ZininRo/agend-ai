package com.example.agend_ai.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PushScheduler {
    private static final Logger log = LoggerFactory.getLogger(PushScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PushSender sender;
    private final ObjectMapper mapper;

    public PushScheduler(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
                         PushSender sender, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.sender = sender;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${push.scan-delay-ms:15000}")
    public void run() {
        if (!sender.enabled()) return;
        try {
            // Only a 5-minute window: do not spam for old items after first deployment.
            jdbc.update("""
                    INSERT INTO push_deliveries(id, subscription_id, item_id, scheduled_at)
                    SELECT gen_random_uuid(), s.id, i.id,
                      CASE WHEN i.type='MEETING' THEN i.start_at - interval '15 minutes' ELSE i.start_at END
                    FROM items i JOIN push_subscriptions s ON s.user_id=i.user_id
                    WHERE i.deleted_at IS NULL AND i.status='TODO'
                      AND i.type IN ('MEETING','REMINDER') AND i.start_at IS NOT NULL
                      AND (CASE WHEN i.type='MEETING' THEN i.start_at - interval '15 minutes' ELSE i.start_at END)
                          BETWEEN now() - interval '5 minutes' AND now()
                    ON CONFLICT (subscription_id,item_id,scheduled_at) DO NOTHING
                    """);
            // One delivery per transaction. Row locks prevent concurrent schedulers from claiming it.
            for (int i = 0; i < 50; i++) {
                Boolean found = tx.execute(status -> processOne());
                if (!Boolean.TRUE.equals(found)) break;
            }
        } catch (Exception e) {
            log.error("Push scheduler failed", e);
        }
    }

    private boolean processOne() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.id, d.attempts, d.scheduled_at, s.endpoint, s.p256dh, s.auth_secret,
                       i.title, i.type, i.status AS item_status, i.deleted_at, i.start_at
                FROM push_deliveries d
                JOIN push_subscriptions s ON s.id=d.subscription_id
                JOIN items i ON i.id=d.item_id
                WHERE d.status='PENDING' AND d.next_attempt_at<=now()
                ORDER BY d.next_attempt_at, d.created_at
                LIMIT 1 FOR UPDATE OF d SKIP LOCKED
                """);
        if (rows.isEmpty()) return false;
        Map<String,Object> row = rows.get(0);
        UUID id = (UUID) row.get("id");
        if (row.get("start_at") == null) {
            jdbc.update("UPDATE push_deliveries SET status='CANCELLED' WHERE id=?", id);
            return true;
        }
        Instant start = ((Timestamp)row.get("start_at")).toInstant();
        Instant expected = "MEETING".equals(row.get("type")) ? start.minusSeconds(900) : start;
        Instant scheduled = ((Timestamp)row.get("scheduled_at")).toInstant();
        if (!"TODO".equals(row.get("item_status")) || row.get("deleted_at") != null || !expected.equals(scheduled)) {
            jdbc.update("UPDATE push_deliveries SET status='CANCELLED' WHERE id=?", id);
            return true;
        }
        int attempts = ((Number)row.get("attempts")).intValue() + 1;
        try {
            String title = "MEETING".equals(row.get("type")) ? "Встреча через 15 минут" : "Напоминание Agend AI";
            String payload = mapper.writeValueAsString(Map.of("title", title, "body", row.get("title"), "url", "/"));
            int code = sender.send((String)row.get("endpoint"), (String)row.get("p256dh"),
                    (String)row.get("auth_secret"), payload);
            if (code >= 200 && code < 300) {
                jdbc.update("UPDATE push_deliveries SET status='SENT', attempts=? WHERE id=?", attempts, id);
            } else if (code == 404 || code == 410) {
                jdbc.update("DELETE FROM push_subscriptions WHERE endpoint=?", row.get("endpoint"));
            } else {
                retry(id, attempts, "Push service HTTP " + code);
            }
        } catch (Exception e) {
            // Do not log endpoint or encryption secrets.
            log.warn("Web Push delivery failed: {}", e.getClass().getSimpleName());
            retry(id, attempts, e.getClass().getSimpleName());
        }
        return true;
    }

    private void retry(UUID id, int attempts, String error) {
        if (attempts >= 5) {
            jdbc.update("UPDATE push_deliveries SET status='FAILED', attempts=?, last_error=? WHERE id=?", attempts, error, id);
        } else {
            int seconds = Math.min(300, 15 * (1 << (attempts - 1)));
            jdbc.update("""
                    UPDATE push_deliveries SET attempts=?, last_error=?, next_attempt_at=now() + (? * interval '1 second')
                    WHERE id=?
                    """, attempts, error, seconds, id);
        }
    }
}
