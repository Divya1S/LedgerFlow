package com.ledgerflow.transfer.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ledgerflow.account.domain.Account;
import com.ledgerflow.account.domain.AccountService;
import com.ledgerflow.transfer.persistence.TransferHoldRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a transfer goes through at once or waits on hold.
 *
 * The problem it answers: an instant transfer to the wrong person, or to
 * a scammer, cannot be taken back. That is the largest single complaint
 * about payment apps (the evidence is in ADR-010). The costly cases share
 * a shape: the first payment to someone the sender has never dealt with,
 * for a meaningful amount, made in a hurry.
 *
 * The rule is deliberately small and explainable:
 *
 *  1. Below the threshold: instant. No lookups at all, so everyday
 *     payments pay nothing for this feature.
 *  2. The two people have settled money before, in either direction:
 *     instant. Trust was established by a payment that actually arrived.
 *  3. Otherwise: hold. The hold is longer when there is evidence about
 *     the RECIPIENT (a brand new account, or several strangers paying them
 *     today), because the sender cannot vouch for that.
 *
 * Every reason is returned as a code the dashboard shows in plain words.
 * A hold the person cannot understand would just be a different complaint.
 */
@Component
public class TransferProtection {

    public static final String FIRST_PAYMENT = "FIRST_PAYMENT";
    public static final String RECIPIENT_ACCOUNT_NEW = "RECIPIENT_ACCOUNT_NEW";
    public static final String RECIPIENT_PAID_BY_STRANGERS = "RECIPIENT_PAID_BY_STRANGERS";

    private final AccountService accounts;
    private final TransferHoldRepository holds;
    private final boolean enabled;
    private final long thresholdMinorUnits;
    private final int elevatedHoldMinutes;
    private final int highHoldMinutes;
    private final int newAccountDays;
    private final int strangersForHighRisk;

    public TransferProtection(AccountService accounts, TransferHoldRepository holds,
                              @Value("${ledgerflow.protection.enabled:true}") boolean enabled,
                              @Value("${ledgerflow.protection.threshold-minor-units:20000}") long thresholdMinorUnits,
                              @Value("${ledgerflow.protection.elevated-hold-minutes:60}") int elevatedHoldMinutes,
                              @Value("${ledgerflow.protection.high-hold-minutes:1440}") int highHoldMinutes,
                              @Value("${ledgerflow.protection.new-account-days:7}") int newAccountDays,
                              @Value("${ledgerflow.protection.strangers-for-high-risk:2}") int strangersForHighRisk) {
        this.accounts = accounts;
        this.holds = holds;
        this.enabled = enabled;
        this.thresholdMinorUnits = thresholdMinorUnits;
        this.elevatedHoldMinutes = elevatedHoldMinutes;
        this.highHoldMinutes = highHoldMinutes;
        this.newAccountDays = newAccountDays;
        this.strangersForHighRisk = strangersForHighRisk;
    }

    /**
     * @param recipientUserId who the money is for; null when it is not held
     */
    public record Assessment(boolean held, String riskLevel, List<String> reasons, int holdMinutes,
                             UUID recipientUserId) {

        static Assessment instant() {
            return new Assessment(false, null, List.of(), 0, null);
        }
    }

    public long thresholdMinorUnits() {
        return thresholdMinorUnits;
    }

    public Assessment assess(UUID senderUserId, UUID destinationAccountId, long amount, String currency) {
        if (!enabled || amount < thresholdMinorUnits) {
            return Assessment.instant();
        }
        Account destination = accounts.find(destinationAccountId).orElse(null);
        // Anything the ordinary path would refuse anyway (unknown or closed
        // account, wrong currency) is left to it, so the error is the same
        // with or without protection. Platform accounts and the sender's
        // own accounts are never strangers.
        if (destination == null
                || destination.userId() == null
                || destination.userId().equals(senderUserId)
                || "CLOSED".equals(destination.status())
                || !destination.currency().equals(currency)) {
            return Assessment.instant();
        }
        if (holds.haveSettledBefore(senderUserId, destination.userId())) {
            return Assessment.instant();
        }

        List<String> reasons = new ArrayList<>();
        reasons.add(FIRST_PAYMENT);
        boolean recipientEvidence = false;
        if (destination.createdAt() != null
                && destination.createdAt().isAfter(OffsetDateTime.now().minusDays(newAccountDays))) {
            reasons.add(RECIPIENT_ACCOUNT_NEW);
            recipientEvidence = true;
        }
        if (holds.otherRecentFirstTimeSenders(destination.userId(), senderUserId) >= strangersForHighRisk) {
            reasons.add(RECIPIENT_PAID_BY_STRANGERS);
            recipientEvidence = true;
        }
        return recipientEvidence
                ? new Assessment(true, "HIGH", List.copyOf(reasons), highHoldMinutes, destination.userId())
                : new Assessment(true, "ELEVATED", List.copyOf(reasons), elevatedHoldMinutes, destination.userId());
    }
}
