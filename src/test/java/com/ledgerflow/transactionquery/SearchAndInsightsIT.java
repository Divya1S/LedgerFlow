package com.ledgerflow.transactionquery;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ledgerflow.support.ApiTestClient;
import com.ledgerflow.support.IntegrationTest;
import com.ledgerflow.support.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction search, ledger entries, statements, CSV export and the
 * overview numbers, all checked against one small, known history:
 *
 *   Alice: +500.00 deposit, 100.00 to her own savings, 40.00 to Bob,
 *          60.00 to Corner Grocery, 20.00 withdrawn.
 *   So money in is 500.00, money out is 120.00 (the move to savings is
 *   neither), and she holds 380.00 across two accounts.
 */
@IntegrationTest
class SearchAndInsightsIT {

    @Autowired
    TestRestTemplate rest;

    ApiTestClient api;
    Money money;

    ApiTestClient.Session alice;
    ApiTestClient.Session bob;
    String everyday;
    String savings;
    String bobWallet;
    String grocery;
    String paymentTransactionId;

    @BeforeEach
    void setUp() {
        api = new ApiTestClient(rest);
        money = new Money(api);

        alice = api.registerNamed("Alice Stone");
        bob = api.registerNamed("Bob Reyes");
        everyday = money.openWallet(alice, "Everyday");
        savings = money.openWallet(alice, "Savings");
        bobWallet = money.openWallet(bob, "Bob wallet");
        grocery = money.openShop(bob, "Corner Grocery", "GROCERIES");

        money.deposit(alice, everyday, 50_000, "=1+1");
        money.transfer(alice, everyday, savings, 10_000, "to savings");
        money.transfer(alice, everyday, bobWallet, 4_000, "dinner split");
        paymentTransactionId = (String) money.pay(alice, everyday, grocery, 6_000, "weekly shop")
                .getBody().get("transactionId");
        money.withdraw(alice, everyday, 2_000);
    }

    private List<Map<String, Object>> search(ApiTestClient.Session who, String query) {
        ResponseEntity<Map> response = api.get(who.token(), "/api/v1/transactions" + query);
        assertThat(response.getStatusCode()).as("search %s", query).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        return items;
    }

    @Test
    void historyNamesTheOtherSideAndTheDirection() {
        List<Map<String, Object>> all = search(alice, "");
        assertThat(all).extracting(t -> t.get("type"))
                .containsExactly("WITHDRAWAL", "PAYMENT", "TRANSFER", "TRANSFER", "DEPOSIT");
        assertThat(all).extracting(t -> t.get("direction"))
                .containsExactly("OUT", "OUT", "OUT", "INTERNAL", "IN");
        assertThat(all).extracting(t -> t.get("counterparty"))
                .containsExactly("External bank", "Corner Grocery", "Bob Reyes", "Savings", "External bank");

        // Bob sees the same transfer from the other side, with Alice's name
        // and not the label she gave her wallet.
        Map<String, Object> received = search(bob, "?type=TRANSFER").getFirst();
        assertThat(received.get("direction")).isEqualTo("IN");
        assertThat(received.get("counterparty")).isEqualTo("Alice Stone");
    }

    @Test
    void filtersNarrowTheHistory() {
        assertThat(search(alice, "?type=TRANSFER")).hasSize(2);
        assertThat(search(alice, "?direction=OUT")).extracting(t -> t.get("type"))
                .containsExactly("WITHDRAWAL", "PAYMENT", "TRANSFER");
        assertThat(search(alice, "?direction=IN")).extracting(t -> t.get("type")).containsExactly("DEPOSIT");
        assertThat(search(alice, "?q=DINNER")).hasSize(1); // case-insensitive
        assertThat(search(alice, "?minAmount=5000&maxAmount=7000")).extracting(t -> t.get("type"))
                .containsExactly("PAYMENT");
        // One account: only what touched it.
        assertThat(search(alice, "?accountId=" + savings)).hasSize(1);
        assertThat(search(alice, "?accountId=" + savings + "&direction=IN")).hasSize(1);
        assertThat(search(alice, "?accountId=" + savings + "&direction=OUT")).isEmpty();

        // Wildcards typed by a person are searched for literally.
        assertThat(search(alice, "?q=%")).isEmpty();
        assertThat(search(alice, "?q=_inner")).isEmpty();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(search(alice, "?from=" + today.minusDays(1) + "&to=" + today.plusDays(1))).hasSize(5);
        assertThat(search(alice, "?from=" + today.plusDays(2))).isEmpty();
        assertThat(search(alice, "?to=" + today.minusDays(2))).isEmpty();
    }

    @Test
    void filteredHistoryPagesWithoutGapsOrRepeats() {
        Set<Object> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> page = api.get(alice.token(),
                    "/api/v1/transactions?limit=2&direction=OUT" + (cursor == null ? "" : "&cursor=" + cursor));
            List<?> items = (List<?>) page.getBody().get("items");
            for (Object item : items) {
                assertThat(seen.add(((Map<?, ?>) item).get("id"))).as("no transaction twice").isTrue();
            }
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).hasSize(3);
    }

    @Test
    void badFiltersAreTheCallersMistakeNotAServerError() {
        ResponseEntity<Map> badType = api.get(alice.token(), "/api/v1/transactions?type=GIFT");
        assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badType.getBody().get("code")).isEqualTo("VALIDATION_FAILED");

        assertThat(api.get(alice.token(), "/api/v1/transactions?zone=Nowhere/City").getBody().get("code"))
                .isEqualTo("INVALID_ZONE");
        assertThat(api.get(alice.token(), "/api/v1/transactions?from=yesterday").getBody().get("code"))
                .isEqualTo("INVALID_PARAMETER");
        assertThat(api.get(alice.token(), "/api/v1/transactions?limit=5000").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Filtering by someone else's account does not reveal that it exists.
        assertThat(api.get(alice.token(), "/api/v1/transactions?accountId=" + bobWallet).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void entriesShowTheBalancedPostingBehindAPayment() {
        Map<?, ?> body = api.get(alice.token(),
                "/api/v1/transactions/" + paymentTransactionId + "/entries").getBody();
        assertThat(((Number) body.get("sumMinorUnits")).longValue()).isZero();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        // 60.00 leaves Alice; 59.40 reaches the shop; 0.60 is the platform fee.
        assertThat(entries).extracting(e -> ((Number) e.get("amountMinorUnits")).longValue())
                .containsExactly(-6_000L, 60L, 5_940L);
        assertThat(entries).extracting(e -> e.get("account"))
                .containsExactly("Everyday", "LedgerFlow", "Corner Grocery");
        // Account ids are only given for the caller's own accounts.
        assertThat(entries.get(0).get("accountId")).isEqualTo(everyday);
        assertThat(entries.get(2).get("accountId")).isNull();

        ApiTestClient.Session mallory = api.registerAndLogin();
        assertThat(api.get(mallory.token(), "/api/v1/transactions/" + paymentTransactionId + "/entries")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statementLinesSayWhatHappenedAndWithWhom() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) api.get(alice.token(),
                "/api/v1/accounts/" + everyday + "/statement").getBody().get("items");
        assertThat(lines).extracting(l -> l.get("type"))
                .containsExactly("WITHDRAWAL", "PAYMENT", "TRANSFER", "TRANSFER", "DEPOSIT");
        assertThat(lines).extracting(l -> l.get("counterparty"))
                .containsExactly("External bank", "Corner Grocery", "Bob Reyes", "Savings", "External bank");
        assertThat(lines).extracting(l -> ((Number) l.get("balanceAfterMinorUnits")).longValue())
                .containsExactly(28_000L, 30_000L, 36_000L, 40_000L, 50_000L);
        assertThat(lines.get(1).get("description")).isEqualTo("weekly shop");
    }

    @Test
    void csvExportIsChronologicalBalancedAndSafeToOpenInASpreadsheet() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ResponseEntity<String> export = api.getText(alice.token(),
                "/api/v1/accounts/" + everyday + "/statement/export?from=" + today + "&to=" + today);
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(export.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(export.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("ledgerflow-everyday-" + today + "-to-" + today + ".csv");

        String[] rows = export.getBody().replace("﻿", "").split("\r\n");
        assertThat(rows[0]).isEqualTo(
                "Date,Type,Description,Counterparty,Money in,Money out,Balance,Currency,Transaction ID");
        assertThat(rows).hasSize(6);
        // Oldest first. The deposit's description starts with "=", which a
        // spreadsheet would run as a formula, so it is exported as text.
        assertThat(rows[1]).contains(",Deposit,\"'=1+1\",\"External bank\",500.00,,500.00,USD,");
        assertThat(rows[2]).contains(",Transfer,\"to savings\",\"Savings\",,100.00,400.00,USD,");
        assertThat(rows[4]).contains(",Payment,\"weekly shop\",\"Corner Grocery\",,60.00,300.00,USD,");
        assertThat(rows[5]).contains(",Withdrawal,,\"External bank\",,20.00,280.00,USD,");

        // A range that ends before today has nothing in it, and still works.
        String empty = api.getText(alice.token(), "/api/v1/accounts/" + everyday
                + "/statement/export?from=" + today.minusDays(9) + "&to=" + today.minusDays(2)).getBody();
        assertThat(empty.replace("﻿", "").split("\r\n")).hasSize(1);

        assertThat(api.get(alice.token(), "/api/v1/accounts/" + everyday
                + "/statement/export?from=" + today + "&to=" + today.minusDays(1)).getBody().get("code"))
                .isEqualTo("INVALID_RANGE");
        assertThat(api.get(alice.token(), "/api/v1/accounts/" + everyday
                + "/statement/export?from=" + today.minusDays(500) + "&to=" + today).getBody().get("code"))
                .isEqualTo("RANGE_TOO_LONG");
        assertThat(api.get(alice.token(), "/api/v1/accounts/" + everyday + "/statement/export?from=" + today)
                .getBody().get("code")).isEqualTo("MISSING_PARAMETER");
        // Someone else's statement is not downloadable.
        assertThat(api.get(bob.token(), "/api/v1/accounts/" + everyday
                + "/statement/export?from=" + today + "&to=" + today).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void overviewAddsUpToTheSameNumbersAsTheLedger() {
        Map<?, ?> overview = api.get(alice.token(), "/api/v1/insights/overview?days=7").getBody();

        assertThat(((Number) overview.get("totalBalanceMinorUnits")).longValue()).isEqualTo(38_000);
        assertThat((List<?>) overview.get("accounts")).hasSize(2);

        Map<?, ?> current = (Map<?, ?>) overview.get("current");
        // The 100.00 moved to her own savings is not income and not spending.
        assertThat(((Number) current.get("moneyInMinorUnits")).longValue()).isEqualTo(50_000);
        assertThat(((Number) current.get("moneyOutMinorUnits")).longValue()).isEqualTo(12_000);
        assertThat(((Number) current.get("netMinorUnits")).longValue()).isEqualTo(38_000);
        Map<?, ?> previous = (Map<?, ?>) overview.get("previous");
        assertThat(((Number) previous.get("moneyInMinorUnits")).longValue()).isZero();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) overview.get("daily");
        assertThat(daily).hasSize(7);
        Map<String, Object> today = daily.getLast();
        assertThat(today.get("date")).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(((Number) today.get("moneyInMinorUnits")).longValue()).isEqualTo(50_000);
        assertThat(((Number) today.get("closingBalanceMinorUnits")).longValue()).isEqualTo(38_000);
        // Before today she had nothing, and the walk back from today's balance says so.
        assertThat(((Number) daily.get(5).get("closingBalanceMinorUnits")).longValue()).isZero();
        assertThat(((Number) daily.getFirst().get("closingBalanceMinorUnits")).longValue()).isZero();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spending = (List<Map<String, Object>>) overview.get("spending");
        assertThat(spending).extracting(s -> s.get("category"))
                .containsExactly("GROCERIES", "TRANSFERS", "WITHDRAWALS");
        assertThat(spending).extracting(s -> ((Number) s.get("amountMinorUnits")).longValue())
                .containsExactly(6_000L, 4_000L, 2_000L);

        // Someone with no accounts gets an empty, well-formed overview.
        ApiTestClient.Session nobody = api.registerAndLogin();
        Map<?, ?> blank = api.get(nobody.token(), "/api/v1/insights/overview").getBody();
        assertThat(((Number) blank.get("totalBalanceMinorUnits")).longValue()).isZero();
        assertThat((List<?>) blank.get("daily")).hasSize(30);

        assertThat(api.get(alice.token(), "/api/v1/insights/overview?zone=Mars/Olympus").getBody().get("code"))
                .isEqualTo("INVALID_ZONE");
    }
}
