package com.salesmanager.crm.reporting.dto;

import java.math.BigDecimal;

/** GET /reports/revenue - entitled is false (revenue always zero) when the org hasn't licensed
 * INVOICING, so the frontend can distinguish "no revenue yet" from "this feature
 * isn't available" rather than showing a misleading zero. */
public record RevenueResponse(boolean entitled, BigDecimal revenue) {
}
