# BilPark: street-parking management system

**A field app, an admin panel and a citizen page that replace paper parking tickets for a municipal street-parking operator. Running in production.**

[![Java CI with Maven](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/maven.yml/badge.svg)](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/maven.yml)
[![Flutter CI](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/flutter.yml/badge.svg)](https://github.com/Kadir0642/bilpark-parking-system/actions/workflows/flutter.yml)
[![License: All rights reserved](https://img.shields.io/badge/license-All%20rights%20reserved-red)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-brightgreen)
![Flutter](https://img.shields.io/badge/Flutter-3.38-02569B)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)

[Türkçe README](README.tr.md)

![Admin panel tour: dashboard, records, tariff preview, notifications, developer page](docs/images/panel-tour.gif)

---

## The problem

Parking officers on three streets used paper tickets. Fees were worked out by hand, drivers who left without paying were hard to track, and the owner had no live view of revenue. BilPark puts every vehicle, payment and shift into one system that runs on free cloud tiers.

## What it does

**Field app (Flutter, Android)** for parking officers
- Reads plates with the camera using on-device OCR (Google ML Kit), including two-line motorcycle plates. Plates can also be typed.
- Check-in to the left or right curb, check-out with cash or POS, and an undo window after payment.
- Live street view with timers and the running fee. Subscribers are shown in a different color and can be hidden.
- Marks runaway vehicles and warns the officer when an indebted plate comes back.
- An offline entry queue: entries made without signal are sent later with the time the officer actually tapped.
- Live sync over Server-Sent Events. Tariff, vehicle type and account changes reach every phone within seconds.
- Shows a QR code that opens the citizen page for that vehicle.

**Admin panel (static web, vanilla JS)** for the operator
- Live dashboard: vehicles on site, revenue, open runaway debt, zone occupancy and active staff.
- Records with filters, detail rows, cancellation with a reason (nothing is deleted), and an Excel export.
- Versioned tariffs: draft → preview with sample fees and a day-by-day breakdown → scheduled activation, confirmed by re-entering the password.
- Staff accounts and zone assignment, shift history, monthly performance report.
- Excel/CSV reports: staff, runaways for the municipality, period revenue minus rent, full record dump.
- Audit log of who did what and when, with export.
- License status with a visible read-only mode, a notification bell, and dark and light themes.
- A separate developer (maintenance) page for license, system health, recent errors and alerts. It sees plates only in masked form.

**Citizen page (mobile web)**
- A driver scans the QR on the street sign or the officer's phone, enters the plate and sees the current fee.
- A day-by-day breakdown shows which hours were paid and which were free (the night window is free).

**Backend (Spring Boot)**
- REST API with JWT auth and three roles (officer, admin, developer), plus a small set of read-only public endpoints for the citizen page.
- Flyway migrations, idempotent check-in, optimistic locking, SSE with catch-up, nightly retention jobs.

## Screenshots

| | | |
|---|---|---|
| ![Dashboard](docs/images/panel/dashboard.png) **Dashboard** | ![Records with an expanded detail row](docs/images/panel/records.png) **Records** | ![Tariff preview with day-by-day breakdown](docs/images/panel/tariff-preview.png) **Tariff preview** |
| ![Runaway vehicles](docs/images/panel/runaways.png) **Runaways** | ![Staff](docs/images/panel/staff.png) **Staff** | ![Shifts](docs/images/panel/shifts.png) **Shifts** |
| ![Reports](docs/images/panel/reports.png) **Reports** | ![Audit log](docs/images/panel/audit.png) **Audit log** | ![Developer page](docs/images/panel/developer.png) **Developer page** |
| ![Notification bell](docs/images/panel/notifications.png) **Notifications** | ![Dark theme dashboard](docs/images/panel/dashboard-dark.png) **Dark theme** | ![Tariff page](docs/images/panel/tariff.png) **Tariff draft** |

**Field app** (Android, October 2026 UI)

| | | | |
|---|---|---|---|
| ![Login](docs/images/mobile/login.png) | ![Home](docs/images/mobile/home.png) | ![Street view](docs/images/mobile/street.png) | ![Street view, dark](docs/images/mobile/street-dark.png) |
| Login | Home and shift summary | Street view | Street view, dark |
| ![Vehicle detail and payment](docs/images/mobile/vehicle-detail.png) | ![Undo after payment](docs/images/mobile/undo-payment.png) | ![History](docs/images/mobile/history.png) | |
| Vehicle detail and payment | Undo after payment | History | |

**Citizen page**

| | | |
|---|---|---|
| ![Citizen QR flow](docs/images/citizen-qr.gif) | ![Plate entry](docs/images/citizen/plate-entry.png) | ![Fee with day-by-day breakdown](docs/images/citizen/fee-breakdown.png) |
| QR flow | Plate entry | Fee and day-by-day breakdown |

All panel and citizen screenshots come from the local Docker demo with sample data.

## Architecture

```mermaid
flowchart LR
    subgraph Clients
        APP["Flutter field app<br/>(Android, on-device OCR)"]
        PANEL["Admin panel<br/>(static HTML/JS)"]
        CIT["Citizen page<br/>(mobile web, QR)"]
    end
    PAGES["GitHub Pages<br/>(hosts panel and citizen page)"]
    subgraph Render["Render (free web service, Docker)"]
        API["Spring Boot API<br/>JWT · REST · SSE"]
        JOBS["Scheduled jobs<br/>nightly retention,<br/>tariff activation"]
    end
    DB[("PostgreSQL on Neon<br/>(scale to zero)")]

    PAGES -. serves .-> PANEL
    PAGES -. serves .-> CIT
    APP -- "REST /api" --> API
    PANEL -- "REST /api" --> API
    CIT -- "GET /api/parking/debt" --> API
    API -- "SSE /api/events" --> APP
    API -- "SSE /api/events" --> PANEL
    API -- "JDBC, Flyway" --> DB
    JOBS --> DB
    MON["Uptime monitor"] -- "GET /api/health<br/>(no DB access)" --> API
```

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4 (Web MVC, Security, Data JPA, Validation), Flyway, JJWT, Apache POI (Excel), springdoc OpenAPI |
| Database | PostgreSQL 16 (Neon in production, Docker locally), H2 for fast tests |
| Mobile | Flutter 3.38 / Dart, Google ML Kit text recognition, camera, `qr_flutter`, `shared_preferences` |
| Web | Static HTML/CSS/vanilla JS with shared design tokens, Chart.js, EventSource (SSE) |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers (PostgreSQL), Flutter widget tests, `integration_test` on a real phone |
| Ops | Docker, Docker Compose, Render, Neon, GitHub Pages, GitHub Actions |

## Engineering highlights

- **Idempotent check-in.** The phone sends a `checkInRequestId` with every entry. A retried request after a timeout returns the original result instead of creating a second vehicle. See [`CheckInRequest`](backend/src/main/java/com/bilpark/backend/model/CheckInRequest.java), [`V6__check_in_request_id.sql`](backend/src/main/resources/db/migration/V6__check_in_request_id.sql) and [`CheckInIdempotencyTest`](backend/src/test/java/com/bilpark/backend/CheckInIdempotencyTest.java).
- **Optimistic locking.** Active spots carry a JPA `@Version`, so two officers (or an officer and the citizen page) cannot both close the same vehicle. [`ParkSpot`](backend/src/main/java/com/bilpark/backend/model/ParkSpot.java), tested against real Postgres in [`ConcurrentCheckoutPostgresTest`](backend/src/test/java/com/bilpark/backend/postgres/ConcurrentCheckoutPostgresTest.java).
- **Versioned tariff engine.** Tariffs move through draft → scheduled → active → archived. [`FeeEngine`](backend/src/main/java/com/bilpark/backend/fee/FeeEngine.java) is a pure function: it sums only the minutes inside the daily paid window (for example 08:00–22:00), applies the tariff once and returns a day-by-day breakdown that the app, the panel and the citizen page all show. Contract: [`docs/api-asama3.md`](docs/api-asama3.md).
- **Offline queue that keeps the tap time.** Entries made without signal wait in [`entry_queue.dart`](mobile/lib/core/entry_queue.dart) and are sent with `clientEntryTime`. The server accepts that time only inside a sane window (up to 24 hours back, 5 minutes ahead), so a dead zone does not lower the fee. See `resolveEntryTime` in [`ParkingService`](backend/src/main/java/com/bilpark/backend/service/ParkingService.java).
- **Live sync without polling.** [`SyncService`](backend/src/main/java/com/bilpark/backend/sync/SyncService.java) streams versioned events over SSE with heartbeats and a `RESET` signal. After a disconnect, clients call `GET /api/sync?since=<version>` to catch up. Browsers open the stream with a short-lived one-time ticket, because `EventSource` cannot send headers. Client side: [`live_sync.dart`](mobile/lib/core/live_sync.dart).
- **Upgrading a legacy production schema.** Production still had the March 2026 schema that Hibernate had created, without Flyway history. [`V7__legacy_march_schema.sql`](backend/src/main/resources/db/migration/V7__legacy_march_schema.sql) and `baseline-version=0` bring it up to date. The upgrade was rehearsed on an exact copy of the live schema and is covered by [`MarchLiveSchemaUpgradePostgresTest`](backend/src/test/java/com/bilpark/backend/postgres/MarchLiveSchemaUpgradePostgresTest.java).
- **A developer role with limited access.** The maintenance account can renew the license, check health and read errors. Every developer action is written to the audit log that the admin sees. Plates are masked for this role (`34 *** *23`).
- **License and read-only mode instead of a kill switch.** When the license expires, every write under `/api` answers `423 Locked` and the panel shows why. Reads and Excel exports keep working, and no data is ever deleted. See [`ReadOnlyModeInterceptor`](backend/src/main/java/com/bilpark/backend/license/ReadOnlyModeInterceptor.java) and [`LicenseService`](backend/src/main/java/com/bilpark/backend/license/LicenseService.java).
- **Designed for free tiers.** [`/api/health`](backend/src/main/java/com/bilpark/backend/controller/HealthController.java) never touches the database, so the uptime monitor keeps Render awake while Neon can scale to zero. The Hikari pool keeps no idle connections (`minimum-idle=0`), maintenance jobs run only at night, and SSE replaced six-second polling.
- **Tests.** 250+ backend tests (JUnit 5, Spring Boot Test, Testcontainers against PostgreSQL 16 for migrations, concurrency and retention), 130+ Flutter unit and widget tests, and five end-to-end suites that run on a real Android phone against a separate Docker stack ([`docs/e2e-testler.md`](docs/e2e-testler.md)).

## Quick start (local demo)

You need Docker Desktop. The demo is fully separate from production.

```bash
powershell -ExecutionPolicy Bypass -File demo/yeni-env.ps1   # creates .env.demo with random secrets
docker compose up --build                                     # Postgres :5433, API :8081, web :5500
powershell -ExecutionPolicy Bypass -File demo/ornek-veri.ps1  # optional sample data
```

- Admin panel: http://localhost:5500/admin-panel.html
- Citizen page: http://localhost:5500/customer-payment.html
- API docs (Swagger UI): http://localhost:8081/swagger-ui.html

Demo users, phone setup (Wi-Fi or USB with `adb reverse`) and common errors are in [`docs/yerel-demo.md`](docs/yerel-demo.md) (Turkish).

**Mobile app.** The API address is set at build time:

```bash
cd mobile
flutter pub get
flutter run --dart-define=API_BASE=http://<your-pc-ip>:8081/api
# release APK
flutter build apk --release --dart-define=API_BASE=https://<your-api-host>/api
```

**Backend only.** Set the variables in [`backend/.env.example`](backend/.env.example) (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD`, `OFFICER_PASSWORD`) and run `./mvnw spring-boot:run`. The app refuses to start without `JWT_SECRET`.

## Testing

```bash
cd backend && ./mvnw test          # H2 tests; Testcontainers tests also run when Docker is available
cd mobile && flutter test          # unit and widget tests
cd mobile && flutter analyze       # same check as CI
bash mobile/integration_test/tools/telefon_dogrula.sh   # real-phone e2e, about 30 minutes
```

GitHub Actions runs the Maven build with all tests and `flutter analyze` on every pull request.

## Project structure

```
backend/        Spring Boot API: controllers, services, fee engine, sync (SSE), audit, license, reports, retention
  src/main/resources/db/migration/   Flyway migrations V1–V9
mobile/         Flutter field app
  lib/core/     API client, offline queue, live sync, plate parsing, tariff and vehicle type stores
  lib/screens/  login, home, street view, plate scanner, vehicle detail, history, settings
  integration_test/  real-phone end-to-end suites
web/            Admin panel, citizen page, shared theme; page modules in web/js/
demo/           Local demo helpers: .env generator and sample data
docs/           API contracts, demo and deploy guides, images (index: docs/README.md)
web-admin/      Early React prototype, not used
```

## How it evolved

| When | Stage | What changed |
|---|---|---|
| Feb 2026 | Prototype | Spring Boot and Neon, the first fee algorithm, a Flutter app with a fixed grid of numbered spots, a hand-drawn layered architecture diagram and a satellite sketch of the streets. |
| Late Feb – Mar 2026 | First deploy | Moved from ngrok to Render. Dynamic street model without fixed capacity, the citizen QR payment page, an admin panel with charts and Excel export, runaway tracking. |
| Oct 2026 | Rebuilt for real use | Secrets moved to environment variables, JWT roles and officer zones, Flyway, locked and idempotent check-in, multi-device live sync over SSE, versioned tariffs with scheduling, redesigned UI, staff, shifts, reports, audit log, license and read-only mode, vehicle types as data, real-phone e2e tests. |
| Nov 2026 | Production | The legacy production schema was upgraded after a rehearsal on a copy, test data was cleared, a release build was verified on a phone, and the system went live. Developer monitoring and admin notifications followed. |

| Feb 2026 architecture | Feb 2026 street sketch | Feb 2026 field app | Oct 2026 panel |
|---|---|---|---|
| ![Feb 2026 architecture diagram](docs/images/evolution/2026-02-architecture.png) | ![Feb 2026 street sketch](docs/images/evolution/2026-02-street-sketch.jpg) | ![Feb 2026 street grid screen, plates blurred](docs/images/evolution/2026-02-mobile-street.png) | ![October 2026 panel overview](docs/images/evolution/2026-10-panel-overview.png) |

## Roadmap

- [ ] Online payment on the citizen page, built first against a payment simulator
- [ ] iOS build (some officers use iPhones) through a cloud build service
- [ ] Printable per-zone QR signs generated from the panel
- [ ] Subscriptions page in the panel

## Author

**Kadir Kacır**, student developer. I designed and built the backend, the mobile app and the web clients, and I run the production deployment.

<!-- LinkedIn / e-posta -->

All rights reserved; see [LICENSE](LICENSE).
