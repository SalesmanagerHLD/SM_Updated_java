# SalesManager CRM — Implementation Documentation

_Snapshot of everything built so far, as of 2026-07-20. This documents the CURRENT, working system — for the original functional spec and forward-looking architecture decisions, see the plan file (`shimmying-seeking-gray.md`), `salesmanager-deep-research-report.md`, and `EMPLOYEE_ENTITLEMENT_PLAN.md`._

## 1. Architecture Overview

- **Backend**: Spring Boot 3.2.5, Java 17, Maven. Package root `com.salesmanager.crm`.
- **Frontend**: React 18 + TypeScript + MUI (Material UI) v9 + Vite. React Query (TanStack) for server state, React Hook Form + Zod for forms, react-router-dom v6, Recharts for charts.
- **Database**: PostgreSQL (Aiven-hosted). Flyway-managed migrations, currently at `V10`.
- **Multi-tenancy**: shared schema, every tenant table carries `organization_id`. Enforced at two layers:
  - App layer: JWT carries `org_id`; a `TenantContext` (ThreadLocal) populated per-request by `TenantFilter`; a Hibernate `@Filter` auto-scopes every JPA query.
  - DB layer (defense-in-depth): Postgres Row-Level Security on every tenant table, driven by `current_setting('app.current_org')` + a narrowly-scoped `app.bypass_rls` escape hatch (used only by login, and by scheduled batch jobs that must legitimately operate across all orgs at once).
  - `organization_id` is never accepted from client input — always derived server-side.
  - **One deliberate exception**: `organization_entitlements` (Section 11) is platform-level data *about* tenants, not tenant-scoped data — it has no RLS at all, by design.
- **Auth**: JWT (short-lived access + rotating refresh token), BCrypt passwords, two roles — `ADMIN` and `EMPLOYEE` — via `@PreAuthorize` plus service-layer ownership checks.
- **Hosting**: live on AWS EC2 — see Section 18 for the full deployment architecture. GitHub: backend and frontend are two separate repositories (`SM_Updated_java`, `SM_Updated_React`), plus `SM_Updated_Mobile` for the mobile app — all migrated to the `SalesmanagerHLD` GitHub organisation (previously under the personal `mssaurabh22` account).
- **Branching**: as of 2026-07-20, both repos also have a `develop` branch (created off `main` at the same commit, nothing diverged yet) for ongoing work going forward — `main` still reflects what's actually deployed to EC2, per commit above.

## 2. Master Data

One generic `master_data` table (`organization_id, type, code, label, sort_order, is_active, parent_id, metadata JSONB`) drives every dropdown in the app, rather than a separate table per category. A fixed `MasterType` enum currently has **11 values**:

`INDUSTRY, CITY, PRODUCT, BUSINESS_TYPE, DESIGNATION, VISIT_PURPOSE, NEXT_ACTION, LOST_REASON, INTEREST_LEVEL, LEAD_SOURCE, STATE`

- **State → City hierarchy**: `master_data.parent_id` links every `CITY` row to its `STATE` row. All 28 Indian states + Delhi are seeded, with a representative set of cities correctly parented.
- **Rich default seed data**: every newly-registered org gets a full starter set for all 11 types (`MasterDataSeedService`) at registration time.
- **Backfill tool for pre-existing orgs**: `POST /internal/organizations/{orgId}/seed-master-data` (platform-key-protected, see Section 13) re-runs the same seed pass for an org that predates this seeding or otherwise never received it. Purely additive — never touches/overwrites rows an admin already entered by hand, and not safely re-runnable if seeding already fully succeeded (no duplicate-code guard, unlike the normal admin CRUD path) — a one-off backfill tool for a genuine gap, not a general idempotent operation.
- Interest Level codes are fixed (`HOT`/`WARM`/`COLD`) — business logic depends on these exact codes, not admin-editable labels.
- Admin-only CRUD via `/masters/{type}`; reads open to any authenticated user. Soft-delete only (`is_active` flag).
- **Creatable fields** (Lead/Visit capture only, not Employee): every master-driven field used when capturing a Lead or Visit has a paired `*Other` free-text column. A rep can either pick an existing master value or type their own — mutually exclusive per field, and a typed value is **never** auto-promoted into master data. A shared `CreatableMasterAutocomplete` component implements this "pick or type" UX everywhere it's needed.

## 3. Employee Management

- Full CRUD: create/update/deactivate (soft), list (paginated), get-by-id.
- Fields: full name, email (immutable after creation), phone (**validated as exactly 10 digits**, matching Lead's contactNo), password (hashed), role (`ADMIN`/`EMPLOYEE`), designation, city, state, assigned products, active flag.
- **`managerId`** (nullable, self-referential UUID, added for the Leave/Attendance module — Section 12): "being a manager" is not a separate role, just "someone else has your id in their `managerId`." Multi-level hierarchies (Manager → Team Lead → Member) fall out of this for free, at any depth, entirely optional at every level. Self-reference and cycles are rejected server-side (a bounded-depth chain walk on create/update).
- Role-gated: only Admins can create/update/deactivate; any authenticated user can read.
- **Single Admin per org (added 2026-08-03)**: every organization has exactly one `ADMIN` (the "super admin") — `EmployeeService#create`/`update` reject creating or promoting a second one (409 `MultipleAdminNotAllowedException`); re-saving the existing Admin's own record with `role=ADMIN` unchanged is still fine. See Section 23 for the full rationale (it's what a Lost lead is reassigned to).

## 4. Lead Management

- **Two-step progressive capture**: Step 1 (required) is Company, Contact Person, Contact No, City, Lead Source, Industry. Everything else is optional, filled in later from the Lead Detail page.
- **Duplicate check**: `GET /leads/duplicates?contactNo=&companyName=` — informational only, never a hard block.
- **Ownership visibility**: an `EMPLOYEE` only ever sees/edits Leads they own; an `ADMIN` sees the whole org. Cross-tenant/cross-owner reads return 404 (never 403).
- **Status workflow**: `NEW, CONTACTED, NEGOTIATION, INTERESTED, LOST, CLOSED_WON, LAPSED`. Interest Level (Hot/Warm/Cold) is a deliberately independent axis from Status, EXCEPT that (added 2026-08-03) a lead whose Interest Level isn't Hot is locked to `INTERESTED` — see Section 23.
- **Lost workflow**: requires a Lost Reason, auto-sets Interest Level to Cold, AND (added 2026-08-03) auto-reassigns the lead to the org's Admin — see Section 23.
- **Reassignment**: Admin-only, triggers a `LEAD_REASSIGNED` notification.
- **Past-date validation**: `nextFollowupDate`/`expectedCloseDate` reject a past date (today is allowed) via the shared `@NotPastDate` constraint (Section 5 shares this with Visit's date fields).
- **"Log this as today's visit" now asks which kind**: when checked, the Lead create form also asks Field vs. Telephonic, threaded through to the auto-created stub Visit (see Section 5) — previously this was silently always hardcoded to Field, mislabeling phone-originated leads.
- **Bulk import from Excel/CSV** (Section 14) — for backfilling an org's pre-existing/historical client base.

## 5. Visit Management

- Full CRUD, fields mirroring the spec (visit date/time, type, purpose, contact/qualification snapshot, products discussed, budget, decision maker, objections, remarks, next visit date, status).
- **Pre-fill + sync-back** from the parent Lead; editing contact/qualification fields during a Visit writes back onto the Lead.
- **`@NotPastDate`** on `visitDate` and `nextVisitDate` (server-enforced, mirrored client-side via the DatePicker's `minDate`). The same constraint is reused for Lead's `nextFollowupDate`/`expectedCloseDate` — one consistent "today or later" semantic across the app, not a Visit-specific rule.
- **Edit-in-place status transitions**: Planned → Completed on the same record, never a forked second Visit. A client can never directly set `MISSED`.
- **Event-driven auto-generated stub Visits** (never full clones):
  - Creating a Lead with "Log this as today's visit" on auto-creates a `COMPLETED` Visit dated today, typed per the rep's Field/Telephonic choice (Section 4).
  - Setting a Next Follow-up Date auto-creates a `PLANNED` stub Visit for that date — **now with a duplicate-prevention guard**: if a Visit already exists for that lead+date (any status), the auto-stub is silently skipped rather than double-booking the day.
  - Implemented via Spring application events (`LeadCreatedEvent`/`FollowUpScheduledEvent`) handled by `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- **Same-day-visit advisory** (`GET /visits/same-day?leadId=&visitDate=`): a non-blocking warning shown when manually adding a Visit for a lead that already has one that day — multiple visits per lead per day are a legitimate, supported scenario (different purposes), so this is a heads-up, never a hard block.

## 6. Automated Status Transitions (scheduled jobs)

- **`MissedVisitJob`**: every 5 minutes, flips overdue `PLANNED` visits to `MISSED`. Notifies both the visit's owner and every org Admin.
- **`LapsedLeadJob`**: nightly, flips overdue Leads to `LAPSED`. Individual owner notified per-lead; Admins get one digest notification per org per run.
- Both guarded by Postgres advisory locks; both operate cross-tenant in one batch via the same RLS-bypass mechanism login uses.
- **Bug fix (2026-07-23)**: rescheduling a `MISSED` visit (editing its `visitDate`/`scheduledTime` via `VisitService#update`) previously left `status` untouched, so a visit given a brand-new future date/time stayed permanently stuck as `MISSED` - invisible to "Today's Follow-ups" (which only shows `PLANNED`) even though it was legitimately upcoming again. `update()` now flips `MISSED` back to `PLANNED` whenever a reschedule (new date and/or time) is part of the request. `COMPLETED` is deliberately left alone - it's a terminal state set only via `updateStatus()`, and correcting an incidental date detail on an already-completed visit shouldn't silently un-complete it.

## 7. Notifications

- Per-employee inbox: `GET /notifications?unreadOnly=`, `PATCH /notifications/{id}/read`. Always scoped to the caller's own notifications.
- Types: `LEAD_REASSIGNED`, `VISIT_MISSED`, `LEAD_LAPSED`, `LEAD_LAPSED_DIGEST`, plus (Leave module) `LEAVE_REQUEST_SUBMITTED`, `LEAVE_REQUEST_APPROVED`, `LEAVE_REQUEST_REJECTED`.
- Frontend: bell icon with unread badge, 30-second poll.

## 8. Activity Log / Lead Journey Timeline

- A purpose-built `activity_log` table — clean entries at the moments that matter: Lead created, Status changed, Reassigned, Visit logged, Visit completed, Visit auto-flagged Missed, Lead auto-flagged Lapsed.
- `owner_id`/`company_name` denormalized at write time.
- **Deterministic pagination**: sorted by `createdAt DESC, id DESC` — the secondary `id` tiebreaker guarantees stable ordering across pages even when multiple entries share an identical timestamp (common when a Lead's own entry and its auto-stub-Visit's entry are written moments apart in the same request).
- Two consumers, one API (`GET /activity?leadId=&ownerId=&type=`): per-Lead timeline, and a broader `/app/activity` feed.
- **Separate, parallel table for the Leave module**: `employee_activity_log` (`GET /employee-activity`) — deliberately NOT the same table, since this one's schema is Lead-shaped (`lead_id NOT NULL`) and Leave/Attendance events have no Lead at all. Same `createdAt, id` deterministic-pagination convention.

## 9. Reporting / Dashboards

Admin-only `/app/reports` page: pipeline summary (pie chart), conversion rate, visits completed-vs-missed, per-salesperson breakdown table. The pipeline pie chart skips text labels for zero-value statuses (their zero-width slices would otherwise collapse to the same point and overlap illegibly) — the legend below still lists every status regardless. All native aggregate queries, org-scoped via the same Hibernate filter/RLS as everything else.

**Added 2026-08-03**, from admin/end-user demo feedback:
- **Visits by Type** (`GET /reports/visits-by-type`) — a chart of visit counts by Field vs. Telephonic, optionally date-ranged.
- **Interest Level × Status matrix** (`GET /reports/interest-level-status-matrix`) — one row per Interest Level (Hot/Warm/Cold/Not Set, fixed order) × every `LeadStatus` column, counting **Leads** (not Visits) currently at that interest level, each cell a lead count. `byStatus` is always fully zero-seeded across every `LeadStatus` value (same convention as `PipelineSummaryResponse#byStatus`).
- **Filterable All-Leads table** — the existing Leads list page's filters (State, City, Date range, Lead Status, Product) extended to serve as this report's "all lead calls matrix with filters," reusing `LeadFilter`/`LeadSpecifications` rather than a parallel reporting-only query.

The separate HR-focused dashboard for the Leave module is documented in Section 12.

## 10. Theme Settings

- Org-wide branding (primary color, light/dark mode, density) — Admin-configurable.
- Optional per-employee personal override, falls back to org default.
- `AppThemeProvider` merges `personal ?? org ?? hardcoded fallback`, caches resolved theme in `localStorage`.

## 11. Feature Entitlement / Licensing Infrastructure

A platform-wide, SaaS-style feature-gating system — deliberately separate from normal tenant data, since it describes what the *platform* offers and which *organizations* currently have access.

- **`FeatureEntitlement`** — a Java enum (not a DB table yet; two values today, `EMPLOYEE_LEAVE_MANAGEMENT` and `TEAM_VISIBILITY` — Section 20), kept simple until there are enough gated features to justify admin-editable catalog data.
- **`organization_entitlements`** table — the one deliberate exception to "every table has RLS": a tenant must never be able to grant itself a paid feature, so there's no Hibernate filter/RLS policy here at all; it's managed exclusively through a shared-secret-protected internal endpoint, never through the normal JWT/tenant flow.
- **Granting/revoking**: `PATCH /internal/organizations/{orgId}/entitlements/{code}` (`X-Platform-Key` header vs. the `PLATFORM_ADMIN_KEY` env var) — upsert-based, supports an optional expiry.
- **Enforcement**: `@RequireEntitlement(FeatureEntitlement.X)` method annotation + an AOP aspect, checked alongside `@PreAuthorize`. A non-entitled org gets 403 with `error: "FEATURE_NOT_ENTITLED"`.
- **Frontend**: `GET /organizations/me/entitlements` fetched once per session into an `EntitlementContext`; gated nav items/routes render conditionally (hidden entirely for a non-entitled org, not just disabled).
- **Platform Console** (Section 13) — a UI over this same infrastructure, replacing hand-crafted curl calls.

## 12. Employee Leave / Attendance / Approvals Module

The full module described in `EMPLOYEE_ENTITLEMENT_PLAN.md`, gated behind the `EMPLOYEE_LEAVE_MANAGEMENT` entitlement (Section 11) — every endpoint in this section requires it.

- **Leave Types** — fully admin-configurable per org (name, code, default allocation days), a dedicated table, not folded into generic master data (needs fields master data has no room for). Soft-delete only.
- **Leave Balances** — store ONLY the allocation (`allocatedDays`, `carriedForwardDays`); "used"/"remaining" are ALWAYS computed at read time as `SUM(total_days) WHERE status='APPROVED'`, never stored/denormalized, so they can never drift.
- **Leave Requests** — full lifecycle (submit → approve/reject → cancel):
  - Submission hard-blocks over-allocation by default (an Admin can still override at decision time), rejects overlapping date ranges for the same employee, and computes `totalDays` excluding weekends and org holidays.
  - **Approval routing**: the requester's direct manager (`Employee.managerId`), with any Admin as a fallback/override — not a separate "manager" role. Falls back to the Admin pool if the requester has no manager set, or if their resolved manager is inactive.
  - Cancellable while `PENDING`, or while `APPROVED` if the leave hasn't started yet.
  - Every transition writes to `employee_activity_log` (Section 8) and fires the relevant notification (Section 7).
- **Attendance** — self-service clock-in/clock-out. Day status (Present/Absent/On Leave/Holiday/Weekend) is DERIVED at read time, never stored: Present if clocked in; else On Leave if an approved Leave Request covers the date; else Holiday if an org Holiday matches; else Weekend; else Absent.
- **Holidays** — admin-configurable company holiday calendar, feeds both the leave day-count and the attendance-status derivation.
- **List/dashboard views**: "My Requests," "Pending My Approval" (the requester's manager plus any Admin fallback pool), Admin-only "All Requests," an Employee Leave & Attendance detail page (balances + recent requests + attendance summary for one employee — viewable by that employee, their direct manager, or any Admin), a Team Leave Calendar ("who's out when" — Admin sees org-wide, a manager sees their direct+indirect reports via a recursive-CTE hierarchy helper), and an HR overview dashboard (today's on-leave/not-clocked-in counts scoped the same way, plus a leave-utilization breakdown).
- **Recursive hierarchy**: `EmployeeHierarchyService.getAllSubordinateIds()` (a Postgres recursive CTE) resolves "all reports at any depth" for the Team Calendar and HR dashboard's team-scoping — approval routing itself always stays "direct manager only," regardless of hierarchy depth. The same helper also backs the `TEAM_VISIBILITY` entitlement (Section 20), which reuses it to expand a manager's visibility in the core Sales module (Leads/Visits/Reports/Activity), not just Leave/Attendance.

## 13. Platform Console

A standalone, non-tenant-scoped screen (`/platform-console`) for whoever operates the platform to manage entitlements across every organization, instead of hand-crafting curl calls against the internal endpoints (Section 11). Deliberately outside the normal JWT/`AuthContext` flow — authenticates with the same shared `X-Platform-Key` the backend already expects (entered once, kept in `sessionStorage` only, cleared when the tab closes). Lists every org with its currently-active entitlements as clickable chips (click to grant/revoke), plus the same search + CSV export convention as every other table in the app (Section 15). Backed by one new read-only endpoint, `GET /internal/organizations`, alongside the existing grant/revoke/list-history endpoints.

## 14. Lead Bulk Import (Excel/CSV)

`/app/leads/import` (Admin only) — a 3-step wizard for backfilling an org's existing/historical client base straight into the Lead table (reusing Leads' existing structure — Visits, Activity Log, Reporting all work on imported rows for free — rather than a separate Client/Account entity).

- **Upload**: `.xlsx` (Apache POI) or `.csv` (hand-rolled RFC 4180 parser).
- **Preview** (`POST /leads/import/preview`): parses headers + first 10 rows, auto-suggests a column-to-field mapping via header-name matching.
- **Commit** (`POST /leads/import/commit`): parses every row using the admin-confirmed mapping; only `companyName`/`contactPerson`/`contactNo` are hard-required (deliberately more lenient than interactive Lead creation, since historical bulk data may genuinely lack detail). Master-data fields (Industry, City, State, etc.) are resolved by case-insensitive label match against existing org master data, falling back to the free-text `*Other` column when no match is found — a typed value is never auto-promoted into master data, same rule as everywhere else (Section 2).
- **Duplicate/error handling**: a row matching an existing Lead (same phone/company) is skipped and reported, never aborting the rest of the batch; a row missing a required field is likewise skipped and reported.
- **No stub-visit spam**: imported leads do NOT publish `LeadCreatedEvent` — a bulk historical backfill must not auto-create hundreds of stub Visits. Each imported row still gets a normal `LEAD_CREATED` activity-log entry.
- Result summary shows imported/skipped-duplicate/error counts with row-level detail.

## 15. Filter + CSV Export (app-wide)

Every list table in the app has a quick-search box and a CSV export button, via two small shared pieces:

- `frontend/src/utils/exportToCsv.ts` — a hand-rolled, dependency-free CSV builder (RFC 4180 escaping) that triggers a client-side download.
- `frontend/src/components/TableToolbar.tsx` — search box + a page's own filter controls (as children) + the export button, one consistent row.

For server-paginated tables (Leads, Employees, Activity, Leave Requests, etc.), export fetches ALL rows matching the current filters via a one-off larger-page-size call (not just the visible page) before building the CSV — a real datatable export should never silently only cover what's on screen. The quick-search box itself filters only the currently-loaded page (a "narrow what's on screen" affordance, not a new server-side search capability). Small, fully-loaded aggregate tables (e.g. the HR dashboard's leave-utilization breakdown) get the export button without the search box, since a handful of rows doesn't need filtering.

## 16. UI/UX & Responsive Design

- **Responsive shell**: temporary overlay drawer below `md`, permanent above it. AppBar collapses below `sm`.
- **Sidebar grouped into labeled sections** (`NavSectionHeader`): a top-level Dashboard, then **Sales** (Leads, Activity), then **Leave & Attendance** (only for entitled orgs — My Leave, Leave Approvals, My Attendance, Team Calendar, HR Dashboard), then **Administration** (Admin-only — Masters, Employees, Reports, plus Leave Types/Holidays when entitled), then Settings below a divider — replacing a single flat list that had grown unwieldy as the app gained modules.
- **Mobile-friendly lists**: every list page in the app switches from a table to a card-based layout below `sm`.
- **Dialogs go fullscreen on mobile**.
- **Visual polish**: non-shouty buttons, softer card shadows/borders, tinted table headers, a pill-style active sidebar nav item, icon-badge stat cards, a time-of-day greeting header. Color-agnostic throughout.

## 17. Recent Validation / Data-Integrity Improvements

A focused batch of fixes and gaps found through direct code review and user-reported issues, not part of a single planned phase:

- Employee `phone` now validated as exactly 10 digits (previously unvalidated, inconsistent with Lead's `contactNo`).
- `Lead.nextFollowupDate`/`expectedCloseDate` and `Visit.nextVisitDate` reject past dates (today still allowed).
- Multipart upload size limit raised to 10MB (Spring's 1MB default was too small for a realistic historical-client spreadsheet — see Section 14).
- Activity feed pagination gained a secondary `id` sort key for deterministic ordering (Section 8).
- The auto-stub-Visit duplicate-prevention guard and the same-day-visit advisory endpoint (both Section 5).
- The Field/Telephonic choice on "log as today's visit" (Section 4/5).

## 18. AWS Deployment

Live on a single AWS EC2 instance (`t2.micro`, free-tier eligible, ap-south-1/Mumbai):

- **nginx** serves the built React SPA as static files and reverse-proxies `/api/` to the backend on `localhost:8080` — the frontend calls a relative `/api/v1` path (baked in via `.env.production`), so no CORS is needed between the SPA and its own backend, and the deployment isn't tied to a specific IP/domain in the built JS.
- **Spring Boot backend** runs as a systemd service (`salesmanager-backend`), pointed at the same real Aiven-hosted Postgres database used throughout development — no separate RDS instance, no data migration needed.
- **Secrets**: `JWT_SECRET` and `PLATFORM_ADMIN_KEY` are freshly-generated, strong, random production values (not the dev-only fallback strings), stored only in a root-only-readable `/etc/salesmanager/backend.env` referenced by the systemd unit's `EnvironmentFile=` — never committed to git.
- **TLS (added 2026-07-21)**: a free Let's Encrypt certificate is installed for `13-235-115-177.nip.io` (a wildcard DNS service that resolves `<ip-with-dashes>.nip.io` straight back to that IP, at zero cost and no signup — used here instead of a purchased domain). `nginx` gained a second `server` block on port 443 with the cert (`/etc/nginx/conf.d/salesmanager.conf`); the original port-80 bare-IP block (`server_name _`) is untouched and still works exactly as before. This was driven by a real mobile-access bug: several mobile browsers/networks were silently timing out (`ERR_CONNECTION_TIMED_OUT`) on plain-HTTP requests to a bare IP address specifically (a common heuristic against phishing/malware, which favors real domains + HTTPS) — confirmed by `neverssl.com` (a real domain, plain HTTP) loading fine on the same phone/network while the bare IP did not. **The app should be accessed at `https://13-235-115-177.nip.io/` on mobile** going forward; the bare-IP HTTP URL still works for existing links/bookmarks but shouldn't be relied on for new/mobile access. Certbot auto-renews the certificate (it expires 2026-10-19).
- **Not yet built**: Terraform/CDK IaC for this infrastructure (it was provisioned directly via AWS CLI for speed), auto-scaling, a second AZ, or a managed load balancer — all deliberately deferred until real usage justifies the added complexity. A real purchased/branded domain (instead of the free nip.io one) remains a possible later upgrade, not required for correctness.

## 19. Testing & Verification

- 212 backend integration tests (Testcontainers, real Postgres per run), covering every feature above including explicit tenant-isolation proofs for each table/feature as it was added.
- Every phase independently re-verified (`mvn clean verify`) and smoke-tested live against the real Aiven-hosted database (and, since Section 18, the live EC2 deployment) before being considered done.
- Frontend: `npm run build` + lint clean at every step.

## 20. Manager Team Visibility (`TEAM_VISIBILITY` entitlement)

Answers a real question that came up after Part B shipped: the `managerId` hierarchy (Section 12) already lets a manager's *reports* be resolved at any depth, but until this feature, that hierarchy had never been wired into the core Sales module — a manager with people reporting to them saw exactly the same Leads/Visits/Reports/Activity as an individual contributor with no reports at all. This closes that gap, gated behind a second entitlement code (alongside `EMPLOYEE_LEAVE_MANAGEMENT`, Section 11) so it's opt-in per organization like every other licensed feature.

- **`FeatureEntitlement.TEAM_VISIBILITY`** — new enum value; no migration needed (`organization_entitlements.entitlement_code` is a plain `varchar`, not DB-constrained to a fixed list — see Section 11).
- **Mechanism**: `EmployeeHierarchyService.getTeamVisibilityScope(organizationId, viewerId)` — returns the viewer's full subordinate chain (via the existing recursive CTE, `getAllSubordinateIds`) if the org has `TEAM_VISIBILITY` active, or an empty set otherwise (checked programmatically via `EntitlementService#isEntitled`, not the `@RequireEntitlement` AOP aspect — see below for why).
- **Different gating shape than Leave/Attendance**: `EMPLOYEE_LEAVE_MANAGEMENT` gates whole endpoints (not entitled → the endpoint doesn't exist for that org). `TEAM_VISIBILITY` instead expands the *result set* of endpoints that are always reachable (`GET /leads`, `GET /visits`, `GET /activity`, `GET /reports/*`) — a manager's own data is visible either way; the entitlement only decides whether their view also includes their team's.
- **Leads/Visits/Activity** (`LeadService#list`, `VisitService#list`, `ActivityLogService#list`): an `EMPLOYEE` who is a manager (non-empty scope) sees themself + every subordinate at any depth instead of just their own records; an explicitly-requested `ownerId` filter is honored only if it names someone inside that scope, otherwise it's ignored (never used to peek outside the manager's team). An `EMPLOYEE` with no reports, or in a non-entitled org, gets exactly the old self-only behavior — nothing changes for them.
- **Read-only, not an edit right**: `LeadService#getById`/`VisitService#getById` are similarly expanded (a manager can open a subordinate's Lead/Visit, not just see it in a list), but `update`/`updateStatus`/`reassign` deliberately are NOT — those keep the original owner-or-Admin-only check. A manager can see but not silently edit a subordinate's record through this feature alone.
- **Reports** (`ReportingController`/`ReportingService`): previously hard `@PreAuthorize("hasRole('ADMIN')")` at the class level. Now open to any authenticated user, with `ReportingService#resolveOwnerScope()` doing the real enforcement: `null` (unrestricted) for ADMIN, a manager's team scope for an entitled `EMPLOYEE` with reports, or an `AccessDeniedException` (403) for anyone else — a plain individual contributor still can't reach Reports at all, entitled org or not. New owner-scoped repository queries (`countGroupedByStatusForOwners`, `countGroupedByStatusForLeadIds`) back the scoped variants of pipeline-summary/conversion-rate/visits-completed-vs-missed.
- **Frontend**: `TeamVisibilityRoute` (new, mirrors `AdminRoute`/`RequireEntitlementRoute`) guards `/app/reports` — ADMIN always through, an `EMPLOYEE` needs the entitlement (the "do they actually have reports" check still happens server-side; a false-positive here just means an entitled non-manager sees per-section 403 error alerts, not a crash). The Reports nav item now also shows for a non-admin with the entitlement. `LeadListPage`/`ActivityPage`'s Owner filter/column (previously Admin-only, `isAdmin`) now also renders for an entitled manager (`canFilterByOwner = isAdmin || hasEntitlement("TEAM_VISIBILITY")`) — the backend still silently ignores an out-of-scope selection either way, so this is purely about surfacing a filter that's now actually useful to them.
- **Still deliberately not built**: Named Teams (Section 21 below) — this feature works identically with or without a labelled "team" concept, same as the Leave module's hierarchy always has.

## 21. Known Deferred Items (not built yet, deliberately)

- File attachments on Visits (needs real AWS S3 credentials) — Lead attachments, by contrast, shipped 2026-08-03 on local disk storage (Section 23).
- TLS/domain name for the EC2 deployment; Terraform IaC for the deployed infrastructure.
- Named Teams / formal org-structure UI (requested 2026-07-23) — a labelled "Team" concept (multiple teams, each with a name + designated Team Lead) and an admin UI to create teams and assign members, layered on top of the `managerId` chain that already gives Admin → Manager → Team-Lead → Employee at any depth. Recommended shape and open questions captured in the architecture plan (`shimmying-seeking-gray.md` section 10) — not started yet.
- MVP2 backlog: global search, bulk reassignment, multiple contacts per Lead, "Next Action" master wired into the Visit form, a "log a call" quick-action for Telephonic visits, richer post-sale Account management (if ever needed, a separate module from Lead-based bulk import).
- Visit-level reassignment on a same-day conflict (distinct from whole-Lead reassignment) + a `VISIT_REASSIGNMENT_REQUIRED` notification when an approved leave day collides with already-scheduled Visits.

## 22. Notification Center (bell dropdown + full history page)

The bell icon (Layout) previously fetched its own last-20 notifications and derived the unread badge count by counting unread items *within that fetched page* — correct only as long as there were never more than 20 unread at once. Reworked into a proper two-tier notification center:

- **`GET /notifications/unread-count`** (new) — a dedicated `COUNT` query (`NotificationRepository#countByRecipientIdAndRead`), the badge's actual source of truth now, independent of whatever page size the dropdown happens to fetch.
- **`PATCH /notifications/read-all`** (new) — bulk-marks every unread notification for the current user as read in one call (`NotificationRepository#markAllReadForRecipient`, a JPQL bulk `UPDATE`). Scoped by `recipientId` alone (no explicit `organizationId` condition) is safe here for the same reason `NotificationService#markRead`'s single-row lookup already was: `recipientId` is a real employee id, globally unique, so it can never resolve to another org's rows even though bulk JPQL `UPDATE`/`DELETE` bypasses the Hibernate `tenantFilter` (a known limitation — the filter only rewrites `SELECT`s).
- **Bell dropdown** (`Layout.tsx`): now shows the top 10 (was 20) with a "See all notifications" footer link to the new page below. Unread items get a clearer visual treatment than the previous faint background tint alone — bold text + a small red dot — driven by the same `isRead` flag.
- **`/app/notifications`** (new, `NotificationsPage`) — full paginated history for the current user (personal, no team/admin visibility change here — same recipient-only scoping as before), with the same search+CSV-export convention as every other list (Section 15), an "Unread only" checkbox (a real server-side filter, reusing the existing `unreadOnly` param), a client-side Type filter (the backend has no per-type filter yet, so this narrows the loaded page only, same convention as `ActivityPage`'s Type filter), a "Mark all read" button, and a type icon/color per row via new `NOTIFICATION_TYPE_ICONS`/`NOTIFICATION_TYPE_COLORS` maps.
- **Shared formatting**: `describeNotification`/`parseNotificationPayload`/`getNotificationTarget` (the click-to-navigate routing per notification type) moved out of `Layout.tsx` into `frontend/src/utils/notificationFormat.ts` so the bell dropdown and the full page render and navigate identically for the same notification, with no duplicated per-type switch logic.
- **Retention**: no time-based cutoff or archival — same as every other list in this app (Leads/Visits/Activity also never expire old rows), just ordinary pagination. Revisit only if it ever becomes a real problem at scale.

## 23. Demo Feedback Batch (2026-08-03): Lead Form Reallocation, INTERESTED Status, Lost Auto-Reassignment, Single-Admin Rule, Employee Dashboard Widgets, Lead Attachments, Import Template

A 9-point feedback batch from an end-user/admin demo. Two items from that list needed no new work: the Reports asks were already covered by Section 9's 2026-08-03 additions, and "Add Visit" was confirmed to have no actual gap. Everything below is the remaining scope.

- **Lead form field reallocation** (`LeadCreateDialog.tsx`/`LeadDetailPage.tsx`): the "Required Details" section is now, in order, Company Name, Business Type, Contact Person, Designation, Contact Number, State, City, Product, Interest Level, Decision Maker, Next Follow-up Date, Expected Close Date, Remarks; "Additional Details" (still collapsed by default) now holds Industry, Lead Source, Turnover, Email, Address, Current Product/Solution, Budget Range, and the new Attachments section. Requirements/Objections/Other-Products were removed from both forms — a UI-only removal (the underlying Lead columns/DTO fields are untouched, so no migration and no data loss). Because Lead Source moved into the collapsed accordion despite still being backend-required, the accordion now auto-expands if a submit attempt fails validation on `industryId`/`leadSourceId`.
- **New `LeadStatus.INTERESTED`** (`V17__lead_status_interested.sql`, extending the `leads_status_check` constraint the same way `V6` did for `master_data_type_check`): a lead whose Interest Level isn't Hot (checked via the master-data row's `code`, not its label — a free-text `interestLevelOther` never counts as Hot) is locked to `INTERESTED` — `LeadService#create` defaults new leads to `INTERESTED` unless created Hot (previously always `NEW`), and `LeadService#updateStatus` rejects (409 `InvalidLeadStatusException`) any other non-`LOST` status while not Hot. The frontend hides the status dropdown entirely for a non-Hot lead (showing a locked "Interested" chip + a standalone "Mark as Lost" button instead of the dropdown), and reveals the full pipeline the moment Interest Level is switched to Hot.
- **Lost auto-reassignment**: marking a lead Lost still auto-sets Interest Level to Cold as before, and now ALSO unassigns it from its current owner and hands it to the org's single Admin (mirroring the existing manual `reassign()`'s notification + activity-log shape) — so a lost lead re-enters the reassignable pool instead of sitting invisibly under whichever rep lost it. The Lead list/detail pages show a distinct "Unassigned – Lost" tag in the owner column/header for any Lost lead.
- **Single-Admin-per-org enforcement** — see Section 3.
- **Employee dashboard widgets** (`TodaysFollowUpsPage.tsx`, the plain-EMPLOYEE landing page at `/app`): two new sections, "Lapsed Calls" (`GET /leads?status=LAPSED`, already owner/team-scoped server-side) and "Upcoming Follow-ups" (`GET /visits?status=PLANNED&dateFrom=&dateTo=` for the next 7 days, the same query the Admin Dashboard's own upcoming-visits widget already uses) — both reused existing, already-scoped endpoints; no new backend query was needed for either.
- **Lead Attachments** (new `com.salesmanager.crm.leadattachment` package, `V18__lead_attachments.sql`): file upload/list/download/delete on the Lead detail page, stored on local disk via an `AttachmentStorageService` interface (`LocalFilesystemAttachmentStorageService` the only implementation today, base directory configurable via `LEAD_ATTACHMENT_STORAGE_DIR`) — deliberately built so a future S3-backed implementation is a drop-in second bean, no entity/controller/migration changes needed. 10MB size cap, allowed types images/PDF/Word/Excel/plain-text. Only reachable from the Lead detail page (a brand-new Lead in the create dialog has no id yet to attach a file to).
- **Lead Import template**: a static bundled `.xlsx` (`frontend/public/lead-import-template.xlsx`, regenerable via `scripts/generate_lead_import_template.py`) with one header row matching `LeadImportField`'s importable-field vocabulary plus two example rows, linked as a "Download Excel template" button on the import wizard's upload step. No backend endpoint — the importer has no fixed required header set to generate against.
- **Verification**: extended/fixed pre-existing tests that assumed the old multi-admin and default-`NEW`-status behavior (both intentionally changed), plus new coverage — `EmployeeCrudIT`'s single-admin-rejection test, `LeadCrudIT`'s Lost-reassignment-to-Admin and Interest-Level-gating tests. Full suite green (`mvn verify`, 208/208), frontend `npm run build`/lint clean.

## 24. Post-Demo Follow-Up Fixes (2026-08-03): Attachment Storage Path, Visit Form Reallocation, Invoice → Quotation

A second round of feedback after Section 23 went live, from the user actually clicking through the new features on the deployed site.

- **Lead attachment upload 500 bug, fixed**: the very first live upload attempt failed with `UnexpectedRollbackException`/an incomplete chunked response. Root cause: `LEAD_ATTACHMENT_STORAGE_DIR` was never set on the EC2 box, so `LocalFilesystemAttachmentStorageService` fell back to its relative dev default (`./data/lead-attachments`) — but the systemd unit has no `WorkingDirectory=`, so that resolved to `/data/lead-attachments`, which `ec2-user` cannot create. Fixed by creating `/opt/salesmanager/data/lead-attachments` (owned by `ec2-user`) and setting `LEAD_ATTACHMENT_STORAGE_DIR` in `/etc/salesmanager/backend.env`. No code change — a deployment-config gap, not a logic bug.
- **Visit form restructuring** (`VisitFormDialog.tsx`): mirrors the Lead form's Section 23 reallocation exactly. The old "Contact details"/"Visit details" always-visible sections merged into one "Required details" section (Contact Person, Designation, Contact Number, State, City, Product, Interest Level, Decision Maker, Next Visit Date, Remarks); Email/Budget Range/Address moved into a collapsed "Additional details" accordion; Requirements, Objections, and Other Products removed from the form entirely (same UI-only removal as Lead's).
- **"Invoice" renamed to "Quotation"** everywhere user-facing — nav label, page titles/buttons, empty states, table headers, the generated PDF's own title (`INVOICE` → `QUOTATION`) and document-number prefix (`INV-2026-0001` → `QUO-2026-0001`). Deliberately a pure label rename: routes (`/app/invoices`), component/file names, the `Invoice`/`InvoiceLineItem` entities/tables, and all behavior (stock deduction, per-year numbering, PDF generation) are untouched.

## 25. Minimalist UI Style (theme setting)

A fourth theme dimension alongside primaryColor/mode/density (same `Organization#themeSettings` + `Employee#themePreference` JSONB storage, no migration needed — `ThemeSettings` is just a 4-field record now).

- **Settings**: a new "UI style" toggle (Standard / Minimalist) in both "Organization branding" (Admin-set default) and "My preference" (personal override), identical org-default-then-personal-override pattern as Appearance/Density.
- **`createAppTheme.ts`**: when Minimalist, a component-overrides layer applied on top of density's (Minimalist wins on any shared key) removes Paper/Card shadows, sets `MuiButton` `disableElevation`, replaces the sidebar's rounded "pill" active-nav highlight with a plain left-border indicator, lightens Chip/heading font weights, and tightens `shape.borderRadius` (12 → 4). Deliberately never touches palette colors (primary/secondary/mode) — purely less decoration, not a re-theme.
- Verified live in a real browser session: toggling Minimalist instantly re-themed the app (screenshot-confirmed nav-highlight change), zero console errors.

## 26. Team Progress + Read-Only Team Member Drill-Down

Answers "as an admin/manager, let me check what my team members are working on" — read-only for now (no edit/reassign actions from this view).

- **`GET /reports/team-progress`** (`ReportingService#teamProgress`, new): one row per team member — lead counts by every status, visits due today, visits due in the next 7 days, and a last-activity timestamp. Reuses the same TEAM_VISIBILITY-gated scoping every other Reports endpoint already has, but lists individual members rather than an aggregate: an ADMIN sees every other active employee in the org; an entitled manager sees their subordinate chain (never themself); anyone else still gets 403. New repository projections/queries back the per-member breakdown (`LeadRepository#countGroupedByOwnerAndStatusForOwners`, two new `VisitRepository` leadId-scoped finders, `ActivityLogRepository#findLastActivityForOwners`) — Visit has no `ownerId` of its own, so visits are attributed back to a member via their leads' ownership, same pattern `ReportingService` already used for visits-by-type/completed-vs-missed.
- **Reports page**: a new "Team Progress" table section (same page, no new nav item — reuses the existing `TeamVisibilityRoute` gate).
- **`/app/team/:employeeId`** (new, `TeamMemberDetailPage`): clicking a team member's row opens a read-only page showing their assigned leads (company, contact, status, interest level, next follow-up) and recent activity feed. No edit controls, and leads aren't individually clickable (a deliberate "list only" scope decision — a future iteration could add full read-only per-lead drill-down). No new backend endpoint needed here at all: `GET /leads?ownerId=` and `GET /activity?ownerId=` already scope correctly for the caller.
- Both verified live in a real browser session (click-through from Team Progress into the detail page), correct data, zero console errors.
