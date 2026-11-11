package com.ledgerflow.transactionquery.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvCellTest {

    @Test
    void plainTextIsQuoted() {
        assertThat(StatementExport.cell("weekly shop")).isEqualTo("\"weekly shop\"");
        assertThat(StatementExport.cell(null)).isEmpty();
        assertThat(StatementExport.cell("")).isEmpty();
    }

    @Test
    void quotesCommasAndLineBreaksCannotBreakTheRow() {
        assertThat(StatementExport.cell("say \"hi\", then go")).isEqualTo("\"say \"\"hi\"\", then go\"");
        assertThat(StatementExport.cell("line one\r\nline two")).isEqualTo("\"line one  line two\"");
    }

    @Test
    void textThatLooksLikeAFormulaIsExportedAsText() {
        for (String dangerous : new String[]{"=1+1", "+1", "-1", "@SUM(A1)", "\tx"}) {
            assertThat(StatementExport.cell(dangerous)).startsWith("\"'");
        }
    }
}
