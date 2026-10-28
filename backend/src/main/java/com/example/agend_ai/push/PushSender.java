package com.example.agend_ai.push;

import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

@Component
public class PushSender {
    private final String publicKey;
    private final PushService service;

    public PushSender(@Value("${push.public-key:}") String publicKey,
                      @Value("${push.private-key:}") String privateKey,
                      @Value("${push.subject:mailto:admin@example.com}") String subject) throws Exception {
        this.publicKey = publicKey;
        if (publicKey.isBlank() || privateKey.isBlank()) {
            this.service = null;
        } else {
            this.service = new PushService(publicKey, privateKey, subject);
        }
    }

    public boolean enabled() { return service != null; }
    public String publicKey() { return enabled() ? publicKey : ""; }

    /** Return HTTP status of the push service (not proof that a device displayed it). */
    public int send(String endpoint, String p256dh, String auth, String payload) throws Exception {
        if (service == null) throw new IllegalStateException("Push is disabled");
        Notification notification = new Notification(endpoint, p256dh, auth, payload.getBytes(StandardCharsets.UTF_8));
        HttpResponse response = service.send(notification);
        return response.getStatusLine().getStatusCode();
    }
}
