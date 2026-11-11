package com.ledgerflow.account.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public static final Set<String> MERCHANT_CATEGORIES = Set.of(
            "GROCERIES", "DINING", "SHOPPING", "TRAVEL", "TRANSPORT",
            "ENTERTAINMENT", "UTILITIES", "HEALTH", "SERVICES", "OTHER");

    private final AccountRepository accounts;
    private final AuditLogger audit;

    public AccountService(AccountRepository accounts, AuditLogger audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Account openAccount(UUID userId, String type, String currency, String name) {
        return openAccount(userId, type, currency, name, null);
    }

    @Transactional
    public Account openAccount(UUID userId, String type, String currency, String name, String category) {
        if (!USER_CREATABLE_TYPES.contains(type)) {
            throw ApiException.unprocessable("UNSUPPORTED_ACCOUNT_TYPE",
                    "Account type must be one of " + USER_CREATABLE_TYPES);
        }
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY",
                    "Currency must be one of " + SUPPORTED_CURRENCIES);
        }
        // Only merchants have a category: it describes what a business
        // sells, so payers can see where their money went.
        String storedCategory = null;
        if ("MERCHANT".equals(type)) {
            storedCategory = category == null ? "OTHER" : category;
            if (!MERCHANT_CATEGORIES.contains(storedCategory)) {
                throw ApiException.unprocessable("UNSUPPORTED_CATEGORY",
                        "Category must be one of " + MERCHANT_CATEGORIES);
            }
        }
        Account account = new Account(Uuid7.generate(), userId, type, currency, "ACTIVE", name.strip(),
                storedCategory, null);
        // User accounts get a hard floor of 0: the CHECK constraint on
        // account_balances is the database-level double-spend backstop.
        accounts.insert(account, 0);
        audit.record(userId, "ACCOUNT_OPENED", "account", account.id().toString(),
                null, "{\"type\":\"" + type + "\",\"currency\":\"" + currency + "\"}", "account", null);
        return accounts.findById(account.id()).orElseThrow();
    }

    /** Lookup without ownership semantics, for other contexts' checks. */
    public Optional<Account> find(UUID accountId) {
        return accounts.findById(accountId);
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

    public List<AccountSummary> listOwnAccountSummaries(UUID userId) {
        return accounts.findSummariesByUserId(userId);
    }

    public AccountSummary summaryOf(UUID accountId, UUID userId, boolean admin) {
        requireOwnedAccount(accountId, userId, admin);
        return accounts.findSummary(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    public Balance balanceOf(UUID accountId, UUID userId, boolean admin) {
        requireOwnedAccount(accountId, userId, admin);
        return accounts.findBalance(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }

    /**
     * Confirmation of payee: who is behind this account id? Anyone signed
     * in may ask, because they need the answer before sending money, but
     * the reply carries a display name and nothing more.
     */
    public Payee lookupPayee(UUID accountId, UUID viewerUserId) {
        return accounts.findPayee(accountId, viewerUserId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "No account has this id"));
    }

    @Transactional
    public Account rename(UUID accountId, UUID userId, boolean admin, String newName) {
        Account account = requireOwnedAccount(accountId, userId, admin);
        String name = newName.strip();
        if (name.equals(account.name())) {
            return account;
        }
        accounts.updateName(accountId, name);
        audit.recordChange(userId, "ACCOUNT_RENAMED", "account", accountId.toString(),
                Map.of("name", account.name()), Map.of("name", name), "account", null);
        return accounts.findById(accountId).orElseThrow();
    }

    /**
     * Freezing stops money leaving an account without closing it: the
     * "I lost my phone" switch. Money can still arrive (a refund, a
     * transfer from a friend); the movement service refuses debits.
     */
    @Transactional
    public Account freeze(UUID accountId, UUID userId, boolean admin) {
        return changeStatus(accountId, userId, admin, "ACTIVE", "FROZEN", "ACCOUNT_FROZEN");
    }

    @Transactional
    public Account unfreeze(UUID accountId, UUID userId, boolean admin) {
        return changeStatus(accountId, userId, admin, "FROZEN", "ACTIVE", "ACCOUNT_UNFROZEN");
    }

    private Account changeStatus(UUID accountId, UUID userId, boolean admin,
                                 String from, String to, String action) {
        Account account = requireOwnedAccount(accountId, userId, admin);
        if (to.equals(account.status())) {
            return account; // already there: repeating the request is harmless
        }
        if (!from.equals(account.status())) {
            throw ApiException.conflict("ACCOUNT_STATUS_CONFLICT",
                    "An account that is " + account.status().toLowerCase() + " cannot be changed to "
                            + to.toLowerCase());
        }
        accounts.updateStatus(accountId, to);
        audit.record(userId, action, "account", accountId.toString(),
                "{\"status\":\"" + from + "\"}", "{\"status\":\"" + to + "\"}", "account", null);
        return accounts.findById(accountId).orElseThrow();
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
