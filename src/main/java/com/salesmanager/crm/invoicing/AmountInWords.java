package com.salesmanager.crm.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** "Rupees One Lakh Twenty Three Thousand Four Hundred Fifty Six and Seventy Eight Paise Only" -
 * Indian grouping (thousand, lakh, crore), as printed on GST invoices. */
final class AmountInWords {

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight",
            "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy",
            "Eighty", "Ninety"};

    private AmountInWords() {
    }

    static String rupees(BigDecimal amount) {
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long rupees = abs.longValue();
        int paise = abs.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        StringBuilder out = new StringBuilder("Rupees ").append(rupees == 0 ? "Zero" : words(rupees));
        if (paise > 0) {
            out.append(" and ").append(words(paise)).append(" Paise");
        }
        return out.append(" Only").toString();
    }

    private static String words(long n) {
        StringBuilder out = new StringBuilder();
        long[] divisors = {10_000_000L, 100_000L, 1_000L, 100L};
        String[] names = {"Crore", "Lakh", "Thousand", "Hundred"};
        for (int i = 0; i < divisors.length; i++) {
            if (n >= divisors[i]) {
                out.append(words(n / divisors[i])).append(' ').append(names[i]).append(' ');
                n %= divisors[i];
            }
        }
        if (n >= 20) {
            out.append(TENS[(int) (n / 10)]).append(' ');
            n %= 10;
        }
        if (n > 0) {
            out.append(ONES[(int) n]).append(' ');
        }
        return out.toString().trim();
    }
}
