package com.salesmanager.crm.reporting.dto;

import java.util.List;

/** Rows are ordered Hot, Warm, Cold, then any other level, and within a level by count
 * descending. {@code leadsWithReasons} is the number of distinct leads that have at least one
 * reason recorded. */
public record InterestReasonReportResponse(List<InterestReasonRow> rows, long leadsWithReasons) {
}
