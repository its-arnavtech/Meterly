package com.meterly.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification history for the calling tenant (TENANT_ADMIN only, enforced by the security chain).
 * The query has no tenant filter of its own: the RLS policy on {@code notification} scopes it to the
 * tenant bound from the JWT for this transaction.
 */
@RestController
public class NotificationController {

    static final int MAX_ITEMS = 100;

    private final JdbcClient jdbc;

    public NotificationController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/notifications")
    @Transactional(readOnly = true)
    public NotificationList list() {
        List<NotificationView> items = jdbc.sql("""
                        SELECT id, type, recipient, subject, status, created_at
                        FROM notification
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("limit", MAX_ITEMS)
                .query((rs, row) -> new NotificationView(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        return new NotificationList(items);
    }

    public record NotificationList(List<NotificationView> items) {
    }

    public record NotificationView(UUID id, String type, String recipient, String subject,
                                   String status, Instant createdAt) {
    }
}
