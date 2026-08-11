package com.ledgerflow.common.audit;

import java.util.UUID;

import com.ledgerflow.common.id.Uuid7;
import com.ledgerflow.common.web.CorrelationIdFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes append-only audit records. Called inside the same database
 * transaction as the audited change so an audit row exists exactly when the
 * change committed (an audit trail that can disagree with the data it audits
 * is worse than none).
 */
@Component
public class AuditLogger {

    private final JdbcClient jdbc;

    public AuditLogger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID actorUserId, String action, String resourceType, String resourceId,
                       String previousStateJson, String newStateJson, String service, String reason) {
        jdbc.sql("""
                        INSERT INTO audit_logs
                            (id, actor_user_id, actor_type, action, resource_type, resource_id,
                             previous_state, new_state, correlation_id, service, reason)
                        VALUES
                            (:id, :actorUserId, :actorType, :action, :resourceType, :resourceId,
                             CAST(:previousState AS jsonb), CAST(:newState AS jsonb), :correlationId, :service, :reason)
                        """)
                .param("id", Uuid7.generate())
                .param("actorUserId", actorUserId)
                .param("actorType", actorUserId == null ? "SYSTEM" : "USER")
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("previousState", previousStateJson)
                .param("newState", newStateJson)
                .param("correlationId", CorrelationIdFilter.current())
                .param("service", service)
                .param("reason", reason)
                .update();
    }
}
