# ISKCON Kitchen Management System

A multi-tenant web application for managing ISKCON temple food service operations — recipes, inventory, meal planning, procurement, volunteer coordination, and donations.

Each temple is a fully isolated tenant. India-first (INR, UPI, WhatsApp); designed for users who are not necessarily computer-literate.

## Project documentation

| Document | Purpose |
|---|---|
| [`docs/PROJECT_COMMANDMENTS.md`](docs/PROJECT_COMMANDMENTS.md) | Governing SDLC rules for this project |
| [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md) | Product requirements (v1.0, locked) |
| [`docs/SYSTEM_DESIGN.md`](docs/SYSTEM_DESIGN.md) | Architecture and system design (v1.0, locked) |
| [`docs/TECH_STACK.md`](docs/TECH_STACK.md) | Technology selections with rationale (v1.0, locked) |
| [`docs/stories/`](docs/stories/) | 7 epics, 55 user stories (mirrored to GitHub Issues) |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | Version history of the locked documents |

## Stack

**Backend:** Java 21, Spring Boot 3.3, Spring Data JPA, PostgreSQL (Row-Level Security for tenant isolation), Quartz (background jobs), Playwright/Chromium (PDF generation)
**Frontend:** Next.js 14 (App Router), TypeScript, Tailwind CSS, Radix UI
**Infrastructure:** Google Cloud (Cloud Run, Cloud SQL, Cloud Storage, Secret Manager) in `asia-south1`, Cloudflare (CDN/WAF)
**Integrations:** Firebase Auth, Razorpay (UPI-first), Meta WhatsApp Cloud API, Bhashini (Indian-language translation)

## Repository layout

```
backend/     Spring Boot API and background workers
frontend/    Next.js web application
docs/        Requirements, design, stories
.github/     CI workflows
```

## Local development

**Prerequisites:** JDK 21, Node 22, Docker (for the local database and Testcontainers).

```bash
# 1. Start the local Postgres
docker compose up -d

# 2. Backend — first time only, generate the Gradle wrapper
cd backend
gradle wrapper --gradle-version 8.10   # requires a local Gradle install; afterwards use ./gradlew
./gradlew bootRun                       # http://localhost:8080/health

# 3. Backend tests (Testcontainers spins up its own Postgres; Docker must be running)
./gradlew test

# 4. Frontend
cd ../frontend
npm install
npm run dev                             # http://localhost:3000
npm test                                # Vitest
```

## CI

GitHub Actions runs on every pull request and push to `main`: backend build + tests (with Testcontainers), frontend type check + tests + production build. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).
