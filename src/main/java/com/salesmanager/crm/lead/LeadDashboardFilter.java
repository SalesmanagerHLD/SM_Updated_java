package com.salesmanager.crm.lead;

import java.time.LocalDate;
import java.util.UUID;

/** Optional filters for the Reports Dashboard's global filter bar (section 17.5 of the
 * Quotations/Invoices plan) - a superset of {@link LeadFilter}'s fields plus businessTypeId/
 * nextFollowupDate/expectedCloseDate. Kept separate from LeadFilter (rather than extending its
 * field list) since GET /leads and the dashboard have genuinely different filter surfaces and
 * dateFrom/dateTo mean something different here (quotationDate-style "created between" is
 * shared, but the two extra date fields are exact-match, not ranges). */
public record LeadDashboardFilter(LeadStatus status, UUID ownerId, UUID interestLevelId, UUID stateId, UUID cityId,
                                   UUID productId, UUID businessTypeId, LocalDate nextFollowupDate,
                                   LocalDate expectedCloseDate, LocalDate dateFrom, LocalDate dateTo,
                                   UUID interestReasonId) {
}
