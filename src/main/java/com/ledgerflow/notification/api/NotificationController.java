package com.ledgerflow.notification.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The in-app inbox: what the notification consumer wrote, newest first,
 * with read state. Reads its own table only.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "Your in-app inbox")
public class NotificationController {

    private final JdbcClient jdbc;

    public NotificationController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record NotificationResponse(UUID id, String type, String title, String body,
                                       boolean read, OffsetDateTime createdAt) {
    }

    public record Inbox(List<NotificationResponse> items, long unreadCount) {
    }

    public record MarkedRead(int updated) {
    }

    @GetMapping
    @Operation(summary = "Your latest notifications and how many are unread")
    Inbox inbox(@AuthenticationPrincipal Jwt jwt,
                @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        UUID userId = CurrentUser.from(jwt).id();
        List<NotificationResponse> items = jdbc.sql("""
                        SELECT id, type, title, body, read_at, created_at
                        FROM notifications
                        WHERE user_id = :userId AND channel = 'IN_APP'
                        ORDER BY created_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", Math.clamp(limit, 1, 100))
                .query((rs, n) -> {
                    String type = rs.getString("type");
                    String title = rs.getString("title");
                    String body = rs.getString("body");
                    return new NotificationResponse(
                            rs.getObject("id", UUID.class), type,
                            title != null ? title : fallbackTitle(type),
                            body != null ? body : "",
                            rs.getObject("read_at", OffsetDateTime.class) != null,
                            rs.getObject("created_at", OffsetDateTime.class));
                })
                .list();
        long unread = jdbc.sql("""
                        SELECT count(*) FROM notifications
                        WHERE user_id = :userId AND read_at IS NULL AND channel = 'IN_APP'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
        return new Inbox(items, unread);
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read")
    ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        int updated = jdbc.sql("""
                        UPDATE notifications SET read_at = COALESCE(read_at, now())
                        WHERE id = :id AND user_id = :userId
                        """)
                .param("id", id)
                .param("userId", CurrentUser.from(jwt).id())
                .update();
        if (updated == 0) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every notification as read")
    MarkedRead markAllRead(@AuthenticationPrincipal Jwt jwt) {
        int updated = jdbc.sql("""
                        UPDATE notifications SET read_at = now()
                        WHERE user_id = :userId AND read_at IS NULL
                        """)
                .param("userId", CurrentUser.from(jwt).id())
                .update();
        return new MarkedRead(updated);
    }

    /** Wording for rows written before notifications stored their own text. */
    private static String fallbackTitle(String type) {
        return switch (type) {
            case "PAYMENT_COMPLETED" -> "Payment sent";
            case "REFUND_COMPLETED" -> "Refund received";
            default -> "Account update";
        };
    }
}
