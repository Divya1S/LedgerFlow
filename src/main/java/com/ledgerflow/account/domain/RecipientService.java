package com.ledgerflow.account.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.account.persistence.RecipientRepository;
import com.ledgerflow.account.persistence.RecipientRepository.RecipientRow;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved recipients: accounts a user sends money to more than once. Saving
 * one never moves money and gives no access to the account; it only
 * replaces typing an account id with picking a name.
 */
@Service
public class RecipientService {

    private final RecipientRepository recipients;
    private final AccountService accounts;
    private final AuditLogger audit;

    public RecipientService(RecipientRepository recipients, AccountService accounts, AuditLogger audit) {
        this.recipients = recipients;
        this.accounts = accounts;
        this.audit = audit;
    }

    public List<RecipientRow> list(UUID ownerUserId) {
        return recipients.listForOwner(ownerUserId);
    }

    @Transactional
    public RecipientRow add(UUID ownerUserId, UUID accountId, String nickname) {
        Payee payee = accounts.lookupPayee(accountId, ownerUserId);
        if (payee.own()) {
            throw ApiException.unprocessable("OWN_ACCOUNT",
                    "Your own accounts are always available; there is no need to save them");
        }
        if ("CLOSED".equals(payee.status())) {
            throw ApiException.conflict("ACCOUNT_NOT_ACTIVE", "This account is closed and cannot receive money");
        }
        String label = nickname == null || nickname.isBlank() ? payee.displayName() : nickname.strip();
        UUID id = Uuid7.generate();
        try {
            recipients.insert(id, ownerUserId, accountId, label);
        } catch (DuplicateKeyException e) {
            // The unique constraint decides, not a check-then-insert that
            // two quick clicks could both pass.
            throw ApiException.conflict("RECIPIENT_EXISTS", "This account is already in your recipients");
        }
        audit.recordChange(ownerUserId, "RECIPIENT_ADDED", "recipient", id.toString(),
                null, Map.of("accountId", accountId.toString(), "nickname", label), "account", null);
        return recipients.find(id, ownerUserId).orElseThrow();
    }

    @Transactional
    public void remove(UUID ownerUserId, UUID recipientId) {
        if (recipients.delete(recipientId, ownerUserId) == 0) {
            throw ApiException.notFound("RECIPIENT_NOT_FOUND", "Recipient not found");
        }
        audit.record(ownerUserId, "RECIPIENT_REMOVED", "recipient", recipientId.toString(),
                null, null, "account", null);
    }
}
