package com.salesmanager.crm.masterdata;

/**
 * The fixed set of dropdown-driving reference-data categories. Kept in sync with the
 * CHECK constraint on master_data.type in V2__master_data_and_employee_extensions.sql -
 * any change here must be accompanied by a new migration updating that constraint.
 */
public enum MasterType {
    INDUSTRY,
    CITY,
    PRODUCT,
    BUSINESS_TYPE,
    DESIGNATION,
    VISIT_PURPOSE,
    NEXT_ACTION,
    LOST_REASON,
    INTEREST_LEVEL,
    LEAD_SOURCE,
    STATE,
    /** Why a lead/visit has its interest level (e.g. Hot because "Budget approved"). Each row's
     * parent_id is the INTEREST_LEVEL row it belongs to. */
    INTEREST_REASON
}
