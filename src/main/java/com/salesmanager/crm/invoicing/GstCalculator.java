package com.salesmanager.crm.invoicing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * The one place GST is split into CGST/SGST (intra-state) or IGST (inter-state). Shared by
 * invoicing and quotation so both always agree. Tax type is derived from the seller's state and
 * the place-of-supply state - never taken from user input.
 */
public final class GstCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private GstCalculator() {
    }

    /** Inter-state only when BOTH states are known and differ. */
    public static boolean isInterState(UUID sellerStateId, UUID placeOfSupplyStateId) {
        return sellerStateId != null && placeOfSupplyStateId != null && !sellerStateId.equals(placeOfSupplyStateId);
    }

    /** Tax on {@code taxable} at {@code ratePercent}. Intra-state: each of CGST and SGST is half
     * the rate, rounded HALF_UP to 2 dp independently (so the two halves always match). */
    public static Split split(BigDecimal taxable, BigDecimal ratePercent, boolean interState) {
        if (interState) {
            BigDecimal igst = round(taxable.multiply(ratePercent).divide(HUNDRED, 10, RoundingMode.HALF_UP));
            return new Split(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), igst);
        }
        BigDecimal halfRate = ratePercent.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        BigDecimal half = round(taxable.multiply(halfRate).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        return new Split(half, half, BigDecimal.ZERO.setScale(2));
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record Split(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
        public BigDecimal total() {
            return cgst.add(sgst).add(igst);
        }
    }
}
