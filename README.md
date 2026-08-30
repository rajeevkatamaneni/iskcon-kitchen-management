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
# 1. Start the local Postgres. Database, user and password are all "kms", which is
#    what the backend defaults to, so no configuration is needed for it.
docker compose up -d

# 2. Backend. KMS_FIREBASE_ENABLED is not optional if you intend to sign in — see below.
cd backend
KMS_FIREBASE_ENABLED=true ./gradlew bootRun    # http://localhost:8080/health

# 3. Backend tests (Testcontainers spins up its own Postgres; Docker must be running)
./gradlew test

# 4. Frontend. NEXT_PUBLIC_API_URL is inlined at build time, so it has to be on the
#    command that starts the server — .env.local carries the Firebase keys but leaves
#    this one empty, and without it every request goes to the Next server and 404s.
cd ../frontend
npm install
NEXT_PUBLIC_API_URL=http://localhost:8080 npm run dev   # http://localhost:3000
npm test                                                 # Vitest
```

**The two variables above are the whole difference between a working local setup and a
puzzling one.** Without `KMS_FIREBASE_ENABLED=true` the application boots perfectly and
nobody can ever sign in: `FirebaseConfiguration` is conditional on it, so `RejectingTokenVerifier`
takes over and refuses every token. Without `NEXT_PUBLIC_API_URL` the frontend renders and every
API call fails. Neither announces itself.

Firebase verification also needs application-default credentials — `gcloud auth
application-default login` once. Sign-in runs against the real hosted Firebase project, which
already authorises `localhost`.

**Giving yourself an account.** A fresh database has no users and no temples, so a valid Google
sign-in lands you as somebody with no membership and nothing to join. Seed a pending row keyed to
an address you can actually verify, then sign in with Google to claim it:

```sql
INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
VALUES (NULL, 'pending:' || gen_random_uuid(), 'Platform Operator',
        'you@example.com', '+91XXXXXXXXXX', 'SUPER_ADMIN');
```

The `pending:` prefix is load-bearing — the claim looks for it — and the email is matched only when
Firebase reports it verified, so use Google rather than a password.

**One thing not to do:** `npm run build` while `npm run dev` is running. They share `.next`, and
the build overwrites the running server's files, after which every request 500s with
`MODULE_NOT_FOUND`. Stop the dev server first.

## CI

GitHub Actions runs on every pull request and push to `main`: backend build + tests (with Testcontainers), frontend type check + tests + production build. See [`.github/workflows/ci.yml`](.github/workflows/ci.yml).
