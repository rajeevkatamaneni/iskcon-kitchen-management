# EPIC 6 — Workforce Management

**Goal:** Staff scheduling, volunteer shift posting with per-event reminder configuration, signup/release with waitlist auto-promotion, WhatsApp-first reminders honoring per-user channel preference, and one-off broadcasts.
**Depends on:** Epic 1 (auth, roles, notifications, jobs). Independent of Epics 2–5.
**Labels:** `epic:workforce`

---

## E6-S1 — Staff profiles and weekly schedule

**Verified by:** [UAT-047](../uat/UAT-047-staff-schedule.md)

**As a** Temple Admin, **I want** to maintain full-time staff schedules, **so that** who works when is visible to everyone who needs it.

**Assumptions:** Simple recurring weekly pattern + per-date exceptions (day off, swapped shift) — no payroll, attendance, or leave-balance accounting in release 1 (prior proposal's staff attendance/leave module is Phase 2+).

**Revised 2026-08-19 by E6-S8.** "Staff" was *a KITCHEN_STAFF user plus a profile with a free-text designation*, created on this screen. It is now an **employment record** (E6-S8), and this screen no longer creates one — it shows the people the register already holds and links to `/staff`, because two screens creating the same thing is two places for them to disagree. Consequences here: the grid shows whoever is currently employed rather than whoever has `active = true`; the name comes from the employment record, not from a users row; and a staff member with no app account appears on the grid but is told about a change the way they always were, since there is nobody to notify.

**Requirements:**
- Staff profile CRUD (admin); weekly template per staff (day → time range or Off), matching the approved wireframe's grid.
- Per-date exception entry (override one day without editing the template).
- Week view for all staff (the wireframe's schedule grid); staff see their own schedule on their dashboard.
- Schedule changes notify the affected staff member via preferred channel.

**Acceptance criteria:**
- [ ] Weekly grid renders template + exceptions correctly across a month boundary.
- [ ] Exception on one date leaves the template untouched.
- [ ] Affected staff receives a change notification; unaffected staff don't.

---

## E6-S2 — Volunteer shift posting with reminder configuration

**Verified by:** [UAT-048](../uat/UAT-048-post-a-volunteer-shift.md)

**As a** Temple Admin or Kitchen Staff member, **I want** to post volunteer shifts with capacity and reminder settings, **so that** each day's seva needs are visible and signable.

**Assumptions:** Locked decisions: reminder timing is part of event setup (not global); shift = role/title, description, date, time window, capacity, reminder offset(s). Multiple reminder offsets allowed (e.g. 48h + 24h), default single 24h. A posted shift is editable in every field until it is cancelled; a cancelled shift is not editable (`KMS-4928`). No duplicate-shift action and no recurring-series engine — duplication was built and withdrawn at Rajeev's direction, 2026-08-18: it saved one form's typing and cost a second concept on the screen, and a posted shift that can be edited covers the correcting case that actually came up.

**Requirements:**
- Shift CRUD; visibility: published shifts appear to volunteers immediately (no separate publish step — creation is publication; drafts unnecessary at this scale).
- Editing a posted shift: one form serves both posting and correcting, prefilled from the shift. The API renders times as `HH:mm:ss`; a time input reads `HH:mm`, so the form narrows them or opens blank and wipes them on save.
- Reminder config per shift: offsets editable until shift start; changes reschedule pending reminder jobs (E1-S9).
- Moving a shift that has signups reschedules their reminders but notifies nobody, so the screen says so and links to the shift's broadcast (E6-S7) rather than sending anything on the admin's behalf — the words are theirs to write, and the broadcast's rate guard still applies.
- Cancel shift → all signed-up volunteers + waitlist notified with apology template; signups closed.

**Acceptance criteria:**
- [x] Posted shift visible to volunteers with live capacity count (wireframe's card + progress bar).
- [x] Editing reminder offsets reschedules jobs (verify: pending job set matches config after edit).
- [x] Cancellation notifies every affected volunteer via their preferred channel and closes signup.
- [x] Every field of a posted shift can be edited, and the form opens prefilled from it — times included.
- [x] Editing a cancelled shift is refused with `KMS-4928`.
- [x] Moving a shift with signups warns that nobody was told; an edit that does not move it warns about nothing.
- [x] No duplicate action remains, on the screen or in the API.

---

## E6-S3 — Volunteer signup

**Verified by:** [UAT-049](../uat/UAT-049-volunteer-signs-up.md)

**As a** Volunteer, **I want** to browse open shifts and claim a spot, **so that** offering seva takes seconds.

**Assumptions:** Volunteers must have accounts (locked; email+phone captured at registration, E1-S8). One signup per volunteer per shift; overlapping-shift signups allowed with a warning (real families share duties; don't hard-block).

**Requirements:**
- "Available shifts" view (wireframe): date-grouped cards, capacity progress, sign-up action; filter by date range.
- Signup: atomic capacity check (no oversubscription under concurrency), confirmation via preferred channel with shift details.
- "My Shifts" view listing upcoming signups (wireframe).
- Full shift shows "Join waitlist" (E6-S5) instead of signup.

**Acceptance criteria:**
- [ ] Concurrency test: capacity 1, two simultaneous signups → exactly one succeeds, other gets a friendly full/waitlist response.
- [ ] Confirmation message delivered on signup; shift appears in My Shifts.
- [ ] Overlap warning shows when claiming a time-overlapping shift; volunteer may proceed.

---

## E6-S4 — Signup release

**Verified by:** [UAT-050](../uat/UAT-050-volunteer-releases-a-spot.md)

**As a** Volunteer, **I want** to release my spot when I can't make it, **so that** the temple isn't silently short-handed.

**Assumptions:** Locked feature. Releasable until shift start (no lock-out window in release 1 — a late release still beats a no-show; Phase 2 reliability tracking will observe patterns rather than restrict).

**Requirements:**
- "Can't make it? Release my spot" on My Shifts (wireframe wording); confirmation dialog states the spot goes to the waitlist.
- Release frees capacity → triggers waitlist auto-promotion (E6-S5) atomically.
- Shift owner (poster) sees release activity on the shift's roster view.
- Release recorded (who, when) — data for Phase 2 reliability, no judgment surfaced now.

**Acceptance criteria:**
- [ ] Release frees the spot and disappears from My Shifts immediately.
- [ ] Roster view shows the release event; capacity reflects it.
- [ ] Release after shift start is rejected with a gentle message.

---

## E6-S5 — Waitlist with auto-promotion

**Verified by:** [UAT-051](../uat/UAT-051-the-waitlist.md)

**As a** Volunteer, **I want** to join a waitlist on a full shift and be promoted automatically when a spot opens, **so that** released spots never quietly become empty shifts.

**Assumptions:** Locked Phase 1 feature. FIFO promotion. Promotion is automatic and immediate (no accept/decline handshake in release 1 — the promoted volunteer is notified and can release like any signup; a handshake adds latency that kills same-day backfills; revisit if promoted-then-released churn shows up).

**Requirements:**
- Join/leave waitlist; position visible to the volunteer.
- On capacity free (release, capacity increase, cancellation of a signup by admin): promote head-of-queue atomically with the freeing event; notify promoted volunteer via preferred channel with clear "you're in" message; remaining queue positions shift.
- Waitlisted volunteers get reminded of nothing (only signed-up volunteers get shift reminders); promotion before a pending reminder offset → promoted volunteer enters the normal reminder flow for remaining offsets.
- Shift roster shows waitlist to the poster.

**Acceptance criteria:**
- [ ] Release on a full shift with a 2-person waitlist promotes exactly the first, notifies them, and updates positions.
- [ ] Concurrency: simultaneous release + new signup on the last spot resolves without oversubscription or double-promotion.
- [ ] Promoted volunteer receives remaining scheduled reminders; un-promoted waitlisters receive none.
- [ ] Leaving the waitlist removes promotion eligibility immediately.

---

## E6-S6 — Scheduled shift reminders

**Verified by:** [UAT-052](../uat/UAT-052-shift-reminders.md)

**As a** Volunteer, **I want** reminders for shifts I signed up for, on my preferred channel, **so that** I show up when I promised.

**Assumptions:** Locked: WhatsApp primary channel per user preference (E1-S8), per-shift offsets (E6-S2), SMS/email fallback cascade (E1-S10). Reminder content: shift title, date/time, location line (tenant address default), release link.

**Requirements:**
- Reminder jobs scheduled per (signup × offset) at signup time; rescheduled on shift edit; cancelled on release/shift cancellation.
- Sends via notification service with full cascade + delivery status recorded.
- Volunteers signing up *after* an offset has passed skip that offset silently.
- Shift roster shows per-volunteer reminder delivery status to the poster (sent/delivered/failed) — this is the ops answer to "did people actually get told?".

**Acceptance criteria:**
- [ ] End-to-end in staging: signup → offset reached → WhatsApp reminder received; status visible on roster.
- [ ] Release before offset → no reminder sent (job cancelled, verified).
- [ ] Shift time edit reschedules pending reminders to correct new times.
- [ ] Failed WhatsApp with SMS fallback records both attempts on the roster view.

---

## E6-S7 — One-off reminder broadcast

**Verified by:** [UAT-053](../uat/UAT-053-broadcast-an-update.md)

**As a** shift poster, **I want** a button to blast an immediate message to everyone signed up, **so that** last-minute changes ("gate B today, not A") reach people now, not at the next scheduled offset.

**Assumptions:** Locked feature. Free-text within a pre-approved utility template shell (Meta requires templates; a generic "update about your shift: {{text}}" utility template covers this — verify template approval in E1-S10's Meta checklist).

**Requirements:**
- "Send update to all signed up" on the shift roster: compose (length-capped to template limits), preview, confirm, send via preferred channels; optionally include waitlist.
- Rate guard: max N broadcasts per shift per day (tenant config, default 3) — protects volunteers from a panicking poster.
- Broadcast + per-recipient delivery status recorded on the roster; audited (actor, content, timestamp).

**Acceptance criteria:**
- [ ] Broadcast reaches all signed-up volunteers on preferred channels; statuses recorded.
- [ ] Waitlist inclusion toggle works; default off.
- [ ] Fourth broadcast in a day is blocked with explanation; admin can raise the limit in tenant config.
- [ ] Content and actor audited.

---

## E6-S8 — Hiring, employment records, and letting go

**Verified by:** [UAT-008](../uat/UAT-008-add-your-team.md), [UAT-064](../uat/UAT-064-hire-and-let-go.md)

**As a** Temple Admin, **I want** to hire someone, keep what the temple has to know about them, promote them when their job changes, and record it properly when they leave, **so that** who works here is a record rather than a memory.

**Origin:** Rajeev, 2026-08-18, asking for a staff onboarding page under People, with a hire form, current staff listed, and past staff kept in their own section "so we have that info if the admin needs it."

### Decisions

**D1 — Hiring is the only door into a temple role.** E1-S12 removed both the add-a-person form and the role dropdown at Rajeev's direction; this story is where the capability they held has to land, or a temple cannot make a cook at all. Being hired grants a role and employment ending takes it away, and there is no third road. Rajeev: *"that should be the only way temple staff get access and their access gets revoked."*

**D2 — A job title and system access are two fields, never one.** He asked for a role field listing "Temple Admin, Kitchen Manager, Store Manager, Head Cook, Line cook, Janitor" — which mixes what someone may *do* with what they are *called*. Merging them would make adding "Pujari" to a dropdown an edit to the authorisation policy. So a **job title** is a label from a controlled vocabulary (`JobTitle`) that gates nothing, and **system access** stays the existing roles. The title *suggests* the access, so the common case is still one choice.

**D3 — The title is a picklist, not free text.** My call, offered to him and taken. Free text produces "Head cook", "head Cook", "HC" and "Head Chef" inside a month, and then the register cannot be grouped and the schedule cannot be read. `OTHER` carries the temple's own words for a job the list does not have, and is refused if left unnamed. The vocabulary lives in Java rather than a CHECK constraint, like `AuditAction`, so a temple naming a job we did not think of is not a migration.

**D4 — An employment record does not require an app account.** A janitor does not need a login, and demanding one would have every temple minting accounts nobody signs into. `staff_profiles.user_id` is nullable and the record carries its own name, phone and email. Granting access later creates the account, pending their first sign-in like any pre-made one.

**D5 — Ending employment is not deletion, and it forks.** Someone who resigns is still a devotee of this temple and keeps signing in as one, so their role drops back to volunteer. Someone dismissed for cause should not, so the form offers to disable the account outright and defaults that on for a dismissal and off for a resignation. Either way the record survives — a former cook is still the actor on last year's stock adjustment.

**D6 — An admin cannot end their own employment or strip their own access.** The last administrator of a temple doing so leaves nobody able to undo it, and the fix would be a platform operator editing the database. `KMS-4304` and `KMS-4302`.

**D7 — PAN is encrypted; Aadhaar waits for E6-S9.** BL-5 asks for both. PAN reuses the donor-PAN machinery exactly (`PanCipher`, now shared rather than living under `donation`): encrypted before it touches the database, shown as a masked last-four, and readable in full only through a separate request that writes `STAFF_PAN_VIEWED`. Aadhaar is deliberately absent — see E6-S9 for why a typed Aadhaar number is worse than none.

**D8 — Salary is not collected.** Offered to Rajeev on 2026-08-18 with the argument that a monthly figure nothing pays out is sensitive data with no reader; he agreed — *"We dont have it and dont need it."*

**D9 — Two permissions, not one.** `MANAGE_STAFF` (who works here, and their date of birth, address and PAN) is held apart from `MANAGE_STAFF_SCHEDULE` (when they work). Both sit with Temple Admin today; the split exists so that when BL-4 gives a kitchen manager the roster, everyone's identity documents do not travel with it.

**D10 — The founding administrator is employed, not merely created.** Provisioning (E1-S6) now writes their employment record too. Without it they would be the one person on no screen at all: not a devotee, and never hired. V57 does the same retrospectively for every temple that already exists.

**Requirements:**
- `staff_profiles` becomes the employment record: name, phone, email, job title (+ the temple's own words for `OTHER`), employment type, date of joining, date of birth, address, emergency contact (name, relationship, phone), encrypted PAN, notes, employment status, last working day, end reason.
- Hire from an existing devotee (promoting the account they already hold, so their seva history stays with them) or as somebody the temple has no record of.
- Edit everything, including promotions: changing job title, and granting or withdrawing access.
- End employment with a status (resigned / dismissed / contract ended), a last working day, a reason, and a decision about their sign-in.
- The register in two lists: current, and former with how and when they left.
- Audit: `STAFF_HIRED`, `STAFF_UPDATED`, `STAFF_EMPLOYMENT_ENDED`, `STAFF_PAN_VIEWED`. The before/after names the job title and the access — never the PAN or the address.
- V57 backfills every existing profile and hires retrospectively every `TEMPLE_ADMIN`/`KITCHEN_STAFF` user that has none, with a job title of `UNRECORDED` rather than a guessed one; the screen flags those so an admin fixes them.

**Acceptance criteria:**
- [x] Hiring a devotee promotes the account they already have — no second account, no split history.
- [x] Someone can be employed with no app account at all, and appears on the register.
- [x] Access cannot be granted without both an email and a phone number (`KMS-4950`).
- [x] `OTHER` without the temple's own words is refused (`KMS-4001`).
- [x] Hiring the same person twice is refused (`KMS-4926`).
- [x] A promotion creates the login the person never had, pending their first sign-in, and is audited.
- [x] Ending employment moves the record to Former, keeps every reference to them, and either returns them to being a devotee or disables the account.
- [x] A former record cannot be edited (`KMS-4949`).
- [x] An admin cannot end their own employment (`KMS-4304`).
- [x] A PAN is not readable in the table; reading it writes `STAFF_PAN_VIEWED`.
- [x] Kitchen staff are refused the whole surface.
- [x] A temple's founding administrator is on the register from the day the temple exists.

---

## E6-S9 — Is this Aadhaar card real?

**Status:** SPECIFIED, NOT BUILT. Needs a real Aadhaar QR to test against.

**Verified by:** UAT to be written with the story.

**As a** Temple Admin, **I want** to know the identity document in my hand is genuine, **so that** the trust ISKCON extends by default is not extended to a forged card.

**Origin:** BL-5, and Rajeev on 2026-08-19: *"can you download a digital aadhar from the government website and show it on screen so the admin can verify if the aadhar number / copy they gave is real?"*

### What is not possible, and why the story is not that

**Nobody can download another person's Aadhaar.** e-Aadhaar download sends an OTP to the holder's own registered mobile; e-PAN likewise. This is the design, not a gap.

**Aadhaar authentication is licensed.** The API that answers "is this number real" is open only to UIDAI-approved AUA/KUA entities. Since the Supreme Court struck down §57 of the Aadhaar Act in 2018, a private company cannot require Aadhaar authentication for employment without a specific law behind it. A temple kitchen SaaS will not hold that licence.

### What is possible, and is better

**Every Aadhaar card and e-Aadhaar carries a Secure QR code that UIDAI has digitally signed.** Scanning it and verifying the signature against UIDAI's public certificate proves the document in the room is genuine and unaltered — which a download never could, since a download proves nothing about the paper someone handed over. It needs no licence, no vendor, no fee and no call to UIDAI.

**Requirements:**
- Capture the QR from the laptop or phone camera on the hire form. No file upload: a photograph of a screen is a photograph of a screen.
- Decode it (big integer → GZIP → fields + a 256-byte RSA signature + a JPEG of the holder's photograph) and verify the signature against UIDAI's published certificate. A card that fails verification is rejected, loudly, and nothing is stored.
- **Store only what UIDAI signed:** verified name, date of birth, gender, the photograph, the last four digits, and when it was verified. **Never the full Aadhaar number** — which also satisfies UIDAI's masking rule by construction, and means the admin never types it.
- Show the signed name and photograph beside what was typed on the hire form, so a mismatch is the admin's to judge.
- Reading a stored Aadhaar photograph is audited, like the PAN.

**Open, and needed before this can be built:**
- **A real Aadhaar QR to test against.** A valid UIDAI signature cannot be fabricated, so until one is scanned this can only be claimed to compile. Rajeev supplied his own card on 2026-08-19; the file must live outside this repository (see the `.gitignore` entry) because git history is permanent.
- PAN verification is a separate decision: no trustworthy QR equivalent, so it means a paid third-party API (~₹1–3 a check) returning name and validity. Not started; needs his account and his word.
