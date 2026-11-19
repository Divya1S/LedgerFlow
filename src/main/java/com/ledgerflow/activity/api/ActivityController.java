package com.ledgerflow.activity.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.common.web.PageCursor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own audit trail: every sign-in and every change they made,
 * newest first. It reads the same append-only audit_logs rows the platform
 * keeps for itself, so what the user sees is the record, not a copy of it.
 *
 * Only rows the caller acted in are returned, and only the action, time
 * and reference. Before/after state stays internal.
 */
@RestController
@RequestMapping("/api/v1/activity")
@Tag(name = "Activity", description = "Your sign-ins and account changes")
public class ActivityController {

    private final JdbcClient jdbc;

    public ActivityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ActivityItem(UUID id, String action, String summary, String category,
                               String resourceType, String resourceId, String note,
                               OffsetDateTime occurredAt) {
    }

    public record ActivityPage(List<ActivityItem> items, String nextCursor) {
    }

    @GetMapping
    @Operation(summary = "Your recent activity, newest first")
    ActivityPage activity(@AuthenticationPrincipal Jwt jwt,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        UUID userId = CurrentUser.from(jwt).id();
        int pageSize = Math.clamp(limit, 1, 200);
        PageCursor c = PageCursor.decodeOrNull(cursor);

        var spec = jdbc.sql("""
                        SELECT id, action, resource_type, resource_id, reason, occurred_at
                        FROM audit_logs
                        WHERE actor_user_id = :userId%s
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :limit
                        """.formatted(c == null ? "" : " AND (occurred_at, id) < (:cts, :cid)"))
                .param("userId", userId)
                .param("limit", pageSize);
        if (c != null) {
            spec = spec.param("cts", c.time()).param("cid", c.id());
        }
        List<ActivityItem> items = spec.query((rs, n) -> {
            String action = rs.getString("action");
            return new ActivityItem(
                    rs.getObject("id", UUID.class), action, summary(action), category(action),
                    rs.getString("resource_type"), rs.getString("resource_id"), rs.getString("reason"),
                    rs.getObject("occurred_at", OffsetDateTime.class));
        }).list();

        String nextCursor = null;
        if (items.size() == pageSize) {
            ActivityItem last = items.getLast();
            nextCursor = new PageCursor(last.occurredAt(), last.id()).encode();
        }
        return new ActivityPage(items, nextCursor);
    }

    /** Plain words for an audit action code. Unknown codes are shown as written. */
    static String summary(String action) {
        return switch (action) {
            case "USER_REGISTERED" -> "Created your LedgerFlow profile";
            case "USER_LOGIN" -> "Signed in";
            case "PASSWORD_CHANGED" -> "Changed your password";
            case "PROFILE_UPDATED" -> "Updated your profile";
            case "ACCOUNT_OPENED" -> "Opened an account";
            case "ACCOUNT_RENAMED" -> "Renamed an account";
            case "ACCOUNT_FROZEN" -> "Froze an account";
            case "ACCOUNT_UNFROZEN" -> "Unfroze an account";
            case "ACCOUNT_CLOSED" -> "Closed an account";
            case "DEPOSIT_COMPLETED" -> "Added money";
            case "WITHDRAWAL_COMPLETED" -> "Withdrew money";
            case "TRANSFER_COMPLETED" -> "Sent a transfer";
            case "TRANSFER_HELD" -> "Sent a transfer that was put on hold";
            case "TRANSFER_HOLD_CANCELLED" -> "Cancelled a transfer on hold";
            case "PAYMENT_COMPLETED" -> "Paid a merchant";
            case "REFUND_COMPLETED" -> "Refunded a payment";
            case "RECIPIENT_ADDED" -> "Saved a recipient";
            case "RECIPIENT_REMOVED" -> "Removed a recipient";
            case "SCHEDULE_CREATED" -> "Scheduled a transfer";
            case "SCHEDULE_PAUSED" -> "Paused a scheduled transfer";
            case "SCHEDULE_RESUMED" -> "Resumed a scheduled transfer";
            case "SCHEDULE_CANCELLED" -> "Cancelled a scheduled transfer";
            case "MONEY_REQUEST_CREATED" -> "Requested money";
            case "MONEY_REQUEST_CANCELLED" -> "Cancelled a money request";
            case "MONEY_REQUEST_PAID" -> "Paid a money request";
            case "DISPUTE_OPENED" -> "Disputed a payment";
            case "DISPUTE_UNDER_REVIEW" -> "Declined a dispute";
            case "DISPUTE_RESOLVED_CUSTOMER" -> "Refunded a disputed payment";
            case "DISPUTE_RESOLVED_MERCHANT" -> "Closed a dispute without a refund";
            default -> {
                String words = action.replace('_', ' ').toLowerCase();
                yield Character.toUpperCase(words.charAt(0)) + words.substring(1);
            }
        };
    }

    /** Groups actions for the filter chips and icons on the activity screen. */
    static String category(String action) {
        if (action.startsWith("USER_") || action.startsWith("PASSWORD") || action.startsWith("PROFILE")) {
            return "SECURITY";
        }
        if (action.startsWith("ACCOUNT_") || action.startsWith("RECIPIENT_")) {
            return "ACCOUNT";
        }
        return "MONEY";
    }
}
