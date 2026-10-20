package com.salesmanager.crm.purchaseorder;

import java.util.List;
import lombok.Getter;

/** Billing more than a PO line's remaining quantity without a valid admin override. Mapped to
 * 409; {@code lines} lists each offending line with what is left, so the UI can say exactly why. */
@Getter
public class OverBillingException extends RuntimeException {

    public record Violation(java.util.UUID poLineId, String description, java.math.BigDecimal requested,
                             java.math.BigDecimal remaining) {
    }

    private final List<Violation> violations;
    /** True when the caller could override (an admin who just forgot the reason). */
    private final boolean overridable;

    public OverBillingException(String message, List<Violation> violations, boolean overridable) {
        super(message);
        this.violations = violations;
        this.overridable = overridable;
    }
}
