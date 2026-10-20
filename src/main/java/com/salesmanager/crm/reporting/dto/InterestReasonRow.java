package com.salesmanager.crm.reporting.dto;

/** One row of GET /reports/interest-reasons: how many leads at {@code interestLevel} (already a
 * master-data label, e.g. "Hot") have {@code reason} (e.g. "Budget approved"). A lead with
 * several reasons counts once under each. */
public record InterestReasonRow(String interestLevel, String reason, long count) {
}
