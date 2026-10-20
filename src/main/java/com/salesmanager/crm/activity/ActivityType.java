package com.salesmanager.crm.activity;

/**
 * The set of Lead/Visit lifecycle moments the activity/journey timeline records. Each value
 * corresponds to exactly one ActivityLogService#record call site - see LeadService (CREATED/
 * STATUS_CHANGED/REASSIGNED), VisitService (LOGGED/COMPLETED), visit.LeadVisitEventListener
 * (an additional LOGGED for each auto-created stub Visit, distinguished from a manually
 * logged one by a null actorId), scheduler.MissedVisitJob (MISSED) and
 * scheduler.LapsedLeadJob (LAPSED).
 */
public enum ActivityType {
    LEAD_CREATED,
    LEAD_STATUS_CHANGED,
    LEAD_REASSIGNED,
    VISIT_LOGGED,
    VISIT_COMPLETED,
    VISIT_MISSED,
    LEAD_LAPSED,
    PO_RECEIVED,
    PO_ACCEPTED,
    PO_BILLED,
    PO_CANCELLED
}
