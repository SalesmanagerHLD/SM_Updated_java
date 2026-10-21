package com.salesmanager.crm.lead;

import java.util.UUID;

/** Spring Data projection for LeadRepository#countReasons - one row per distinct
 * (interestLevelId, reasonId) pair: how many leads at that level carry that reason. */
public interface LeadInterestReasonCount {

    UUID getInterestLevelId();

    UUID getReasonId();

    Long getCount();
}
