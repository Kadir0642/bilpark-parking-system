# BilPark documentation

Most documents are in Turkish. The project overview is in the [main README](../README.md) ([Türkçe](../README.tr.md)).

## User guides

| Document | What it covers |
|---|---|
| [yerel-demo.md](yerel-demo.md) | Run the whole stack locally with Docker, load sample data, connect an Android phone over Wi-Fi or USB, demo users, common errors |

## API contracts

| Document | What it covers |
|---|---|
| [api-asama3.md](api-asama3.md) | Health, streets and zones, tariff engine and day-by-day breakdown, fee and debt endpoints, live sync (SSE and `/api/sync`), user deactivation |
| [api-asama5.md](api-asama5.md) | Role table, license and read-only mode, own account, user admin, shifts, record cancellation, audit log, settings, Excel/CSV reports, developer endpoints, retention job |
| [api-arac-tipleri.md](api-arac-tipleri.md) | Vehicle types as data (Flyway V5), tariff map per type, idempotent check-in (`checkInRequestId`), offline entry time (`clientEntryTime`) |

Interactive API docs are served by the backend at `/swagger-ui.html`.

## Operations and deploy

| Document | What it covers |
|---|---|
| [1-kasim-canliya-gecis.md](1-kasim-canliya-gecis.md) | Go-live checklist: backup branch, password reset, environment variables, legacy schema upgrade, first login, uptime monitor, QR test |
| [e2e-testler.md](e2e-testler.md) | Real-phone end-to-end suites, the one-command runner and the latest results |

## Internal process

| Document | What it covers |
|---|---|
| [pr-main-tek-pr-taslagi.md](pr-main-tek-pr-taslagi.md) | Draft text of the single pull request used for go-live |
| [sozlesme-eki-gelistirici-ve-lisans.md](sozlesme-eki-gelistirici-ve-lisans.md) | Contract annex template for the developer role, license and audit (placeholders only) |

## Images

[`images/`](images/) holds the screenshots and GIFs used in the README: `panel/`, `mobile/`, `citizen/` and `evolution/` (older visuals recovered from git history).
