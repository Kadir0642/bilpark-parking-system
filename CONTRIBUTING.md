# Contributing to BilPark

Thanks for your interest. BilPark is a production system maintained by one developer, and the code is not open source: all rights are reserved (see [LICENSE](LICENSE)). Bug reports and suggestions through issues are welcome. Please ask in an issue before opening a pull request; contributions are accepted only with the copyright holder's written agreement.

## Workflow

1. Fork the repository and create a branch from `main` (`git checkout -b feature/short-name`).
2. Keep each pull request to one topic and describe what changed and how you tested it.
3. CI must pass: the Maven build with all backend tests and `flutter analyze`.

## Local setup

- Full stack: follow [docs/yerel-demo.md](docs/yerel-demo.md) (`demo/yeni-env.ps1`, then `docker compose up --build`).
- Backend: Java 21. Run `./mvnw test` in `backend/`. Testcontainers tests run when Docker is available.
- Mobile: Flutter 3.38.9 (the version pinned in CI). Run `flutter analyze` and `flutter test` in `mobile/`.

## Guidelines

- **Backend:** add a test for every behaviour change (JUnit 5, Mockito, Spring Boot Test). Schema changes go into a new Flyway migration; never edit an applied one.
- **Mobile:** follow the Flutter style guide, keep shared values in `lib/core/`, and add a widget test for UI logic.
- **Web:** the panel is plain HTML/CSS/JS. Use the tokens in `web/theme.css`, and mark buttons that write data with `data-write` so read-only mode can disable them.
- **Secrets:** never commit real passwords, keys or personal data. Use `.env` files (see `backend/.env.example`) and `test-secret-…` values in tests.
