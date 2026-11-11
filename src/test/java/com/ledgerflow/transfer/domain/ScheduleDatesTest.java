package com.ledgerflow.transfer.domain;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleDatesTest {

    @Test
    void monthlyScheduleStaysAnchoredToItsStartDay() {
        OffsetDateTime start = OffsetDateTime.parse("2027-01-31T00:00:00Z");
        // Counted from the start, not from the previous run: February is
        // short, but March is back on the 31st.
        assertThat(ScheduledTransferService.occurrenceAt(start, "MONTHLY", 1))
                .isEqualTo(OffsetDateTime.parse("2027-02-28T00:00:00Z"));
        assertThat(ScheduledTransferService.occurrenceAt(start, "MONTHLY", 2))
                .isEqualTo(OffsetDateTime.parse("2027-03-31T00:00:00Z"));
    }

    @Test
    void weeklyScheduleMovesSevenDaysAtATime() {
        OffsetDateTime start = OffsetDateTime.parse("2027-03-01T09:30:00Z");
        assertThat(ScheduledTransferService.occurrenceAt(start, "WEEKLY", 3))
                .isEqualTo(OffsetDateTime.parse("2027-03-22T09:30:00Z"));
        assertThat(ScheduledTransferService.occurrenceAt(start, "ONCE", 3)).isEqualTo(start);
    }
}
