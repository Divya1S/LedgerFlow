package com.ledgerflow.transactionquery.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.account.domain.AccountSummary;
import com.ledgerflow.common.security.CurrentUser;
import com.ledgerflow.transactionquery.persistence.InsightsRepository;
import com.ledgerflow.transactionquery.persistence.InsightsRepository.DailyFlow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One call that feeds the overview screen: balances, money in and out per
 * day, the same totals for the period before (for "compared with last
 * month"), and where the money went.
 */
@RestController
@Validated
@RequestMapping("/api/v1/insights")
@Tag(name = "Insights", description = "Balances, cash flow and spending for the overview")
public class InsightsController {

    private final InsightsRepository insights;
    private final AccountService accountService;

    public InsightsController(InsightsRepository insights, AccountService accountService) {
        this.insights = insights;
        this.accountService = accountService;
    }

    public record AccountLine(UUID id, String name, String type, String status, String category,
                              long balanceMinorUnits) {
    }

    public record Totals(long moneyInMinorUnits, long moneyOutMinorUnits, long netMinorUnits) {
    }

    public record Day(LocalDate date, long moneyInMinorUnits, long moneyOutMinorUnits,
                      long closingBalanceMinorUnits) {
    }

    public record Spending(String category, long amountMinorUnits, long transactions) {
    }

    public record Overview(String currency, int days, LocalDate from, LocalDate to,
                           long totalBalanceMinorUnits, List<AccountLine> accounts,
                           Totals current, Totals previous,
                           List<Day> daily, List<Spending> spending) {
    }

    @GetMapping("/overview")
    @Operation(summary = "Balances, daily cash flow and spending for the last N days")
    Overview overview(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(defaultValue = "30") @Min(1) @Max(366) int days,
                      @RequestParam(defaultValue = "UTC") String zone) {
        CurrentUser user = CurrentUser.from(jwt);
        ZoneId zoneId = Zones.parse(zone);

        List<AccountSummary> summaries = accountService.listOwnAccountSummaries(user.id());
        List<AccountLine> accounts = summaries.stream()
                .map(s -> new AccountLine(s.account().id(), s.account().name(), s.account().type(),
                        s.account().status(), s.account().category(), s.balance()))
                .toList();
        long totalBalance = summaries.stream().mapToLong(AccountSummary::balance).sum();

        LocalDate today = LocalDate.now(zoneId);
        LocalDate from = today.minusDays(days - 1L);
        LocalDate previousFrom = from.minusDays(days);
        OffsetDateTime start = from.atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime previousStart = previousFrom.atStartOfDay(zoneId).toOffsetDateTime();
        OffsetDateTime end = today.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime();

        if (summaries.isEmpty()) {
            return new Overview("USD", days, from, today, 0, accounts,
                    new Totals(0, 0, 0), new Totals(0, 0, 0), emptyDays(from, today, 0), List.of());
        }
        UUID[] own = summaries.stream().map(s -> s.account().id()).toArray(UUID[]::new);

        // One query covers this period and the one before it.
        Map<LocalDate, DailyFlow> byDay = new HashMap<>();
        long previousIn = 0;
        long previousOut = 0;
        for (DailyFlow flow : insights.dailyFlows(own, previousStart, end, zoneId.getId())) {
            if (flow.day().isBefore(from)) {
                previousIn += flow.moneyIn();
                previousOut += flow.moneyOut();
            } else {
                byDay.put(flow.day(), flow);
            }
        }

        // Closing balance per day, walked backwards from today's real
        // balance: yesterday's close is today's close minus today's net.
        List<Day> daily = new ArrayList<>(days);
        long closing = totalBalance;
        long currentIn = 0;
        long currentOut = 0;
        for (LocalDate day = today; !day.isBefore(from); day = day.minusDays(1)) {
            DailyFlow flow = byDay.get(day);
            long in = flow == null ? 0 : flow.moneyIn();
            long out = flow == null ? 0 : flow.moneyOut();
            daily.add(new Day(day, in, out, closing));
            closing -= in - out;
            currentIn += in;
            currentOut += out;
        }
        List<Day> oldestFirst = daily.reversed();

        List<Spending> spending = insights.spending(own, start, end).stream()
                .map(s -> new Spending(s.category(), s.amount(), s.transactions()))
                .toList();

        return new Overview(summaries.getFirst().account().currency(), days, from, today,
                totalBalance, accounts,
                new Totals(currentIn, currentOut, currentIn - currentOut),
                new Totals(previousIn, previousOut, previousIn - previousOut),
                oldestFirst, spending);
    }

    private static List<Day> emptyDays(LocalDate from, LocalDate to, long balance) {
        List<Day> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(new Day(day, 0, 0, balance));
        }
        return days;
    }
}
