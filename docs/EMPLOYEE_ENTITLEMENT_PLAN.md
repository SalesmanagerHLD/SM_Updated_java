# Employee Entitlement Module — Implementation Plan

> **Status: COMPLETE (2026-07-20).** Both Part A (entitlement infrastructure) and Part B
> (Leave/Attendance/Approvals, all 5 phases) described below are fully implemented, tested
> (155 backend integration tests passing), and live-deployed. This document is kept as-is as
> the historical design record — see `CRM_IMPLEMENTATION.md` sections 11-13 for what actually
> shipped, including a few small deliberate deviations from this plan (e.g. Leave Types ended
> up simpler than originally sketched here — no `is_paid`/`carry_forward` fields, no `is_half_day`
> on requests — added only if real usage shows they're needed). Named Teams (B.2) remain
> deliberately deferred, as planned.
>
> **Follow-on (2026-07-20, same day):** the Part A entitlement infrastructure got a second
> consumer beyond this module — `FeatureEntitlement.TEAM_VISIBILITY` reuses B.2's
> `EmployeeHierarchyService.getAllSubordinateIds` recursive hierarchy to expand a manager's
> visibility in the core Sales module (Leads/Visits/Reports/Activity), which until then had
> never used the `managerId` hierarchy at all. See `CRM_IMPLEMENTATION.md` section 20. Named
> Teams are still not built — this extension, like B.2 itself, works identically with or
> without them.

## Context

The user wants to add Employee Leave/Attendance/Approvals management to SalesManager CRM, explicitly gated behind a **feature entitlement** — i.e. this is a licensed add-on module, not something every organization automatically has. This is genuinely two separate pieces of work:

1. **Feature entitlement/licensing infrastructure** — platform-level: which organizations are licensed to use which product features. Nothing like this exists in the app today (every feature built so far is available to every org unconditionally).
2. **The Leave/Attendance/Approvals module itself** — an HR domain, built on top of the existing `Employee` entity, gated behind entitlement #1.

Confirmed design decisions from discussion: approval routing follows a **direct-manager hierarchy** (not "any Admin approves anyone"), and attendance uses **clock-in/clock-out** with real timestamps (not simple daily marking).

This plan does not touch the core CRM (Leads/Visits/Masters) at all — it's a new, mostly self-contained area, reusing existing infrastructure (Employee, Notification, tenant/RLS plumbing) wherever it fits.

---

## Part A — Feature Entitlement / Licensing Infrastructure

### A.1 Core concept
An **Entitlement** is a named, platform-wide feature flag (e.g. `EMPLOYEE_LEAVE_MANAGEMENT`). Unlike everything else in this app, entitlements are **not tenant-scoped data** — they describe what the *platform* offers and which *organizations* currently have access, so the data model spans tenants deliberately (the one legitimate place in the whole system where that's true by design, not by RLS bypass accident).

### A.2 Schema
```
entitlements(code PK, name, description, created_at)
  -- seeded platform-wide, not per-org. e.g. row: EMPLOYEE_LEAVE_MANAGEMENT / "Employee Leave & Attendance"

organization_entitlements(id, organization_id, entitlement_code, granted_at, expires_at NULL, granted_by NULL, revoked_at NULL)
  -- one row per (org, entitlement) grant. expires_at supports time-limited trials.
  -- NOT RLS-protected the normal way (see A.4) - a tenant must never be able to grant itself an entitlement.
```
No `Plan`/tier bundling concept yet — with exactly one gated feature today, a bundling layer would be speculative. Add it once there are enough entitlements to actually group (YAGNI, matches this project's established discipline elsewhere, e.g. why `NEXT_ACTION` stayed unwired rather than over-building).

### A.3 Who grants entitlements? — keep data-feeding as simple as possible
No existing role can safely do this — an org's own `ADMIN` must never be able to self-grant a paid feature. But this is a low-frequency, low-volume operation (the SaaS vendor's own team granting a feature to a handful of customer orgs) - it does not warrant a whole second authentication system, a new table, or a UI. Simplest viable mechanism, no new infrastructure at all:

- `PATCH /internal/organizations/{orgId}/entitlements` (grant/revoke), protected by a single shared secret key (an env var, e.g. `PLATFORM_ADMIN_KEY`, checked via a header like `X-Platform-Key`) - not a user account, not a login flow, not a role. Whoever operates the platform calls this directly (curl/Postman/a tiny internal script) when a customer's plan changes.
- No `platform_admins` table, no separate login screen. If this ever needs to be self-service (a real billing/subscription flow with its own users), that's a distinct, much later project - not something to build speculatively now.
- The only schema from Part A.2 that's still needed is `organization_entitlements` itself (what's granted); `entitlements` (the catalog of feature codes) can just be a Java enum to start (`FeatureEntitlement.EMPLOYEE_LEAVE_MANAGEMENT`) rather than a DB table - one more thing simplified away until there are enough entitlement types to justify admin-editing them as data.

### A.4 Enforcement
- **Backend**: a `RequireEntitlement("EMPLOYEE_LEAVE_MANAGEMENT")` method-level annotation + AOP aspect (same shape as `@PreAuthorize`, checked alongside it) that looks up the current `TenantContext`'s org against `organization_entitlements` (active, not expired/revoked) before allowing the method to proceed - 403 with a distinct error code (`FEATURE_NOT_ENTITLED`, not a generic 403) so the frontend can render an "upgrade" message rather than a bare permission error.
- **Frontend**: `GET /organizations/me/entitlements` (any authenticated user, returns the current org's active entitlement codes) fetched once at login into a small `EntitlementContext`. Nav items/routes for gated features render conditionally - not just disabled, hidden entirely, so orgs without the feature don't see a dead end (revisit if a deliberate "upgrade to unlock" teaser becomes wanted later).
- **Platform Admin's own endpoints** are a separate, small controller/auth path entirely outside the normal JWT-with-org-id flow (a platform admin isn't scoped to any single org) - simplest to keep this genuinely separate rather than trying to force it through the existing tenant-scoped `UserPrincipal`/`TenantContext` machinery.

---

## Part B — Leave / Attendance / Approvals Module

Every endpoint in this part requires the `EMPLOYEE_LEAVE_MANAGEMENT` entitlement (Part A.4). Within an entitled org, every feature below is available to that org's normal `ADMIN`/`EMPLOYEE` users - no new system role is introduced (see B.2).

### B.1 Schema
```
leave_types(id, organization_id, name, code, annual_entitlement_days, is_paid,
            carry_forward_allowed, max_carry_forward_days, active, created_at, updated_at)
  -- richer than generic master_data (needs entitlement/paid/carry-forward fields
  -- master_data doesn't have) - a dedicated table, not shoehorned into master_data.

employee_leave_balances(id, organization_id, employee_id, leave_type_id, year,
                         allocated_days, carried_forward_days, created_at, updated_at)
  -- stores ONLY the allocation (admin-set, defaults from leave_types, adjustable
  -- per-employee e.g. for a mid-year joiner). "Used" and "remaining" are NEVER
  -- stored - always computed at read time as SUM(leave_requests.total_days)
  -- WHERE status='APPROVED' for that employee+type+year. Storing a running "used"
  -- counter would drift the moment a request is edited/cancelled after approval;
  -- computing it is the same discipline already used for MasterDataService
  -- avoiding denormalized state that can go stale.

leave_requests(id, organization_id, employee_id, leave_type_id, start_date, end_date,
               is_half_day, total_days, reason, status, approver_id NULL,
               decided_at NULL, decision_note NULL, requested_at, created_at, updated_at)
  -- status: PENDING, APPROVED, REJECTED, CANCELLED

holidays(id, organization_id, holiday_date, name, created_at, updated_at)
  -- company holiday calendar - needed so leave day-counts and attendance status
  -- both know which days aren't working days. Admin-configurable, same soft-delete-
  -- never-hard-delete discipline as master_data.

attendance_records(id, organization_id, employee_id, attendance_date, check_in_at NULL,
                    check_out_at NULL, created_at, updated_at)
  -- status (Present/Absent/On Leave/Holiday/Weekend) is DERIVED, not stored:
  -- Present if check_in_at is set; On Leave if an APPROVED leave_request covers
  -- attendance_date; Holiday if a holidays row matches; Weekend if Sat/Sun;
  -- Absent otherwise (a working day, no clock-in, no leave). Same "compute, don't
  -- denormalize a status that can drift" reasoning as leave balances above.
```

`Employee` gains one new field: `manager_id` (nullable, self-referential UUID, same "raw id, service-layer-validated" pattern already used for `designation_id`/`city_id`/`state_id`) — see B.2.

### B.1a Leave Types are fully Admin-configurable, not a fixed list
Every field in `leave_types` is Admin-editable per org, not a hardcoded enum: name, code, **annual entitlement (allowed) days**, paid/unpaid, carry-forward rules. An org can define its own set from scratch (e.g. add a "Bereavement Leave" type nobody else has) — same "admin manages the list, dynamic, no code changes needed" principle as Master Data, just via a dedicated `LeaveTypeController`/`LeaveTypeService` (its own CRUD, not folded into `MasterDataController`, per B.1's rationale) rather than the generic masters screen. Soft-delete only (`active` flag) — a Leave Type already referenced by past `leave_requests`/`employee_leave_balances` is never hard-deleted.

`GET/POST/PUT /leave-types`, `DELETE /leave-types/{id}` (soft) — same ADMIN-only-for-mutations, any-authenticated-user-for-reads split already used for Master Data.

**Policy-change propagation rule**: editing a Leave Type's `annual_entitlement_days` (e.g. raising Casual Leave from 12 to 15 days) affects **only newly-created** `employee_leave_balances` rows going forward (e.g. next year's allocation, or a balance row created for a new joiner) — it does **not** retroactively rewrite `allocated_days` on balance rows that already exist for the current year. This avoids silently changing an employee's already-communicated entitlement mid-year out from under them. An Admin who genuinely wants to adjust a specific employee's *current* balance still can, directly, via the existing per-employee `allocated_days` field (B.1) — that's a deliberate, visible, individual action, not an automatic side effect of changing the org-wide policy default.

All six new tables are per-org RLS-protected exactly like every other tenant table in this app (same `tenant_isolation_*` policy pattern) — Part A's tables are the only deliberate exception to that rule.

### B.2 Approval routing — direct-manager hierarchy, no new role
`Employee.managerId` is the only structural addition needed. "Being a manager" is not a distinct role or permission tier — it's simply "someone else has your id in their `managerId` field." Both `ADMIN` and `EMPLOYEE` accounts can be set as somebody's manager. Routing rule for a submitted `LeaveRequest`:
1. If the requester has a `managerId` set (and that manager is active), the manager is the approver.
2. If the requester has no manager set, or their manager is inactive, it falls back to **any** `ADMIN` in the org (same "Admin has full oversight" principle already established everywhere else in this app - reassignment, master data, reporting) - avoids a request getting stuck in a dead-end queue.

`ADMIN` can always additionally approve/reject/override **any** request regardless of the manager-routing outcome — a normal escalation path, not a special case to build separately.

**Multi-level hierarchy (Manager → Team Lead → Member) falls out of `managerId` for free** — it's just a chain of pointers (`Member.managerId → TeamLead.id`, `TeamLead.managerId → Manager.id`), works at any depth, and "not mandatory" is inherent since the field is nullable at every level (a Member can point straight at a Manager, skipping the Lead tier, or have no manager at all). Approval routing (above) doesn't change with depth - it's always "my direct manager, Admin as fallback," regardless of how many tiers exist above that person.

What the multi-level case *does* require, as a genuinely new piece: **recursive visibility scoping**. Today's plan only covers "my direct reports." For a Manager to see everything happening across all their Team Leads' Members too (in "Pending My Approval," the Team Leave Calendar, the HR dashboard), a flat `WHERE manager_id = me` isn't enough - need a recursive query (Postgres recursive CTE) resolving "all subordinates at any depth" from a given employee. One shared helper (e.g. `EmployeeHierarchyService.getAllSubordinateIds(employeeId)`), reused everywhere visibility needs to account for the full chain rather than just one level.

**Named Teams - optional, not required for any of the above to work.** If wanted later: a small `teams(id, organization_id, name, lead_id NULL)` table + an optional `Employee.teamId`, purely an organizational/labeling layer (e.g. showing "Team Alpha" in the UI, or filtering the HR dashboard by named team) on top of the same `managerId` chain - the hierarchy and approval routing work identically with or without it. Skip it for the initial build; add it only if named-team browsing turns out to matter once the plain reporting-chain is in use.

This is deliberately the smaller of the two options discussed (vs. introducing a formal `MANAGER` role with its own permission set) — it adds just enough structure for direct-manager approval without touching the existing, well-tested `ADMIN`/`EMPLOYEE` security model. Revisit only if this proves insufficient once real usage shows a need for something like "a manager who can't also see Reports" or similar role-shaped restriction.

### B.3 Leave request lifecycle
1. Employee submits `POST /leave-requests` — validates sufficient remaining balance for that leave type/year (computed per B.1); **hard-blocks** over-allocation by default (an Admin can still approve an over-limit request explicitly as an override — the block is on *submission* convenience, not an absolute rule the approver can't bypass).
2. Notification (`LEAVE_REQUEST_SUBMITTED`) to the routed approver (B.2).
3. Approver calls `PATCH /leave-requests/{id}/decision` (`APPROVE`/`REJECT` + optional note) — sets `status`, `approverId`, `decidedAt`.
4. Notification (`LEAVE_REQUEST_APPROVED`/`LEAVE_REQUEST_REJECTED`) to the requester.
5. Requester can `PATCH /leave-requests/{id}/cancel` while `PENDING`, or while `APPROVED` if the leave hasn't started yet.
6. Every transition above also writes to a **new, separate** history table (`employee_activity_log` or similar - deliberately NOT the existing `activity_log`, since that table's schema is Lead-shaped (`lead_id NOT NULL`) and Leave/Attendance events have no associated Lead at all; forcing a nullable `lead_id` onto it to accommodate a genuinely different domain would blur what that table means. A small parallel table following the exact same pattern (`ActivityLogService`'s design, just scoped to `employee_id` instead of `lead_id`) is the more honest fit.

### B.4 Attendance (clock-in/clock-out)
- `POST /attendance/clock-in` / `POST /attendance/clock-out` — self-service, creates/updates the caller's own `attendance_records` row for today. A manager/Admin can view (not edit, initially) a report's attendance.
- **Personal Attendance Calendar** — month view, color-coded per day (Present/Absent/On Leave/Holiday/Weekend), showing clock-in/out times on the day cell.
- **Team Leave Calendar** — a *separate*, Admin/manager-facing month view overlaying every direct report's (or, for Admin, the whole org's) **approved leave only** — "who's out when," not full attendance detail. Distinct purpose from the personal calendar above (planning/coverage vs. individual record-keeping), so it's a separate view rather than a toggle on the same component.

### B.5 Dashboard / list views — the day-to-day surface, not just the two calendars
The calendars (B.4) answer "who's out on a given day." They don't cover the other things a manager/Admin needs to actually manage the team day-to-day - these need their own list/detail views, mirroring the CRM side's existing pattern (`LeadListPage`/`LeadDetailPage`/`ReportsPage`):

- **"My Requests"** (every employee) — a list of their own submitted `leave_requests` (status, dates, type, decision note if rejected) - their personal request history.
- **"Pending My Approval"** (anyone who is someone's manager, plus every Admin) — an inbox/queue of `leave_requests` routed to them and still `PENDING`, with inline Approve/Reject actions - this is the actual daily-use screen for a manager, distinct from and more important than the calendar for the approval workflow itself.
- **"All Requests"** (Admin-only) — org-wide list of every `leave_request` regardless of routing, filterable by employee/type/status/date range - the Admin-oversight equivalent of `LeadListPage`'s admin-only owner filter.
- **Employee Leave & Attendance detail page** — a single consolidated view per employee (accessible to that employee themselves, their manager, and any Admin), mirroring `LeadDetailPage`'s "everything about one record in one place": current balance per leave type (allocated/used/remaining), full leave request history, and an attendance summary/history for a selectable date range. This is the natural place to link to from "Pending My Approval" (click a request → see this employee's fuller picture, not just the one request in isolation) and from the Team Leave Calendar.
- **HR overview dashboard** (Admin/manager-facing, mirroring `ReportsPage`) — at-a-glance stats: how many people are on leave today, how many haven't clocked in yet today, count of pending approvals awaiting the viewer, and a simple leave-utilization breakdown (e.g. average days used per leave type this year). Same "each section fetches independently, one slow endpoint doesn't block the rest" pattern already established on `ReportsPage`.

### B.6 Notifications & history reuse
Extends the existing `NotificationType` enum (`LEAVE_REQUEST_SUBMITTED`, `LEAVE_REQUEST_APPROVED`, `LEAVE_REQUEST_REJECTED`) — reuses `NotificationService` as-is, no changes needed there. History is the new parallel table from B.3, not the Lead-centric `activity_log`.

---

## Phased Rollout

1. **Entitlement infrastructure** (Part A) — `organization_entitlements` table, the secret-key-protected internal grant/revoke endpoint, `RequireEntitlement` backend enforcement, frontend `EntitlementContext` + conditional nav/route gating. *Done when*: calling the internal endpoint with the platform key grants/revokes `EMPLOYEE_LEAVE_MANAGEMENT` for a specific org, and every subsequent phase's endpoints correctly 403 (`FEATURE_NOT_ENTITLED`) for a non-entitled org.
2. **Leave Types + Balances** — Admin configures leave policy per org; employees can view their own balance (allocated/used/remaining, computed).
3. **Leave Requests + Approval** — submission, manager/Admin-fallback routing, approve/reject/cancel, notifications, the new employee-activity history table, plus the "My Requests" / "Pending My Approval" / "All Requests" (Admin) list views (B.5) - the actual daily-use screens for the approval workflow, not just the underlying API.
4. **Attendance** — clock-in/out, Personal Attendance Calendar, and the Employee Leave & Attendance detail page (B.5) consolidating one employee's balance/history/attendance in one place.
5. **Team Leave Calendar + HR overview dashboard** — Admin/manager-facing team-wide approved-leave overlay, plus the at-a-glance stats dashboard (today's leave/attendance snapshot, pending-approvals count, utilization breakdown).

## Verification
- Every new tenant table (leave_types, employee_leave_balances, leave_requests, holidays, attendance_records, the new employee-activity log) gets the same explicit tenant-isolation integration test already standard for every table in this app.
- A dedicated test proves a non-entitled org's Leave/Attendance endpoints all return `FEATURE_NOT_ENTITLED`, and that granting the entitlement immediately unlocks them (no caching/staleness).
- A dedicated test proves the manager-routing fallback: an employee with no `managerId` set routes to an Admin; one with an inactive manager also falls back correctly.
- Balance computation is tested against directly-seeded `leave_requests` rows spanning multiple years/types, confirming "used"/"remaining" are always derived correctly, never stored/stale.
