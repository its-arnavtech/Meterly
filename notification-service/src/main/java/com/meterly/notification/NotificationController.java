package com.meterly.notification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {
    private final JdbcClient jdbc;
    public NotificationController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/v1/notifications")
    @Transactional(readOnly = true)
    public Map<String, List<NotificationView>> list() {
        return Map.of("items", jdbc.sql("""
                SELECT id, type, recipient, subject, status, created_at
                FROM notification ORDER BY created_at DESC LIMIT 100
                """).query((rs, row) -> new NotificationView(rs.getObject("id", UUID.class),
                        rs.getString("type"), rs.getString("recipient"), rs.getString("subject"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant())).list());
    }

    public record NotificationView(UUID id, String type, String recipient, String subject,
                                   String status, Instant createdAt) { }
}
