package com.ledgerflow.transactionquery.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.Balance;
import com.ledgerflow.common.error.ApiException;
import com.ledgerflow.common.money.MoneyFormat;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository;
import com.ledgerflow.transactionquery.persistence.TransactionQueryRepository.StatementLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds a downloadable statement for a date range.
 *
 * The three reads (current balance, entries after the range, entries in
 * the range) run in one REPEATABLE READ snapshot. Without that, a payment
 * landing between two of them would make the running balance column
 * disagree with the entries above it.
 */
@Service
public class StatementExport {

    /** Enough for a busy year of personal use; bigger exports need a narrower range. */
    static final int MAX_ROWS = 10_000;
    static final int MAX_DAYS = 366;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TransactionQueryRepository transactions;
    private final AccountService accounts;

    public StatementExport(TransactionQueryRepository transactions, AccountService accounts) {
        this.transactions = transactions;
        this.accounts = accounts;
    }

    public record Export(String fileName, String csv) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export csv(UUID accountId, UUID userId, boolean admin, LocalDate from, LocalDate to, ZoneId zone) {
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("INVALID_RANGE", "The end date is before the start date");
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw ApiException.unprocessable("RANGE_TOO_LONG",
                    "A statement can cover at most " + MAX_DAYS + " days");
        }
        Account account = accounts.requireOwnedAccount(accountId, userId, admin);
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime endExclusive = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        Balance now = accounts.balanceOf(accountId, userId, admin);
        long closing = now.balance() - transactions.netChangeSince(accountId, endExclusive);
        List<StatementLine> lines = transactions.statementRange(
                accountId, userId, closing, start, endExclusive, MAX_ROWS + 1);
        if (lines.size() > MAX_ROWS) {
            throw ApiException.unprocessable("EXPORT_TOO_LARGE",
                    "This range has more than " + MAX_ROWS + " entries; choose a shorter one");
        }

        StringBuilder csv = new StringBuilder(64 + lines.size() * 140);
        csv.append("Date,Type,Description,Counterparty,Money in,Money out,Balance,Currency,Transaction ID\r\n");
        // Oldest first reads like a bank statement and sorts correctly in a sheet.
        for (StatementLine line : lines.reversed()) {
            csv.append(TIMESTAMP.format(line.createdAt().atZoneSameInstant(zone))).append(',')
                    .append(label(line.type())).append(',')
                    .append(cell(line.description())).append(',')
                    .append(cell(line.counterparty())).append(',')
                    .append(line.amount() > 0 ? MoneyFormat.decimal(line.amount(), line.currency()) : "").append(',')
                    .append(line.amount() < 0 ? MoneyFormat.decimal(-line.amount(), line.currency()) : "").append(',')
                    .append(MoneyFormat.decimal(line.balanceAfter(), line.currency())).append(',')
                    .append(line.currency()).append(',')
                    .append(line.transactionId()).append("\r\n");
        }
        String fileName = "ledgerflow-" + slug(account.name()) + "-" + from + "-to-" + to + ".csv";
        return new Export(fileName, csv.toString());
    }

    private static String label(String type) {
        return type == null ? "" : type.charAt(0) + type.substring(1).toLowerCase();
    }

    /**
     * One CSV cell. Quotes are doubled and the cell is quoted, as the format
     * requires. Text a person typed is also defused for spreadsheets: a
     * description starting with = + - or @ would otherwise run as a formula
     * when the file is opened in Excel or Sheets.
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace("\r", " ").replace("\n", " ");
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String slug(String name) {
        String slug = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "account" : slug;
    }
}
