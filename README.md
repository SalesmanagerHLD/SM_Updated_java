# SalesManager CRM — Backend API

Spring Boot REST API for SalesManager CRM. Repository: [SalesmanagerHLD/SM_Updated_java](https://github.com/SalesmanagerHLD/SM_Updated_java). Consumed by the web app ([SM_Updated_React](https://github.com/SalesmanagerHLD/SM_Updated_React)) and the Flutter app ([SM_Updated_Mobile](https://github.com/SalesmanagerHLD/SM_Updated_Mobile)).

## Stack

- Java 17, Spring Boot 3.2.5, Maven — package root `com.salesmanager.crm`
- PostgreSQL with Flyway migrations
- JWT auth (short-lived access token + rotating refresh token), BCrypt, roles `ADMIN` / `EMPLOYEE`
- Multi-tenant shared schema: every tenant table carries `organization_id`, enforced by a Hibernate filter plus Postgres Row-Level Security

## Running locally

The `local` Spring profile is required. Without it the app tries to connect to a local Postgres that doesn't exist and fails at startup.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`src/main/resources/application-local.yml` holds real database credentials and is git-ignored. Never commit it. A long-running instance also keeps serving old code after edits, so restart it after changes.

## Tests

```bash
mvn clean verify
```

The integration tests use Testcontainers (a real Postgres per run), so Docker must be running.

## Key environment variables

| Variable | Purpose |
|---|---|
| `JWT_SECRET` | JWT signing secret |
| `PLATFORM_ADMIN_KEY` | Shared secret for `/internal/**` platform endpoints (entitlements, org seeding, Platform Console) |
| `LEAD_ATTACHMENT_STORAGE_DIR` | Absolute directory for Lead attachments (the relative default breaks under systemd) |

On EC2 these live in `/etc/salesmanager/backend.env`, loaded through the systemd unit's `EnvironmentFile=`. Base64-encode any secret containing escape sequences (PEM keys, JSON blobs), because systemd mangles `\n`.

## Deployment

Runs as the `salesmanager-backend` systemd service on AWS EC2 behind nginx. Deployments are done manually, so confirm before redeploying. Note that the EC2 instance shares the dev database, so its scheduled jobs can race local ones.

## Branching

`main` reflects what is deployed. Ongoing work goes on `develop` or a feature branch, then merges to `main`.

## Documentation

Project-wide documentation lives in this repo's [docs/](docs/) folder:

- [CRM_IMPLEMENTATION.md](docs/CRM_IMPLEMENTATION.md) — what is built, architecture, deployment
- [EMPLOYEE_ENTITLEMENT_PLAN.md](docs/EMPLOYEE_ENTITLEMENT_PLAN.md) — Leave and entitlement design
- [SalesManager_CRM_Modules_and_Workflows.md](docs/SalesManager_CRM_Modules_and_Workflows.md) — modules and workflows
- [Mobile app mockup review.pdf](docs/Mobile%20app%20mockup%20review.pdf) — mobile mockup review

## Related repositories

- [SM_Updated_React](https://github.com/SalesmanagerHLD/SM_Updated_React) — web frontend
- [SM_Updated_Mobile](https://github.com/SalesmanagerHLD/SM_Updated_Mobile) — Flutter mobile app
