# User Acceptance Testing

How we run UAT, per Commandment 6 (as amended 2026-08-09). Read that commandment first; this file is the working catalogue.

## The model

- **Coding stories** close on automated tests + review + design-doc conformance (see Commandment 5). They are not held open waiting on UAT.
- **UAT stories** are separate, and scoped to a **demonstrable capability** — the smallest slice a person can actually drive end to end — not one-per-coding-story. A capability usually spans several coding stories; pure-infrastructure stories (RLS, the audit kernel, jobs, observability) have no manual surface and carry no UAT story, only a one-line verification note on the coding story itself.
- **Traceability runs both ways.** Each UAT story lists the coding stories it exercises; each of those coding stories links back to its UAT story. Nothing is silently untested.
- **GitHub:** UAT stories are issues labelled `uat`. A coding story still awaiting its UAT pass carries `needs-uat`.

## UAT story template

```
## UAT-<n> — <capability name>

Exercises: <coding stories, e.g. E1-S4, E1-S5, E1-S6>
Status: DRAFT | READY | IN PROGRESS | PASSED | BLOCKED

### Preconditions / setup
- <environment, accounts, data needed before starting>

### Steps
| # | Do this | Expect |
|---|---------|--------|
| 1 | ... | ... |

### Acceptance criteria
- [ ] <the pass/fail bar>

### What to look out for
- <edge cases, and the specific KMS-nnnn codes that should appear>

### Defects
- UAT<n>-D<k> (<severity>): <what's wrong> → <linked issue / fix>
```

## Index

| UAT | Capability | Exercises | Status |
|-----|-----------|-----------|--------|
| UAT-1 | Temple onboarding & first sign-in | E1-S4, E1-S5, E1-S6 | READY (UAT1-D1 fixed) |
| UAT-2 | Profile & communication consent | E1-S8 (+ E1-S10 for effect) | READY (partial — see note) |

---

## UAT-1 — Temple onboarding & first sign-in

Exercises: E1-S4 (Firebase auth), E1-S5 (RBAC), E1-S6 (tenant provisioning)
Status: READY — UAT1-D1 fixed; awaiting a manual pass

The first genuinely demonstrable capability: a platform operator brings a temple onto the platform, its first administrator signs in, lands in an empty-but-working workspace, and the role boundaries hold.

### Preconditions / setup
- Backend and frontend running (staging preferred; local per `docs/DEPLOYMENT.md`).
- A `SUPER_ADMIN` account that can sign in.
- An email address and an Indian (+91) phone number you control, to act as the new temple's first admin.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Sign in as the Super-Admin | The platform view loads with the **Temples** list |
| 2 | Open **Add a temple**; fill name, web address (slug), address, latitude/longitude, timezone, currency, 80G flag, and the first admin's name / email / phone | The form accepts valid input; required fields are enforced |
| 3 | Submit with everything valid | Success; the new temple appears in the list as **Active**, user count **1** |
| 4 | Sign out. As the **new admin**, sign in with the registered email (Firebase-verified) or phone OTP | You land in the temple workspace — empty but working — with the temple nav; `GET /api/v1/whoami` returns `TEMPLE_ADMIN` and the new tenant. First sign-in binds your Firebase identity to the account (the "claim") |
| 5 | As the new admin, attempt a platform action (e.g. open **Add a temple** / `POST /api/v1/tenants`) | **403** — `KMS-4301`, and an audit-relevant log line for the denied attempt |

### Acceptance criteria
- [ ] Super-Admin can provision a temple + first admin from the screen.
- [ ] Invalid coordinates / timezone / duplicate web address / duplicate admin email are refused with actionable messages.
- [ ] The newly provisioned admin can sign in immediately and reach an empty-but-working workspace.
- [ ] A non-super-admin is refused all provisioning endpoints (403).
- [ ] Provisioning wrote an audit event (actor = the Super-Admin, tenant = the new temple).

### What to look out for
- Invalid latitude/longitude → `KMS-4002`; unusable timezone → `KMS-4001`; duplicate web address → `KMS-4901`; admin email already registered at that temple → `KMS-4902`.
- Nothing technical should ever reach the screen — every failure shows plain language plus a `KMS-nnnn` code.
- The audit trail: the new temple's own audit log (once its viewer is exercised) should show "temple provisioned by \<super-admin\>" with a null before-state.

### Defects
- **UAT1-D1 (Blocker) — RESOLVED 2026-08-09:** A freshly provisioned admin could not sign in. Provisioning stores `firebase_uid = "pending:<uuid>"`, and no code linked that pending record to the person's real Firebase uid on first sign-in — `AuthenticationFilter` looked up strictly by the real uid, found nothing, and treated them as having no account. E1-S6's test had masked this by manually running `UPDATE users SET firebase_uid = …`.
  Fixed by first–sign-in **claim-on-match** (approved mechanism: match by Firebase-verified contact, gated on verification): on a uid miss, if the token carries a verified email or an OTP-verified phone, the app adopts the real uid onto the single pending row whose email/phone matches. Narrow by construction — only `pending:` rows, only an exact verified-contact match (new `app.claim_contact` RLS escape, migration V4), refuses ambiguity, and cannot touch an already-active account. Proven by `PendingAccountClaimIT` (6 cases), and `TenantProvisioningIT` now signs the admin in through the real claim rather than a manual update. Issue #6 can close once this UAT-1 passes a manual run.

---

## UAT-2 — Profile & communication consent

Exercises: E1-S8 (profile & consent). The "takes effect on next notification" half needs E1-S10 (notification service) and is verified with that capability.
Status: READY (partial) — the profile management and consent are testable now; the notification effect awaits E1-S10. Note: like all UAT here, a manual run needs the frontend wired to the backend and the app running.

A person manages how their temple reaches them and records their consent to be contacted.

### Preconditions / setup
- A signed-in user of any role, with an account that has an email and phone.

### Steps

| # | Do this | Expect |
|---|---------|--------|
| 1 | Open **Profile** | Your name, email and phone are shown read-only; your preferred channel; and, if you have not consented, a consent prompt |
| 2 | Change the preferred channel (e.g. WhatsApp → SMS) and reload | The new channel persists and is returned by the profile |
| 3 | Read the consent text and choose **I agree** | Consent is recorded; the prompt goes away; the profile shows you have consented |

### Acceptance criteria
- [ ] Contact details are shown but not editable here.
- [ ] A preferred-channel change persists and is returned by the profile API.
- [ ] Consent is recorded with a timestamp and the version of the wording accepted.
- [ ] (E1-S10) A change of channel takes effect on the next notification; an unconsented user is not sent notifications.

### What to look out for
- An unrecognised channel is refused as ordinary validation (`KMS-4001`).
- The consent text states the purpose (reminders and service messages), the channels, and the right to withdraw.
- If the consent wording is later revised, a previously-consented user is asked again (version mismatch).

### Defects
- _None yet._
