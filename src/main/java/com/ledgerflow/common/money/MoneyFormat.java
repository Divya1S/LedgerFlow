package com.ledgerflow.common.money;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Turns stored minor units into text for people (notifications, exports).
 * Amounts stay BIGINT minor units everywhere else; this is display only.
 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    /** 123456 USD becomes "$1,234.56". */
    public static String format(long minorUnits, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
        format.setCurrency(currency);
        return format.format(decimal(minorUnits, currencyCode));
    }

    /** 123456 USD becomes 1234.56, for spreadsheet columns. */
    public static BigDecimal decimal(long minorUnits, String currencyCode) {
        int digits = Math.max(Currency.getInstance(currencyCode).getDefaultFractionDigits(), 0);
        return BigDecimal.valueOf(minorUnits, digits);
    }
}
