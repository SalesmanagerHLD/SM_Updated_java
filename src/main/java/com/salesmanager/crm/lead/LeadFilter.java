package com.salesmanager.crm.lead;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Optional query filters for {@code GET /leads}, as requested by the caller. The
 * EMPLOYEE-vs-ADMIN ownership visibility rule (an EMPLOYEE's ownerId filter is always
 * silently forced to their own id) is enforced in LeadService#list, not here - this record
 * only carries what was literally asked for.
 *
 * <p>{@code search} is a free-text term matched (case-insensitive, substring) against
 * companyName/contactPerson/contactNo/email - added for the "find an existing lead to log a
 * visit against" picker, which needs real server-side search (company/contact/phone/email)
 * rather than the Lead list page's existing client-side-over-the-loaded-page filtering, which
 * only covers the current page and doesn't search email at all.
 *
 * <p>{@code stateId}/{@code cityId}/{@code productId}/{@code dateFrom}/{@code dateTo} back the
 * Reports section's filterable Leads table - dateFrom/dateTo filter on createdAt (see
 * LeadSpecifications#createdBetween).
 */
public record LeadFilter(LeadStatus status, UUID ownerId, UUID interestLevelId, String search,
                          UUID stateId, UUID cityId, UUID productId, LocalDate dateFrom, LocalDate dateTo,
                          UUID interestReasonId) {
}
