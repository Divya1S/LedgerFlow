package com.ledgerflow.account.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ledgerflow.common.db.PartyLabel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RecipientRepository {

    private final JdbcClient jdbc;

    public RecipientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record RecipientRow(UUID id, UUID accountId, String nickname, String accountType,
                               String accountStatus, String currency, String displayName,
                               String category, OffsetDateTime createdAt) {
    }

    private static final String SELECT = """
            SELECT r.id, r.account_id, r.nickname, r.created_at,
                   a.type, a.status, a.currency, a.category,
                   %s AS display_name
            FROM recipients r
            JOIN accounts a ON a.id = r.account_id
            LEFT JOIN users u ON u.id = a.user_id
            """.formatted(PartyLabel.sql("a", "u", "owner"));

    public void insert(UUID id, UUID ownerUserId, UUID accountId, String nickname) {
        jdbc.sql("""
                        INSERT INTO recipients (id, owner_user_id, account_id, nickname)
                        VALUES (:id, :owner, :accountId, :nickname)
                        """)
                .param("id", id)
                .param("owner", ownerUserId)
                .param("accountId", accountId)
                .param("nickname", nickname)
                .update();
    }

    public List<RecipientRow> listForOwner(UUID ownerUserId) {
        return jdbc.sql(SELECT + " WHERE r.owner_user_id = :owner ORDER BY lower(r.nickname), r.id")
                .param("owner", ownerUserId)
                .query(this::map)
                .list();
    }

    public Optional<RecipientRow> find(UUID id, UUID ownerUserId) {
        return jdbc.sql(SELECT + " WHERE r.id = :id AND r.owner_user_id = :owner")
                .param("id", id)
                .param("owner", ownerUserId)
                .query(this::map)
                .optional();
    }

    public int delete(UUID id, UUID ownerUserId) {
        return jdbc.sql("DELETE FROM recipients WHERE id = :id AND owner_user_id = :owner")
                .param("id", id)
                .param("owner", ownerUserId)
                .update();
    }

    private RecipientRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RecipientRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("nickname"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("currency"),
                rs.getString("display_name"),
                rs.getString("category"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
