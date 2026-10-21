package com.salesmanager.crm.reporting;

import com.salesmanager.crm.lead.LeadDashboardFilter;
import com.salesmanager.crm.lead.LeadStatus;
import com.salesmanager.crm.reporting.dto.ConversionRateResponse;
import com.salesmanager.crm.reporting.dto.InterestLevelStatusMatrixResponse;
import com.salesmanager.crm.reporting.dto.InterestReasonReportResponse;
import com.salesmanager.crm.reporting.dto.LeadDashboardResponse;
import com.salesmanager.crm.reporting.dto.LeadsBySourceResponse;
import com.salesmanager.crm.reporting.dto.PipelineSummaryResponse;
import com.salesmanager.crm.reporting.dto.QuotationInvoiceSummaryResponse;
import com.salesmanager.crm.reporting.dto.RevenueResponse;
import com.salesmanager.crm.reporting.dto.TeamProgressResponse;
import com.salesmanager.crm.reporting.dto.VisitsByTypeResponse;
import com.salesmanager.crm.reporting.dto.VisitsCompletedVsMissedResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin controller - all aggregation lives in ReportingService, same layering as
 * MasterDataController/LeadController. No class-level role restriction: an ADMIN always gets
 * the unrestricted org-wide picture, while an EMPLOYEE is let through here but then scoped (or
 * flatly denied with a 403) by ReportingService#resolveOwnerScope based on the TEAM_VISIBILITY
 * entitlement and whether they actually have any subordinates - see that method's comment.
 */
@RestController
@RequestMapping("/reports")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/pipeline-summary")
    public PipelineSummaryResponse pipelineSummary() {
        return reportingService.pipelineSummary();
    }

    @GetMapping("/conversion-rate")
    public ConversionRateResponse conversionRate() {
        return reportingService.conversionRate();
    }

    @GetMapping("/visits-completed-vs-missed")
    public VisitsCompletedVsMissedResponse visitsCompletedVsMissed(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return reportingService.visitsCompletedVsMissed(dateFrom, dateTo);
    }

    @GetMapping("/leads-by-source")
    public LeadsBySourceResponse leadsBySource() {
        return reportingService.leadsBySource();
    }

    @GetMapping("/revenue")
    public RevenueResponse revenue(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return reportingService.revenue(dateFrom, dateTo);
    }

    @GetMapping("/visits-by-type")
    public VisitsByTypeResponse visitsByType(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return reportingService.visitsByType(dateFrom, dateTo);
    }

    @GetMapping("/interest-reasons")
    public InterestReasonReportResponse interestReasons(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return reportingService.interestReasons(dateFrom, dateTo);
    }

    @GetMapping("/interest-level-status-matrix")
    public InterestLevelStatusMatrixResponse interestLevelStatusMatrix() {
        return reportingService.interestLevelStatusMatrix();
    }

    @GetMapping("/team-progress")
    public TeamProgressResponse teamProgress() {
        return reportingService.teamProgress();
    }

    @GetMapping("/lead-dashboard")
    public LeadDashboardResponse leadDashboard(
            @RequestParam(required = false) LeadStatus status,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(required = false) UUID interestLevelId,
            @RequestParam(required = false) UUID stateId,
            @RequestParam(required = false) UUID cityId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID businessTypeId,
            @RequestParam(required = false) UUID interestReasonId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nextFollowupDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedCloseDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        LeadDashboardFilter filter = new LeadDashboardFilter(status, ownerId, interestLevelId, stateId, cityId,
                productId, businessTypeId, nextFollowupDate, expectedCloseDate, dateFrom, dateTo, interestReasonId);
        return reportingService.leadDashboard(filter);
    }

    @GetMapping("/quotation-invoice-summary")
    public QuotationInvoiceSummaryResponse quotationInvoiceSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return reportingService.quotationInvoiceSummary(dateFrom, dateTo);
    }
}
