# Contributing to BilPark Pro

First off, thank you for considering contributing to BilPark Pro!

## Getting Started
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## Coding Guidelines
- **Backend:** Follow Spring Boot best practices. Ensure that you have written unit tests for any new logic (JUnit 5 + Mockito).
- **Mobile:** We use Flutter. Please follow the standard Flutter style guide and structure. Use `AppConstants` for global variables. Ensure you replace `withOpacity` with `withValues` for Flutter 3.10+.
- **Frontend:** Ensure clean HTML/JS structure.

## Setting Up Locally
- Make sure you have **Java 21** installed.
- Configure your `.env` file with Neon PostgreSQL connection details.
- Use Maven to run tests before submitting a PR: `./mvnw clean test`
