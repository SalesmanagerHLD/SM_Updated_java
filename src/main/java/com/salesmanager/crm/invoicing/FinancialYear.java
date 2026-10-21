package com.salesmanager.crm.invoicing;

import java.time.LocalDate;

/** Indian financial year: 1 April - 31 March. Invoice numbers restart on 1 April. */
public final class FinancialYear {

    private FinancialYear() {
    }

    /** The calendar year the financial year starts in (Apr 2026 - Mar 2027 -> 2026). */
    public static int startYear(LocalDate date) {
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    /** "26-27" for FY 2026-27. */
    public static String label(int startYear) {
        return String.format("%02d-%02d", startYear % 100, (startYear + 1) % 100);
    }
}
