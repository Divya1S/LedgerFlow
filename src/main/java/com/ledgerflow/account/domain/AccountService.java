package com.ledgerflow.account.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ledgerflow.account.persistence.AccountRepository;
import com.ledgerflow.common.audit.AuditLogger;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.id.Uuid7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD");
    private static final Set<String> USER_CREATABLE_TYPES = Set.of("USER_WALLET", "MERCHANT");

    private final AccountRepository accounts;
    private final AuditLogger audit;

    public AccountService(AccountRepository accounts, AuditLogger audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Account openAccount(UUID userId, String type, String currency, String name) {
        if (!USER_CREATABLE_TYPES.contains(type)) {
            throw ApiException.unprocessable("UNSUPPORTED_ACCOUNT_TYPE",
                    "Account type must be one of " + USER_CREATABLE_TYPES);
        }
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY",
                    "Currency must be one of " + SUPPORTED_CURRENCIES);
        }
        Account account = new Account(Uuid7.generate(), userId, type, currency, "ACTIVE", name, null);
        // User accounts get a hard floor of 0: the CHECK constraint on
        // account_balances is the database-level double-spend backstop.
        accounts.insert(account, 0);
        audit.record(userId, "ACCOUNT_OPENED", "account", account.id().toString(),
                null, "{\"type\":\"" + type + "\",\"currency\":\"" + currency + "\"}", "account", null);
        return account;
    }

    public Account requireOwnedAccount(UUID accountId, UUID userId, boolean admin) {
        Account account = accounts.findById(accountId)
                // 404 (not 403) for other users' accounts: don't leak account existence
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
        if (!admin && !userId.equals(account.userId())) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
        }
        return account;
    }

    public List<Account> listOwnAccounts(UUID userId) {
        return accounts.findByUserId(userId);
    }

    public Balance balanceOf(UUID accountId, UUID userId, boolean admin) {
        requireOwnedAccount(accountId, userId, admin);
        return accounts.findBalance(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    @Transactional
    public Account closeAccount(UUID accountId, UUID userId, boolean admin) {
        Account account = requireOwnedAccount(accountId, userId, admin);
        if ("CLOSED".equals(account.status())) {
            return account;
        }
        Balance balance = accounts.findBalance(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
        if (balance.balance() != 0) {
            throw ApiException.conflict("ACCOUNT_NOT_EMPTY",
                    "Account balance must be zero before closing");
        }
        accounts.updateStatus(accountId, "CLOSED");
        audit.record(userId, "ACCOUNT_CLOSED", "account", accountId.toString(),
                "{\"status\":\"" + account.status() + "\"}", "{\"status\":\"CLOSED\"}", "account", null);
        return accounts.findById(accountId).orElseThrow();
    }
}
