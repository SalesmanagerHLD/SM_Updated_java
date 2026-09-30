# SalesManager CRM — Modules & Workflows

*Compiled 2026-08-04 for Notion import. Source: `CRM_IMPLEMENTATION.md`, `EMPLOYEE_ENTITLEMENT_PLAN.md`, and the architecture plan. This is a snapshot of the live system plus the confirmed backlog — treat it as a starting point to keep current in Notion going forward, not a permanently frozen export.*

---

## 1. Architecture Overview

| Layer | Choice |
|---|---|
| Backend | Spring Boot 3.2.5, Java 17, Maven — package root `com.salesmanager.crm` |
| Frontend | React 18 + TypeScript + MUI v9 + Vite, React Query, React Hook Form + Zod, react-router-dom v6, Recharts |
| Database | PostgreSQL (Aiven-hosted), Flyway migrations |
| Auth | JWT (short-lived access + rotating refresh), BCrypt passwords, roles `ADMIN` / `EMPLOYEE` |
| Hosting | Single AWS EC2 instance (see §19) |
| Repos | Two separate GitHub repos — `SM_Updated_java` (backend), `SM_Updated_React` (frontend), each with `main` + `develop` |

### Multi-tenancy (defense in depth)
- **App layer**: JWT carries `org_id` → `TenantContext` (ThreadLocal) populated per-request by `TenantFilter` → Hibernate `@Filter` auto-scopes every JPA query.
- **DB layer**: Postgres Row-Level Security on every tenant table, driven by `current_setting('app.current_org')`, plus a narrow `app.bypass_rls` escape hatch used only by login and by scheduled batch jobs that must legitimately cross tenants.
- **Golden rule**: `organization_id` is never accepted from client input — always derived server-side from the JWT.
- **Deliberate exception**: `organization_entitlements` (platform-level data *about* tenants) has no RLS at all, by design — it's never reachable through the normal tenant JWT flow anyway.

---

## 2. Master Data

One generic `master_data` table (`organization_id, type, code, label, sort_order, is_active, parent_id, metadata JSONB`) drives every dropdown, instead of a table per category.

**`MasterType` (11 values)**: `INDUSTRY, CITY, PRODUCT, BUSINESS_TYPE, DESIGNATION, VISIT_PURPOSE, NEXT_ACTION, LOST_REASON, INTEREST_LEVEL, LEAD_SOURCE, STATE`

- **State → City hierarchy**: `master_data.parent_id` links every `CITY` row to its `STATE` row. All 28 Indian states + Delhi seeded, with representative cities correctly parented.
- **Rich default seed data**: every newly-registered org gets a full starter set for all 11 types at registration (`MasterDataSeedService`).
- **Backfill tool**: `POST /internal/organizations/{orgId}/seed-master-data` (platform-key-protected) re-runs seeding for orgs that predate it — additive only, not safely re-runnable if already fully seeded.
- Interest Level codes are fixed (`HOT`/`WARM`/`COLD`) — business logic depends on the code, never the admin-editable label.
- Admin-only CRUD via `/masters/{type}`; reads open to any authenticated user. Soft-delete only (`is_active`).
- **Creatable fields** (Lead/Visit capture only, not Employee): every master-driven field used capturing a Lead/Visit has a paired `*Other` free-text column — pick an existing value or type your own, mutually exclusive, never auto-promoted into master data. Shared `CreatableMasterAutocomplete` component implements this everywhere.

---

## 3. Employee Management

- Full CRUD: create / update / deactivate (soft) / list (paginated) / get-by-id.
- Fields: full name, email (immutable after creation), phone (exactly 10 digits, matching Lead's contactNo), password (hashed), role (`ADMIN`/`EMPLOYEE`), designation, city, state, assigned products, active flag.
- **`managerId`** (nullable, self-referential UUID): "being a manager" is not a role — it's just "someone else has your id in their `managerId`." Multi-level hierarchies (Manager → Team Lead → Member) fall out of this for free, at any depth, entirely optional at every level. Self-reference and cycles rejected server-side.
- Role-gated: only Admins create/update/deactivate; any authenticated user reads.
- **Single Admin per org** (added 2026-08-03): every org has exactly one `ADMIN` (the "super admin"). `EmployeeService#create`/`update` reject creating or promoting a second one (409 `MultipleAdminNotAllowedException`); re-saving the existing Admin's own record unchanged is fine. This is who a Lost lead auto-reassigns to (§5).

---

## 4. Lead Management

- **Two-step progressive capture**: Step 1 (required) — Company, Contact Person, Contact No, City, Lead Source, Industry. Everything else optional, fillable later from the Lead Detail page.
- **Field order in "Required Details"** (2026-08-03 rework): Company Name, Business Type, Contact Person, Designation, Contact Number, State, City, Product, Interest Level, Decision Maker (checkbox), Next Follow-up Date, Expected Closure Date, Remarks, Status.
- **"Additional Details"** (collapsed accordion): Industry, Lead Source, Turnover, Email, Address, Current Product/Solution, Budget Range, Attachments. Auto-expands if a submit fails validation on `industryId`/`leadSourceId` (since Lead Source is required but lives in the collapsed section).
- **Removed from the form (UI-only)**: Requirements, Objections, Other Product — backend columns/DTO fields untouched, no migration, reversible.
- **Duplicate check**: `GET /leads/duplicates?contactNo=&companyName=` — informational only, never a hard block.
- **Ownership visibility**: `EMPLOYEE` sees/edits only leads they own; `ADMIN` sees the whole org. Cross-tenant/cross-owner reads return 404, never 403.
- **Status workflow**: `NEW, CONTACTED, NEGOTIATION, INTERESTED, LOST, CLOSED_WON, LAPSED`.
- **Interest Level × Status gating** (new `INTERESTED` status, 2026-08-03): Interest Level (Hot/Warm/Cold) is independent from Status **except** a lead whose Interest Level isn't Hot is locked to `INTERESTED` — the Status dropdown is hidden, showing a locked "Interested" chip + a standalone "Mark as Lost" button instead. Switching Interest Level to Hot reveals the full status dropdown. New leads default to `INTERESTED` unless created Hot (previously always `NEW`).
- **Lost workflow**: requires a Lost Reason, auto-sets Interest Level to Cold, AND auto-reassigns the lead to the org's single Admin (mirrors the manual `reassign()` notification/activity-log shape) — re-enters the reassignable pool instead of sitting invisibly under the rep who lost it. Lead list/detail show a distinct **"Unassigned – Lost"** tag.
- **Reassignment**: Admin-only, triggers a `LEAD_REASSIGNED` notification.
- **Past-date validation**: `nextFollowupDate`/`expectedCloseDate` reject a past date (today allowed) via shared `@NotPastDate`.
- **"Log this as today's visit"**: when checked, also asks Field vs. Telephonic, threaded through to the auto-created stub Visit.
- **Bulk import** from Excel/CSV — see §14.
- **Attachments** — see §23.5.

---

## 5. Visit Management

- Full CRUD: visit date/time, type, purpose, contact/qualification snapshot, products discussed, budget, decision maker, remarks, next visit date, status.
- **Field order** (2026-08-03 rework, mirrors Lead exactly): one "Required details" section — Contact Person, Designation, Contact Number, State, City, Product, Interest Level, Decision Maker, Next Visit Date, Remarks. Collapsed "Additional details" accordion — Email, Budget Range, Address. Requirements/Objections/Other Products removed (same UI-only removal as Lead's).
- **Pre-fill + sync-back** from the parent Lead — editing contact/qualification fields during a Visit writes back onto the Lead.
- **`@NotPastDate`** on `visitDate`/`nextVisitDate` (server + client, shared with Lead's date fields).
- **Edit-in-place status transitions**: Planned → Completed on the same record, never a forked second Visit. A client can never directly set `MISSED`.
- **Event-driven auto-generated stub Visits** (never full clones):
  - Creating a Lead with "Log this as today's visit" on → auto-creates a `COMPLETED` Visit dated today, typed per the rep's Field/Telephonic choice.
  - Setting a Next Follow-up Date → auto-creates a `PLANNED` stub Visit for that date, with a duplicate-prevention guard (existing visit same lead+date, any status → skip).
  - Implemented via `LeadCreatedEvent`/`FollowUpScheduledEvent`, handled by `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- **Same-day-visit advisory**: `GET /visits/same-day?leadId=&visitDate=` — non-blocking warning when manually adding a Visit for a lead that already has one that day (multiple visits/day is legitimate, different purposes).

---

## 6. Automated Status Transitions (scheduled jobs)

- **`MissedVisitJob`** — every 5 min, flips overdue `PLANNED` visits to `MISSED`; notifies the visit's owner + every org Admin.
- **`LapsedLeadJob`** — nightly, flips overdue Leads to `LAPSED`; owner notified per-lead, Admins get one digest per org per run.
- Both guarded by Postgres advisory locks (`pg_try_advisory_lock`); both operate cross-tenant in one batch via the RLS-bypass mechanism login also uses.
- **Reschedule bug fix**: rescheduling a `MISSED` visit (new `visitDate`/`scheduledTime`) now flips it back to `PLANNED` so it reappears in "Today's Follow-ups." `COMPLETED` is left alone — terminal state, only set via `updateStatus()`.

---

## 7. Notifications

- Per-employee inbox: `GET /notifications?unreadOnly=`, `PATCH /notifications/{id}/read`, always scoped to the caller.
- **Two-tier notification center**:
  - `GET /notifications/unread-count` — dedicated `COUNT` query, the badge's true source of truth (independent of dropdown page size).
  - `PATCH /notifications/read-all` — bulk-marks everything read in one JPQL bulk `UPDATE`.
  - Bell dropdown shows top 10 with a "See all" link → `/app/notifications` full paginated history page (search + CSV export, "Unread only" filter, client-side Type filter, "Mark all read," type icon/color per row).
  - Shared formatting (`describeNotification`/`parseNotificationPayload`/`getNotificationTarget`) centralized in `notificationFormat.ts` so bell + full page render/navigate identically.
- **Types**: `LEAD_REASSIGNED`, `VISIT_MISSED`, `LEAD_LAPSED`, `LEAD_LAPSED_DIGEST`, plus Leave module's `LEAVE_REQUEST_SUBMITTED`, `LEAVE_REQUEST_APPROVED`, `LEAVE_REQUEST_REJECTED`.
- Frontend polls every 30 seconds (upgrade path to push exists, see §Backlog → Firebase).
- No retention/archival — same as every other list in the app.

---

## 8. Activity Log / Lead Journey Timeline

- Purpose-built `activity_log` table (not Envers) — clean, human-readable entries at journey milestones: Lead created, Status changed, Reassigned, Visit logged, Visit completed, Visit auto-flagged Missed, Lead auto-flagged Lapsed.
- `owner_id`/`company_name` denormalized at write time (reflects "who owned this lead when this happened," avoids joins/N+1 on a broad feed).
- Deterministic pagination: `createdAt DESC, id DESC`.
- Two consumers, one API (`GET /activity?leadId=&ownerId=&type=`): per-Lead timeline on Lead Detail, and a broader `/app/activity` feed (personal for an Employee, org-wide+filters for Admin).
- **Separate parallel table for Leave**: `employee_activity_log` (`GET /employee-activity`) — different schema shape (no `lead_id`), same deterministic-pagination convention.

---

## 9. Reporting / Dashboards

`/app/reports` (Admin, plus `TEAM_VISIBILITY`-entitled managers — §20): pipeline summary (pie chart), conversion rate, visits completed-vs-missed, per-salesperson breakdown table. All native aggregate queries, org-scoped via the same Hibernate filter/RLS as everything else.

**Added 2026-08-03**:
- **Visits by Type** (`GET /reports/visits-by-type`) — Field vs. Telephonic, optionally date-ranged.
- **Interest Level × Status matrix** (`GET /reports/interest-level-status-matrix`) — Hot/Warm/Cold/Not Set rows × every `LeadStatus` column, lead counts.
- **Filterable All-Leads table** — reuses the Leads list page's existing filters (State/City/Date range/Status/Product).

**Added 2026-08-04 — Team Progress + Drill-down (§22).**

---

## 10. Theme Settings

Four dimensions on `Organization.themeSettings` / `Employee.themePreference` (JSONB, no migration needed to add a dimension):

1. **Primary color**
2. **Mode** (light/dark)
3. **Density**
4. **UI Style** (Standard / Minimalist — added 2026-08-03)

- Org-wide default (Admin-configurable) + optional per-employee override, falling back to the org default. `AppThemeProvider` merges `personal ?? org ?? hardcoded fallback`, caches the resolved theme in `localStorage`.
- **Minimalist UI**: a component-overrides layer (applied on top of density's, Minimalist wins on shared keys) — removes Paper/Card shadows, `MuiButton disableElevation`, replaces the sidebar's rounded "pill" active-nav highlight with a plain left-border indicator, lightens Chip/heading font weights, tightens `shape.borderRadius` (12→4). Never touches palette colors — purely less decoration.

---

## 11. Feature Entitlement / Licensing Infrastructure

Platform-wide, SaaS-style feature gating — deliberately separate from tenant data (describes what the *platform* offers and which *orgs* currently have access).

- **`FeatureEntitlement`** — Java enum (not yet a DB table): `EMPLOYEE_LEAVE_MANAGEMENT`, `TEAM_VISIBILITY`, `INVENTORY_MANAGEMENT`. (Planned: `PUSH_NOTIFICATIONS`, `CALENDAR_SYNC` — see Backlog.)
- **`organization_entitlements`** table — the one deliberate exception to "every table has RLS." Managed exclusively via a shared-secret-protected internal endpoint, never through the normal JWT/tenant flow.
- **Grant/revoke**: `PATCH /internal/organizations/{orgId}/entitlements/{code}` (`X-Platform-Key` header vs. `PLATFORM_ADMIN_KEY` env var) — upsert-based, optional expiry.
- **Enforcement**: `@RequireEntitlement(FeatureEntitlement.X)` method annotation + AOP aspect, alongside `@PreAuthorize`. Non-entitled org → 403 `FEATURE_NOT_ENTITLED`.
- **Frontend**: `GET /organizations/me/entitlements` fetched once per session into `EntitlementContext`; gated nav/routes render conditionally (hidden entirely, not just disabled).
- **No Plan/tier bundling yet** — deliberately deferred until there are enough entitlements to justify grouping (YAGNI).

---

## 12. Employee Leave / Attendance / Approvals Module

Gated behind `EMPLOYEE_LEAVE_MANAGEMENT`. Status: **COMPLETE**, 5 phases shipped and tested.

### 12.1 Schema
```
leave_types(id, organization_id, name, code, annual_entitlement_days, is_paid,
            carry_forward_allowed, max_carry_forward_days, active, created_at, updated_at)

employee_leave_balances(id, organization_id, employee_id, leave_type_id, year,
                         allocated_days, carried_forward_days, created_at, updated_at)
   -- stores ONLY allocation. "Used"/"remaining" ALWAYS computed at read time as
   -- SUM(leave_requests.total_days) WHERE status='APPROVED' — never stored, never stale.

leave_requests(id, organization_id, employee_id, leave_type_id, start_date, end_date,
               is_half_day, total_days, reason, status, approver_id NULL,
               decided_at NULL, decision_note NULL, requested_at, created_at, updated_at)
   -- status: PENDING, APPROVED, REJECTED, CANCELLED

holidays(id, organization_id, holiday_date, name, created_at, updated_at)

attendance_records(id, organization_id, employee_id, attendance_date, check_in_at NULL,
                    check_out_at NULL, created_at, updated_at)
   -- status DERIVED, never stored: Present if checked in; else On Leave if an approved
   -- leave_request covers the date; else Holiday; else Weekend; else Absent.
```
`Employee` gains `manager_id` (nullable, self-referential UUID).

### 12.2 Approval routing — direct-manager hierarchy, no new role
1. Requester's `managerId` (if set and active) is the approver.
2. No manager / inactive manager → falls back to **any** `ADMIN` in the org.
3. `ADMIN` can always additionally approve/reject/override any request regardless of routing.

Multi-level hierarchies (Manager → Team Lead → Member) fall out of `managerId` chains for free, at any depth — approval routing itself always stays "direct manager only." Full-chain visibility (for dashboards/calendars) needed a genuinely new piece: `EmployeeHierarchyService.getAllSubordinateIds()`, a recursive Postgres CTE, reused everywhere visibility must account for the whole chain, not just one level.

### 12.3 Lifecycle
1. `POST /leave-requests` — validates sufficient balance (hard-blocks over-allocation by default; Admin can override at decision time), rejects overlapping ranges, computes `totalDays` excluding weekends/holidays.
2. Notification (`LEAVE_REQUEST_SUBMITTED`) to the routed approver.
3. `PATCH /leave-requests/{id}/decision` (APPROVE/REJECT + note) sets status/approver/decidedAt.
4. Notification (`LEAVE_REQUEST_APPROVED`/`REJECTED`) to requester.
5. Cancellable while `PENDING`, or `APPROVED` if not yet started.
6. Every transition writes to `employee_activity_log` + fires a notification.

### 12.4 Attendance
- Self-service `POST /attendance/clock-in` / `clock-out`.
- **Personal Attendance Calendar** — month view, color-coded per day.
- **Team Leave Calendar** — separate Admin/manager view overlaying reports' *approved leave only* ("who's out when").

### 12.5 List/dashboard views
- **My Requests** (every employee) — own request history.
- **Pending My Approval** (any manager + all Admins) — inbox with inline Approve/Reject.
- **All Requests** (Admin-only) — org-wide, filterable.
- **Employee Leave & Attendance detail page** — one consolidated view (employee, their manager, or Admin): balance per type, request history, attendance summary.
- **HR overview dashboard** — today's on-leave/not-clocked-in counts, pending-approvals count, leave-utilization breakdown.

---

## 13. Platform Console

Standalone, non-tenant-scoped screen (`/platform-console`) for platform operators to manage entitlements across every org, replacing hand-crafted curl calls. Authenticates with the same shared `X-Platform-Key` (entered once, kept in `sessionStorage`, cleared on tab close). Lists every org with active entitlements as clickable chips (click to grant/revoke) + search + CSV export. Backed by `GET /internal/organizations` plus the existing grant/revoke/history endpoints.

---

## 14. Lead Bulk Import (Excel/CSV)

`/app/leads/import` (Admin only) — 3-step wizard backfilling historical clients straight into the Lead table (reuses Leads' existing structure — Visits/Activity/Reporting work for free).

- **Upload**: `.xlsx` (Apache POI) or `.csv` (hand-rolled RFC 4180 parser).
- **Preview** (`POST /leads/import/preview`): parses headers + first 10 rows, auto-suggests column mapping via header-name matching.
- **Commit** (`POST /leads/import/commit`): only `companyName`/`contactPerson`/`contactNo` hard-required (more lenient than interactive creation). Master-data fields resolved by case-insensitive label match, falling back to free-text `*Other` — never auto-promoted.
- **Duplicate/error handling**: a matching existing Lead (same phone/company) is skipped and reported; a row missing a required field is likewise skipped and reported — never aborts the batch.
- **No stub-visit spam**: imported leads do NOT publish `LeadCreatedEvent` — bulk historical backfill shouldn't auto-create hundreds of stub Visits. Still gets a normal `LEAD_CREATED` activity entry.
- Result summary: imported / skipped-duplicate / error counts with row-level detail.
- **Downloadable template** (added 2026-08-03): static `.xlsx` (`frontend/public/lead-import-template.xlsx`, regenerable via `scripts/generate_lead_import_template.py`) with one header row matching `LeadImportField`'s vocabulary + two example rows.

---

## 15. Filter + CSV Export (app-wide)

Every list table has a quick-search box and a CSV export button, via two shared pieces:
- `frontend/src/utils/exportToCsv.ts` — dependency-free RFC 4180 CSV builder.
- `frontend/src/components/TableToolbar.tsx` — search box + filter controls (children) + export button.

Server-paginated tables export ALL rows matching current filters (a one-off larger-page-size call), not just the visible page. Quick-search filters only the currently-loaded page. Small fully-loaded aggregate tables get export without search.

---

## 16. UI/UX & Responsive Design

- Responsive shell: temporary overlay drawer below `md`, permanent above; AppBar collapses below `sm`.
- Sidebar grouped into labeled sections: Dashboard → **Sales** (Leads, Activity) → **Leave & Attendance** (entitled orgs only) → **Administration** (Admin-only — Masters, Employees, Reports, Leave Types/Holidays when entitled) → Settings.
- Mobile-friendly lists: table → card layout below `sm`. Dialogs go fullscreen on mobile.
- Visual polish: non-shouty buttons, softer card shadows/borders, tinted table headers, pill-style active sidebar nav, icon-badge stat cards, time-of-day greeting header. Color-agnostic throughout.

---

## 17. Inventory + Invoicing → Quotation Module

*Separate from the standalone "Smart Inventory Management for Small Vendors" product (§Appendix) — this is a module inside SalesManager CRM itself, for SalesManager's B2B sales-team users to generate a quotation PDF for a closed deal.*

Gated behind **one shared entitlement**, `INVENTORY_MANAGEMENT` (Inventory + Invoicing are not separately licensed).

### 17.1 Schema
```
products(id, organization_id, sku nullable, name, description, unit_price NUMERIC(12,2),
         tax_rate_percent NUMERIC(5,2) default 0, unit_of_measure, stock_quantity INTEGER
             default 0 CHECK (stock_quantity >= 0), low_stock_threshold nullable,
         is_active, created_at, updated_at)

stock_movements(id, organization_id, product_id FK, quantity_change INTEGER signed,
                 reason CHECK IN ('MANUAL_ADJUSTMENT','INVOICE'), reference_id nullable,
                 note, created_by, created_at)
   -- append-only ledger. products.stock_quantity is a maintained counter; every mutation
   -- inserts a matching stock_movements row in the SAME transaction, so
   -- SUM(quantity_change) GROUP BY product_id always reconciles.

invoice_number_counters(organization_id, year, next_number default 1, PK(org, year))
   -- "QUO-2026-0001" style, resets each January. Allocated via SELECT...FOR UPDATE.

invoices(id, organization_id, invoice_number unique per org, lead_id nullable FK,
         owner_id, created_by, customer_name, customer_contact_person, customer_phone,
         customer_email, customer_address, customer_gstin nullable, invoice_date,
         subtotal/tax_total/grand_total NUMERIC(12,2), status (UNPAID|PAID), notes,
         created_at, updated_at)
   -- can exist standalone with no Lead; if a Lead is picked to prefill, details are
   -- SNAPSHOTTED at creation, never re-synced later.

invoice_line_items(id, organization_id, invoice_id FK, product_id nullable (catalog line),
                    description, quantity NUMERIC(10,2) CHECK > 0, unit_price NUMERIC(12,2)
                    (snapshotted), tax_rate_percent (snapshotted), line_subtotal,
                    line_tax_amount, sort_order, created_at)
```

### 17.2 Key behaviors
- Adding a catalog product to a quotation **auto-deducts stock** — invoicing is a real stock-out event.
- Insufficient stock → **409** (`InsufficientStockException`, same shape as `InsufficientLeaveBalanceException`), backed by the DB `CHECK (stock_quantity >= 0)`.
- **Lock-ordering discipline**: an invoice with several catalog lines holds row locks on every referenced product for the request's duration — distinct product ids are always sorted ascending before locking, counter row locked last, to avoid deadlocks between concurrent invoices.
- All user-supplied text HTML-escaped before landing in the PDF (`InvoicePdfHtmlBuilder`).
- Only active, same-org products are invoiceable (cross-tenant/inactive/nonexistent → `NotFoundException`, information-hiding).
- Money rounding centralized (`setScale(2, RoundingMode.HALF_UP)`).
- **Create-only in v1** — no edit/delete/void; a mistake means creating a corrected new quotation. No compensating stock-reversal logic needed.
- PDF via `openhtmltopdf` (Apache-2.0), a fixed HTML+CSS layout.
- `organizations` gained `billing_address`/`billing_gstin`/`billing_phone` for the PDF's seller header, managed via `GET/PUT /organizations/me/billing-profile`.

### 17.3 "Invoice" → "Quotation" rename (2026-08-03)
Pure label rename, user-facing only — nav label, page titles/buttons, empty states, table headers, PDF title (`INVOICE`→`QUOTATION`), document-number prefix (`INV-2026-0001`→`QUO-2026-0001`). Routes, component/file names, entity/table names, and all behavior (stock deduction, numbering, PDF generation) untouched.

**Status: BUILT and live**, 3 phases (Product catalog+stock → Invoice creation → PDF+billing profile), fully tested.

---

## 18. Recent Validation / Data-Integrity Improvements

- Employee `phone` validated as exactly 10 digits.
- `Lead.nextFollowupDate`/`expectedCloseDate` and `Visit.nextVisitDate` reject past dates.
- Multipart upload limit raised to 10MB.
- Activity feed pagination gained a secondary `id` sort key for deterministic ordering.
- Auto-stub-Visit duplicate-prevention guard + same-day-visit advisory (§5).
- Field/Telephonic choice on "log as today's visit" (§4/§5).

---

## 19. AWS Deployment

Live on a single EC2 instance (`t2.micro`, free-tier, ap-south-1/Mumbai):

- **nginx** serves the built React SPA and reverse-proxies `/api/` to the backend on `localhost:8080` — frontend calls a relative `/api/v1` path, so no CORS needed, and the build isn't tied to a specific IP/domain.
- **Spring Boot backend** runs as a systemd service (`salesmanager-backend`), pointed at the same real Aiven-hosted Postgres used in dev — no RDS, no data migration.
- **Secrets**: `JWT_SECRET`/`PLATFORM_ADMIN_KEY` are strong production values in a root-only `/etc/salesmanager/backend.env` referenced by the systemd unit's `EnvironmentFile=` — never committed.
- **TLS**: a free Let's Encrypt cert for `13-235-115-177.nip.io` (a wildcard-DNS-to-IP service, zero cost/signup). Mobile browsers were timing out on bare-HTTP-to-bare-IP (a phishing heuristic); **the app should be accessed at `https://13-235-115-177.nip.io/`** going forward. Certbot auto-renews (expires 2026-10-19).
- **Not yet built**: Terraform/CDK IaC (provisioned via AWS CLI directly), auto-scaling, second AZ, managed load balancer, a purchased/branded domain — all deliberately deferred until real usage justifies the complexity.

---

## 20. Testing & Verification

- 212 backend integration tests (Testcontainers, real Postgres per run) — every feature above, including explicit tenant-isolation proofs per table/feature.
- Every phase re-verified (`mvn clean verify`) and smoke-tested live against the real database (and the live EC2 deployment) before being considered done.
- Frontend: `npm run build` + lint clean at every step.

---

## 21. Manager Team Visibility (`TEAM_VISIBILITY` entitlement)

Closes a real gap: the `managerId` hierarchy had never been wired into the core Sales module — a manager with reports saw exactly what an individual contributor saw. Opt-in per org, like every other licensed feature.

- **Mechanism**: `EmployeeHierarchyService.getTeamVisibilityScope(orgId, viewerId)` — returns the viewer's full subordinate chain if the org has `TEAM_VISIBILITY`, else empty. Checked programmatically via `EntitlementService#isEntitled`, not the `@RequireEntitlement` AOP aspect.
- **Different gating shape**: `EMPLOYEE_LEAVE_MANAGEMENT` gates whole endpoints; `TEAM_VISIBILITY` expands the *result set* of always-reachable endpoints (`GET /leads`, `/visits`, `/activity`, `/reports/*`).
- **Leads/Visits/Activity**: an entitled manager sees themself + every subordinate at any depth; an explicit `ownerId` filter is honored only if it names someone inside scope. Non-managers / non-entitled orgs get the old self-only behavior unchanged.
- **Read-only, not an edit right**: `getById` expanded the same way, but `update`/`updateStatus`/`reassign` stay owner-or-Admin-only.
- **Reports**: previously hard Admin-only; now open to any authenticated user with `ReportingService#resolveOwnerScope()` doing real enforcement (`null` unrestricted for ADMIN / team scope for an entitled manager / 403 for anyone else).
- **Frontend**: `TeamVisibilityRoute` guards `/app/reports` + `/app/team/:employeeId`; Owner filter/column now also renders for an entitled manager, not just Admin.
- **Still deliberately not built**: Named Teams (see Backlog) — this feature works identically with or without a labelled "team" concept.

---

## 22. Team Progress + Read-Only Team Member Drill-Down (2026-08-04)

Answers "as an admin/manager, let me check what my team members are working on" — read-only, no edit/reassign actions.

- **`GET /reports/team-progress`** (`ReportingService#teamProgress`) — one row per team member: lead counts by every status, visits due today, visits due next 7 days, last-activity timestamp. Reuses the same `TEAM_VISIBILITY`-gated scoping as every other Reports endpoint, but lists individual members: ADMIN sees every other active employee, an entitled manager sees their subordinate chain (never themself), anyone else 403.
- **Reports page**: new "Team Progress" table section (same page, no new nav item).
- **`/app/team/:employeeId`** (`TeamMemberDetailPage`) — clicking a member's row opens a read-only page: assigned leads (company, contact, status, interest level, next follow-up) + recent activity feed. No edit controls, leads not individually clickable (deliberate "list only" scope — full per-lead drill-down is a possible future iteration). No new backend endpoint needed — `GET /leads?ownerId=` / `GET /activity?ownerId=` already scope correctly.

---

## 23. Change Log Highlights (chronological, most recent additions)

### 23.1 Demo Feedback Batch (2026-08-03) — Lead Form, INTERESTED Status, Lost Auto-Reassignment, Single-Admin, Widgets, Attachments, Import Template
See §4 (Lead form + INTERESTED + Lost reassignment), §3 (single-Admin), §14 (import template). Two new items not covered elsewhere:

- **Employee dashboard widgets** (`TodaysFollowUpsPage.tsx`, the plain-EMPLOYEE landing page): "Lapsed Calls" (`GET /leads?status=LAPSED`) and "Upcoming Follow-ups" (`GET /visits?status=PLANNED&dateFrom=&dateTo=`, next 7 days) — both reused existing, already-scoped endpoints, no new backend query needed.
- **Lead Attachments** (new `leadattachment` package): file upload/list/download/delete on the Lead detail page, local disk storage via an `AttachmentStorageService` interface (`LocalFilesystemAttachmentStorageService` today, base dir via `LEAD_ATTACHMENT_STORAGE_DIR`) — built so an S3-backed implementation later is a drop-in second bean, no entity/controller/migration changes. 10MB cap, images/PDF/Word/Excel/plain-text only. Only reachable from Lead Detail (a brand-new Lead in the create dialog has no id yet).

### 23.2 Post-Demo Follow-Up Fixes (2026-08-03)
- **Attachment upload 500 bug** — root cause was a missing `LEAD_ATTACHMENT_STORAGE_DIR` env var on EC2 (relative dev-fallback path resolved to an unwritable `/data/lead-attachments`, since the systemd unit has no `WorkingDirectory=`). Fixed via config, not code — created `/opt/salesmanager/data/lead-attachments` and set the env var.
- **Visit form restructuring** — see §5.
- **Invoice → Quotation rename** — see §17.3.

### 23.3 Minimalist UI Style — see §10.
### 23.4 Team Progress + Drill-down — see §22.

---

## Backlog / Planned (not yet built, deliberately)

### Firebase Push Notifications
*Requested 2026-07-31.* Wires FCM as an instant-delivery channel on top of the existing poll-based system (`notifications` table stays the source of truth). Gated behind planned `FeatureEntitlement.PUSH_NOTIFICATIONS`.

- New `device_tokens` table (`employee_id, token unique, platform WEB|ANDROID|IOS, last_seen_at`).
- `NotificationService.create()` publishes `NotificationCreatedEvent` → `PushNotificationEventListener` (`AFTER_COMMIT`, mirrors `LeadVisitEventListener`'s tenant-activation dance) → `PushNotificationService` wrapping the Firebase Admin SDK.
- Dead tokens (FCM `UNREGISTERED`/`INVALID_ARGUMENT`) auto-deleted; any other failure logged and swallowed — must never fail the originating request/job.
- Config via env vars, `FirebaseApp` bean no-ops gracefully if unset (local dev safe).
- Frontend: `firebase` npm package, a service worker for background messages, `usePushNotifications()` hook (permission request → token → `POST /device-tokens`), 30s poll kept as a fallback.
- **External prerequisite (user, not assistant)**: a Firebase project, a service-account JSON key, and a Web Push (VAPID) key pair.
- Architected for mobile (ANDROID/IOS token types) from day one, though no Flutter client exists yet — no backend rework needed when one arrives.

### Calendar Sync — Google Calendar + Outlook
*Requested 2026-07-31, alongside push.* Scheduled Visits auto-appear on a rep's own calendar. Gated behind planned `FeatureEntitlement.CALENDAR_SYNC`.

- Per-employee `calendar_connections` table (`provider GOOGLE|OUTLOOK`, encrypted access/refresh tokens, `calendar_id`, `last_sync_error`) — one active connection per employee.
- New `TokenEncryptionService` (AES-GCM) — first "encrypt at rest" need in the codebase.
- Standard OAuth authorization-code flow for both providers (plain REST, no SDK needed): `/calendar-connections/{google|outlook}/authorize` + `/callback`, `DELETE /calendar-connections/me` to disconnect.
- Sync reuses the exact `LeadVisitEventListener` pattern: `Visit` gets 3 new columns (`external_calendar_event_id`, `calendar_sync_status`, `calendar_sync_error`); `create()`/`update()` publish `VisitCalendarSyncEvent` → `CalendarSyncEventListener` (`AFTER_COMMIT`) → `CalendarSyncService` creates/updates the external event (1-hour default duration, or all-day if no scheduled time). Failures set `FAILED` status, logged, never roll back the Visit save.
- **External prerequisites (user, not assistant)**: a Google Cloud Console OAuth client (`calendar.events` scope) and a Microsoft Entra app registration (`Calendars.ReadWrite`+`offline_access`), both with the backend's callback URL registered.
- **Build order**: Push first (self-contained, one external account); Calendar Sync second (two OAuth providers, longer external setup lead time).

### Named Teams / formal org-structure UI
*Requested 2026-07-23.* A labelled "Team" concept (multiple teams, each with a name + a designated Team Lead) plus an admin UI to create teams and assign members — layered on top of the existing `managerId` chain, not a second parallel structure.

- **Critical naming rule**: never use a bare `Lead`/`lead` identifier for the "Team Lead" role — collides with the CRM's own `Lead` entity. Always `TeamLead`/`teamLeadId`.
- **Recommended shape**: `teams(id, organization_id, name, team_lead_employee_id nullable)` + `employees.team_id` (nullable FK) — ONE team per employee. Assigning an employee to a team **sets their `managerId`** to that team's lead in the same action — Team is a named label over the existing reporting chain, never allowed to drift out of sync.
- **No new Role** — "Team Lead" is structural, exactly like "Manager" isn't a role today.
- Reuses `EmployeeHierarchyService`/`TEAM_VISIBILITY` end-to-end for free — no changes needed to Leave approval routing, Team Visibility, or Reports.
- Open question: free/core admin feature, or a new gated `FeatureEntitlement`? Default assumption is free/core unless there's a monetization reason.

### MVP2 Backlog (smaller items)
- Remove "High" as an Interest Level option (quick masters-data cleanup).
- Full read-only per-lead drill-down from the Team Member Detail page (currently list-only, §22).
- Unified activity timeline UI polish once more real data exists.
- "Log a call" quick-action for Telephonic visits (lighter than the full Visit form).
- Wire the "Next Action" master into the Visit form (defined but unused today).
- Global search (leads by company/contact/phone, `pg_trgm`/`ILIKE`) once volume justifies it.
- Bulk reassignment (multi-select leads → one employee).
- Multiple Contacts per Lead (v2 — MVP1 keeps a single Contact Person).
- Visit-level reassignment on a same-day leave conflict + a `VISIT_REASSIGNMENT_REQUIRED` notification, triggered from `LeaveRequestService#decide` when an approved leave day collides with already-scheduled visits.
- Richer post-sale Account management (multiple contacts, renewal/contract tracking) — a separate, bigger future module, not folded into Lead-based import.
- Barcode scanning for Inventory product lookups (mobile camera scan via `mobile_scanner` + external keyboard-wedge HID scanners — the latter needs zero SDK integration and already works on the web app today with a focused text input). Small backend gap to close first: a fast exact-SKU lookup endpoint.

### Infrastructure
- Real purchased/branded domain + proper TLS (currently the free `nip.io` + Let's Encrypt setup).
- Terraform/CDK IaC for the deployed infrastructure (currently provisioned by hand via AWS CLI).

---

## Appendix: Separate Product — "Smart Inventory Management for Small Vendors"

*Not part of SalesManager CRM — a separate, independent product (different codebase, repos, DB, deployment, customers: small retail vendors, not B2B sales orgs). Noted here only because it deliberately reuses SalesManager's proven architectural patterns. Working title "InventoryOS," placeholder.*

### Patterns reused directly from SalesManager
Multi-tenancy (`organization_id` + Hibernate filter + RLS), feature/plan entitlement infrastructure, generic master-data table, the notification center, event-driven side effects (`ApplicationEventPublisher` + `AFTER_COMMIT` listeners), scheduled jobs with advisory locks, the "append-only ledger, never a mutable balance" discipline (core to inventory correctness — stock is `SUM()` over a `StockMovement` ledger, exactly like Leave Balance's used/remaining are always computed, never stored), aggregate reporting via JPQL projections, the two-repo + EC2/nginx/systemd deployment shape, and the platform-level oversight console pattern (reused for a cross-vendor support-ticket queue).

### New domain concepts (this product only)
Store (per-location stock), Product (with `tracksBatches`/`tracksVariants` flags — a variant is just another Product row with an optional `parentProductId`), ProductBatch (expiry/FEFO consumption), Sale + SaleLineItem (POS), Supplier + PurchaseOrder + GoodsReceipt, Customer + CustomerCreditMovement ledger, SupportTicket + SupportTicketMessage (in-app support queue, not live chat).

### Genuinely new infrastructure needed
PDF generation for GST invoices, WhatsApp Business API integration (invoice sharing — Phase 2, needs a real Meta/Twilio account), Razorpay subscription billing (needs a real account + webhook handling to flip plan tiers — genuinely new, since SalesManager orgs are provisioned directly, never self-serve-paid), browser camera-based barcode scanning.

### Phased build order
1. Scaffolding + auth + multi-tenancy (same cross-org isolation proof as SalesManager Phase 0).
2. Master data + Store + Product (incl. batch/variant flags) + Stock ledger — the hardest-to-retrofit piece, get it right first.
3. POS/Billing + Customer — the actual daily workflow, demoable alone.
4. Purchases + Suppliers + Goods Receipt.
5. Low-stock/expiry/payment-due alerts + Notification Center (reused wholesale) + core Reports.
6. Support Ticket module (vendor-facing + platform console).
7. Entitlement/plan tiers wired to real limits.
8. Phase 2 (separate effort, each with its own external-dependency setup): Razorpay billing, WhatsApp sharing, PDF GST invoices, AI insights, barcode hardware polish, Flutter mobile.

**Before any real deployment**: this needs genuinely new AWS resources (separate EC2, separate DB) — requires explicit go-ahead first, same standing rule as SalesManager.
