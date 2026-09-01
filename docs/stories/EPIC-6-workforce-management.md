# EPIC 6 — Workforce Management

**Goal:** Staff scheduling, volunteer shift posting with per-event reminder configuration, signup/release with waitlist auto-promotion, WhatsApp-first reminders honoring per-user channel preference, and one-off broadcasts.
**Depends on:** Epic 1 (auth, roles, notifications, jobs). Independent of Epics 2–5.
**Labels:** `epic:workforce`

---

## E6-S1 — Staff profiles and weekly schedule

**Verified by:** [UAT-047](../uat/UAT-047-staff-schedule.md)

**As a** Temple Admin, **I want** to maintain full-time staff schedules, **so that** who works when is visible to everyone who needs it.

**Assumptions:** Simple recurring weekly pattern + per-date exceptions (day off, swapped shift) — leave and staff payments arrive in release 1 (REQUIREMENTS v1.1, 2026-08-20 — the temple asked for them); **leave-balance accrual and attendance stay in Phase 2**, the first because a balance nobody reconciles misleads, the second because hourly pay was dropped and hours worked were the only thing that would have needed it.

**Revised 2026-08-19 by E6-S8.** "Staff" was *a KITCHEN_STAFF user plus a profile with a free-text designation*, created on this screen. It is now an **employment record** (E6-S8), and this screen no longer creates one — it shows the people the register already holds and links to `/staff`, because two screens creating the same thing is two places for them to disagree. Consequences here: the grid shows whoever is currently employed rather than whoever has `active = true`; the name comes from the employment record, not from a users row; and a staff member with no app account appears on the grid but is told about a change the way they always were, since there is nobody to notify.

**Revised 2026-08-20 by E6-S11.** Per-date exceptions leave this screen entirely. This page answers
*what is this person's ordinary week?*, and a swapped Thursday is not a pattern — so the override is
written from the week grid, where the week is actually being read, and the template page carries the
template alone. A second consequence follows from E6-S10: an exception can no longer say "not
working". An absence is a leave record, and the only day off this table still holds is the outbound
half of a swap, where the person is not absent at all.

**Requirements:**
- Staff profile CRUD (admin); weekly template per staff (day → time range or Off), matching the approved wireframe's grid.
- ~~Per-date exception entry (override one day without editing the template).~~ Moved to **E6-S11**,
  which writes overrides from the week grid; this page edits the template only.
- Week view for all staff (the wireframe's schedule grid); staff see their own schedule on their dashboard.
- Schedule changes notify the affected staff member via preferred channel.

**Acceptance criteria:**
- [x] Weekly grid renders template + exceptions correctly across a month boundary.
- [x] Exception on one date leaves the template untouched (written from the grid — E6-S11).
- [x] Affected staff receives a change notification; unaffected staff don't.

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

### Revised 2026-08-20 by the build brief

**D8 is reversed.** *"Salary is not collected"* was right on 2026-08-18 — a monthly figure nothing
pays out is sensitive data with no reader — and it stopped being right when the temple asked for
staff payments in Phase 1 (REQUIREMENTS v1.1). Salary is now an optional monthly figure on this
record, and everything that reads it lives in **E6-S13**. D9's split is what keeps it from
travelling: `MANAGE_STAFF` reaches pay, `MANAGE_STAFF_SCHEDULE` does not, and E6-S12's Kitchen
Manager deliberately holds only the second.

**D9's "when BL-4 gives a kitchen manager the roster" has happened**, in E6-S12, and the split held
exactly as it was designed to. No new permission was needed.

**Hiring and ending employment both gained a cross-temple half — see E9-S2.** Ending employment may
now raise a ban record against the person, on this same request and in the same transaction, because
it is a decision made *at* the dismissal; a record that could be raised against anybody at any time
would be a different and far more dangerous feature. Hiring runs the check that reads those records,
and a finding never blocks the hire. Both surfaces sit on this story's screens; the reasoning, the
matching and the constraints accepted on purpose belong to Epic 9, which owns everything in this
product that crosses tenant isolation.

**Consequences here**: `staff_profiles` gains a PAN fingerprint (so raising a ban never has to
decrypt a PAN and quietly become a `STAFF_PAN_VIEWED`), the Aadhaar triple E6-S9 will fill, and the
record of what the hire check found and what the admin decided about it.

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

---

## E6-S10 — Leave: time off, sick and unpaid

**Status:** DONE 2026-08-20. Built from the 2026-08-20 build brief (B7, §4). Leave moved from
Phase 2 into Phase 1 at the temple's request — REQUIREMENTS.md v1.1, signed off by Rajeev.

**Verified by:** UAT to be written. Automated cover: `StaffLeaveIT`, `frontend/__tests__/leave.test.tsx`.

**As a** Temple Admin, **I want** leave asked for, answered and recorded in one log, **so that** the
roster, the workforce count and the kitchen all bend around the same answer to "who is out, and
why".

**Assumptions:** No accrual, no balances, no carry-forward, no encashment, and no attendance — the
temple asked for none of them, and REQUIREMENTS v1.1 states them as out rather than leaving them to
be assumed back in. Three types only: time off, sick, unpaid. A half day covers one date. Approval
sits with the temple admin, or with a Kitchen Manager where a temple has appointed one (E6-S12).

### Decisions

**D1 — A request-and-approve log, and nothing that accrues.** A balance column nobody reconciles is
a number that misleads whoever reads it next, and the only question the kitchen actually has is
whether this person is in on Thursday. `staff_leave` (V62) records who, what kind, which dates, who
asked, who answered and what they said. There is no entitlement anywhere in the schema.

**D2 — Two ways in, because there are two situations.** A cook with a login asks from their own
account page and waits to be answered. A janitor has no app at all, so the admin or manager writes
it down — and that record lands already `APPROVED`, because the person recording it and the person
who would have approved it are the same person in the same act. Leaving it pending would put a row
in a queue waiting for its own author.

**D3 — Back-dating is allowed, and approved leave can be revoked.** Somebody rings in sick at six in
the morning and the record is written afterwards; that is how sick leave arrives, and refusing
yesterday's date would only teach people to type today's. Revocation is the mirror of it, for the
day somebody comes in after all.

**D4 — Marking somebody off on the week grid *is* a leave record.** One concept, not two. The grid's
old "not working" exception said the same thing a leave record says, and two ways of saying it are
two things to keep in step. V62 converts every existing `working = false` exception into the
approved leave record it was standing in for, then adds a CHECK that refuses any future one unless
it is half of a swap — so the rule is held by the database rather than by convention. The
carried-over records name no approver, because the rows they came from never recorded one; an
invented name would have been worse than an honest blank.

**D5 — A decision notifies the person, and they cannot opt out of it.** Category `OPERATIONAL`: it
is the consequence of something they did, not a message the temple chose to send them. Somebody with
no login and no contact details is simply not notified — there is nowhere to send it. The
notification is queued outside the transaction that made the decision, so a send that cannot be
queued never rolls back the answer.

**D6 — Only *approved* leave empties a cell.** A request still waiting is not an absence: the cook
is expected in until somebody says otherwise, and a grid that emptied itself the moment somebody
asked would let anybody take a day off by requesting one.

**D7 — A half day leaves them in.** They are in the kitchen for half of it, which is more use to a
head count than pretending they are not there; the grid says which half of the fact it is showing.
Decided while building, and recorded here because it is the one place the workforce count departs
from "approved leave drops them out".

**D8 — Overlapping leave is refused in the service, not by an exclusion constraint.** An `EXCLUDE`
would state the rule where it cannot be forgotten, but a range exclusion scoped by tenant and person
needs `btree_gist` installed in every environment — a deployment step bought against a race nobody
has, namely two people at one temple recording the same cook's leave in the same instant.

**Requirements:**
- `staff_leave` per V62: type, from/to dates, half-day flag, optional reason, status
  (`PENDING`/`APPROVED`/`DECLINED`/`REVOKED`), requester, decider, decision note. Keyed on the
  employment record, not the user, so somebody with no login takes leave like anybody else.
- `REQUEST_OWN_LEAVE` for the staff member's own list, request and withdrawal; `APPROVE_LEAVE` for
  the queue, recording on somebody's behalf, approving, declining and revoking.
- The approver's queue is one filtered list under People — waiting, approved, everything — not two
  screens, because approving something must not make it vanish from one list without appearing in
  the other.
- The leave section on the account page shows what the person asked for and what came back.
- Audited on every write; a decision notifies the affected person on their preferred channel.

**Acceptance criteria:**
- [x] A staff member with a login requests leave and sees the answer on their own page.
- [x] Leave recorded on behalf of somebody without a login is approved in the same act.
- [x] A half day spanning more than one date is refused (`KMS-4006`), in the service and in the database.
- [x] Leave dates in the wrong order are refused (`KMS-4005`).
- [x] Overlapping leave for the same person is refused (`KMS-4953`).
- [x] Answering an already-answered request is refused (`KMS-4954`); revoking something never approved is refused (`KMS-4955`).
- [x] Withdrawing somebody else's request is refused (`KMS-4306`); a volunteer with no employment record is told so (`KMS-4403`).
- [x] Approved leave empties the person's cell on the week grid and drops them from the workforce count; a pending request does neither.
- [x] Approving and declining both notify the person; somebody with no contact details is skipped rather than failing the decision.
- [x] V62 leaves no `working = false` exception behind — the CHECK it adds would fail the migration if it did.

---

## E6-S11 — The week grid, edited where it is read

**Status:** DONE 2026-08-20 (B7, build brief §6). Replaces E6-S1's per-date exception entry.

**Verified by:** UAT to be written. Automated cover: `StaffScheduleIT`, `frontend/__tests__/staff-schedule.test.tsx`.

**As a** Temple Admin or Kitchen Manager, **I want** to change one person's one day on the grid I am
already looking at, **so that** a swapped Thursday is one gesture on the screen that shows the week,
not an edit to the pattern it is an exception to.

**Assumptions:** The template page answers *what is this person's ordinary week?* — and a swap is not
a pattern. Overrides already rendered distinctly on the grid before this story, so an adjusted week
already looked adjusted; what was missing was the ability to make one there.

### Decisions

**D1 — Per-date exceptions leave the template page entirely.** They are written from the grid and
nowhere else, and `/staff-schedule/{id}` keeps the template alone. E6-S1's requirement list is
amended accordingly.

**D2 — Four actions on a cell, each writing that date alone.** Change the hours; mark them off; add
them on to a day they do not normally work; swap with another day. Nothing any of them writes
touches the template.

**D3 — A swap is one act with two halves, written together.** Both dates travel in one request and
one transaction, and both rows share a `swap_link_id`, so undoing either undoes both. This is the
case people get wrong by doing half of it: the cook marked off Thursday and never added to Saturday
reads on the grid as somebody who simply vanished. A swap onto the same day is refused (`KMS-4957`).

**D4 — Approved leave is on the grid, read-only.** A manager sees why somebody is out and cannot
schedule over it; putting them in means revoking the leave first (`KMS-4956`), which is a decision
with a name on it rather than a cell quietly overwritten.

**D5 — The count at the foot of each column is the single source.** The Today tile and the planner
pebbles read the same figure (E6-S14) rather than each screen adding up its own columns. A grid that
totalled its own would be a fourth opinion about how many cooks there are.

**D6 — No overtime.** Adding a salaried cook to an extra day changes the roster, not their pay.
Nothing here reaches E6-S13.

**Requirements:**
- `swap_link_id` on `staff_schedule_exceptions` (V62), and the CHECK that makes a lone day-off
  override impossible — an absence is a leave record (E6-S10).
- Grid cell actions behind `MANAGE_STAFF_SCHEDULE`: set hours, add on, mark off (which posts a leave
  record), swap, and undo.
- `ScheduleResolver` is the one place the resolution order is written: template, then per-date
  override, then approved leave, which wins over both.
- `WeekScheduleView` carries per day: the resolved hours, whether an override decided it, the
  override's id, the swap link, and any approved leave with its label.
- The staff member's own schedule view and the affected-staff notification (E6-S1) are unchanged.

**Acceptance criteria:**
- [x] Changing one day's hours from the grid leaves the template untouched and shows the day as adjusted.
- [x] Marking somebody off from the grid creates an approved leave record, and the cell reads as leave.
- [x] A swap writes both halves; undoing either removes both.
- [x] A swap where both dates are the same is refused (`KMS-4957`).
- [x] Scheduling over approved leave is refused (`KMS-4956`).
- [x] The grid resolves correctly across a month boundary.
- [x] The template page no longer offers per-date exceptions.
- [x] The foot of each column shows staff and volunteers separately, from `WorkforceService`.

---

## E6-S12 — A fifth role: Kitchen Manager

**Status:** DONE 2026-08-20 (build brief §5). Closes **BL-4**.

**Verified by:** `RolePermissionsTest`; exercised throughout `StaffLeaveIT` and `StaffScheduleIT`.

**As a** Temple Admin, **I want** to appoint somebody to run the kitchen's people without handing
them the run of the temple, **so that** the person who decides Thursday's shifts can also decide the
leave that Thursday bends around, and still cannot read anybody's salary.

**Assumptions:** "The kitchen manager can approve leave" collides with E6-S8's D2 — a job title is a
label and gates nothing. Something had to give, and it was not that rule.

### Decisions

**D1 — More roles, not a second concept beside them.** This is BL-4's own recommendation, taken
unchanged: an employee "type" sitting next to a role would mean two things to check before every
action and two places for them to disagree. `RolePermissions.java` was built to absorb exactly this.

**D2 — Everything `KITCHEN_STAFF` holds, plus `MANAGE_STAFF_SCHEDULE`, `APPROVE_LEAVE` and
`REQUEST_OWN_LEAVE`.** Those are the two decisions that make somebody a manager rather than a cook:
the roster, and the leave the roster has to bend around.

**D3 — Deliberately not `MANAGE_STAFF` — and that single omission is the whole of the
"who may see pay" requirement.** An earlier claim that a new permission split was needed was wrong
and is corrected here: `/staff` is behind `MANAGE_STAFF` and is the only screen salary and PAN
appear on; `/staff-schedule` is behind `MANAGE_STAFF_SCHEDULE` and carries neither. So the
requirement is not a split but an absence.

**D4 — "If a temple has appointed one" falls out for free.** A temple with nobody holding the role
has only its administrator approving leave, and nothing anywhere has to test for the role's
existence.

**D5 — The title still grants nothing; the access grants.** The hire form suggests an access level
from the job title, so choosing *Kitchen Manager* suggests the role and the admin may override it.
E6-S8's D2 stands exactly as written.

**Requirements:**
- `User.Role.KITCHEN_MANAGER` and its permission set in `RolePermissions.java`.
- V61 widens the `users_role_valid` CHECK. The constraint listing the roles has to be edited when a
  role is added; that is what it is for.
- Navigation and route guards admit the role to the roster, the leave queue and the kitchen screens,
  and to nothing else.

**Acceptance criteria:**
- [x] A Kitchen Manager can open the roster, edit the week grid, and approve or decline leave.
- [x] A Kitchen Manager is refused the staff register, and with it every surface salary or PAN appears on.
- [x] The database refuses a role outside the five.
- [x] A temple with no Kitchen Manager behaves exactly as before.

---

## E6-S13 — Staff pay: salary, payments, advances and docking

**Status:** DONE 2026-08-20 (B8, build brief §7). Staff payments moved from Phase 2 into Phase 1 at
the temple's request — REQUIREMENTS.md v1.1.

**Verified by:** UAT to be written. Automated cover: `StaffPayIT`, `frontend/__tests__/staff-pay.test.tsx`.

**As a** Temple Admin, **I want** to record what the temple pays its staff — salary, advances, and
what a payment recovered — **so that** the money that leaves the temple's hands is a record somebody
can check against a bank statement.

**Assumptions:** Salaried staff only. Hourly was dropped as more trouble than it is worth until
somebody asks for it, and with it went the only reason to record attendance. This is why the story
sits in Epic 6 and not Epic 7: a payment to a cook is a fact about a person the temple employs, not
about a donor or a vendor.

### Decisions

**D1 — The app records; it does not compute what is owed.** The admin types every figure, the final
settlement included. Working out salary owed needs a pay period, a start date and a ledger of
settled periods — that is payroll, and nobody asked for payroll. The boundary is the whole design,
so it is stated rather than implied.

**D2 — The cash-advance balance is the one exact number, and it is never stored.** Advances given
minus deductions recovered, both of which are rows, computed on read. A stored balance is a second
version of the truth that drifts from the entries it claims to summarise; there is deliberately no
balance column anywhere in V63.

**D3 — Salary is a monthly figure and is optional.** A temple takes somebody on before pay is
agreed, and a part-timer paid daily in cash may have nothing recorded at all — so the termination
screen must be able to say *no salary recorded* rather than show a confident zero. There is no
`DEFAULT 0`, precisely so the two answers stay distinguishable.

**D4 — Payments are recorded on the staff member's own record.** A payment is a fact about a person,
and an admin recording one is already looking at them. A separate payments screen was the
alternative and would have meant finding the person twice.

**D5 — A wrong entry is voided, not reversed and not edited.** The stock ledger corrects itself with
a compensating entry because its only consumer is a sum; this table is read one row at a time by an
administrator answering "what did we pay Ramesh in July", and a mistyped 50,000 beside a −50,000
beside a 5,000 answers that badly three times over. So the row stays, stamped with who struck it,
and every total ignores it — which is why `staff_payments` is deliberately not under
`make_append_only()`. A payment that has already had advances docked against it cannot be voided at
all (`KMS-4961`), because that would quietly hand somebody their advance balance back.

**D6 — Docking is a link, not a subtraction.** Each deduction names the advance it repays, so the
advance balance falls out of the rows and nobody maintains it. A payment is gross; the net is gross
minus its deductions, computed rather than stored so the two cannot disagree.

**D7 — A currency on the temple, and only the new columns are neutral.** `tenants.currency` has
existed since V1 and had never been read; it is read now, and everything built here is named
`amount` rather than `amount_inr`. The existing rupee-named columns across donations, the wish list,
invoices and purchase orders stay exactly as they are — retrofitting a dozen columns, their views,
exports and screens for a temple that does not exist is churn spent on a guess.

**D8 — What the termination screen shows, since "display what they owe" still has to mean
something.** The advance balance, exactly, because it is arithmetic; and the last salary payment
with its date beside it — *"last recorded payment 31 July; terminating 12 September"* — leaving the
admin to draw their own conclusion about the months between. Showing what we know beats inventing a
figure that looks authoritative and is not.

**Requirements:**
- V63: `staff_profiles.monthly_salary` (nullable, positive), `staff_payments`, `staff_advances`,
  `staff_payment_deductions`, and an ISO-4217 CHECK on `tenants.currency`.
- Payments: date, gross amount, mode (cheque / cash / payroll), reference, purpose (salary /
  settlement), note. A cheque or payroll payment needs its reference (`KMS-4008`); cash does not,
  because demanding one there only teaches people to type a full stop.
- Advances: cheque or cash only — an advance is by definition not part of a payroll run.
- A payment may be recorded for former staff: a final settlement is normally paid after the last
  working day.
- Every entry point behind `MANAGE_STAFF`. Pay is served through `StaffPayView` alone and never
  added to `StaffProfileView`, which the roster and a staff member's own schedule both read.
- Audited on every write, void included.

**Acceptance criteria:**
- [x] A salary can be left unrecorded, and the screen says so rather than showing zero.
- [x] A payment with deductions reduces the advance balance by exactly what it recovered.
- [x] Deductions totalling more than the payment are refused (`KMS-4958`); more than the advance's remainder, refused (`KMS-4959`); against a fully recovered advance, refused (`KMS-4960`).
- [x] A voided payment stays on the record, names who struck it, and is excluded from every total.
- [x] A payment with deductions against it cannot be voided (`KMS-4961`).
- [x] The advance balance is computed from the rows every time and stored nowhere.
- [x] A Kitchen Manager cannot reach any of it.
- [x] The termination screen states the advance balance exactly and names the last salary payment and its date.
- [x] Amounts render in the temple's own currency.

---

## E6-S14 — Workforce: how much of a kitchen there is today

**Status:** DONE 2026-08-20 (B1 and B3, build brief §6b).

**Verified by:** UAT to be written. Automated cover: `StaffScheduleIT`, `TodayIT`,
`frontend/__tests__/today.test.tsx`, `frontend/__tests__/planner.test.tsx`.

**As a** Temple Admin or Kitchen Staff member, **I want** one honest read on who is actually in
today, **so that** I know before the morning starts whether there is enough of a kitchen to cook
with.

**Assumptions:** Leave had to land first (E6-S10) — a tile that counts somebody who is on leave is
worse than no tile at all.

### Decisions

**D1 — It replaces the *Shifts unfilled* tile.** That tile warned about a shift on an unnamed date
and gave an admin nothing they could act on. The question they actually have is about today.

**D2 — One number, computed once.** `WorkforceService` over `ScheduleResolver` is the single source;
the foot of the week grid, the Today tile and the planner pebbles all read it. Three screens each
counting for themselves is three screens that disagree by one about the same Thursday, with nobody
able to say which is right.

**D3 — Staff and volunteers are counted apart and never summed.** A full-time cook and a two-hour
evening volunteer are not interchangeable, and a single figure of "seven" would hide which seven.

**D4 — *Staff in* means the template, adjusted by any per-date override, minus approved leave, over
currently employed people only.** Somebody who left in March must not appear on April's grid, and
counting their old template as a body in the kitchen would be worse than merely untidy.

**D5 — *Volunteers* means signups on shifts falling that date, cancelled shifts excluded.** A
devotee who took two shifts on one day counts twice: the question is how many pairs of hands turn
up, not how many people the temple knows.

**D6 — Two pebbles on the daily and weekly planner, and none on the monthly.** The month grid is
already fighting for room, and clicking a day opens the daily view, which carries them.

**Requirements:**
- `GET /api/v1/workforce` over a date range, returning every date in it including the ones nobody is
  in on — a caller drawing seven columns needs seven answers.
- The Today tile, labelled *Working today*, linking to the roster.
- Pebbles between the date and the festival line on the daily view, and on each weekly tile.
- Behind `MANAGE_MEAL_PLANS`, the permission both temple roles hold; the figure carries no pay and
  no identity documents, so it does not belong behind `MANAGE_STAFF`.

**Acceptance criteria:**
- [x] The tile, the grid's column totals and the planner pebbles show the same two numbers for the same date.
- [x] Approving leave for somebody lowers the staff figure for those dates; a pending request does not.
- [x] A per-date override adding somebody on raises it; a swap moves it from one day to the other.
- [x] Former staff are absent from the count entirely.
- [x] A date with nobody in returns zero rather than being missing.
- [x] The monthly planner carries no pebbles.

---

## E6-S15 — Where the schedule is short of hands

**Status:** DONE 2026-08-31 (review item SS1, signed off by Rajeev).

**Verified by:** [UAT-078](../uat/UAT-078-short-of-hands.md); the grid it changes is also covered by
[UAT-047](../uat/UAT-047-staff-schedule.md), which wants a pass adding the footer row and the list.
Automated cover: `CrewCoverageIT`, and `frontend/__tests__/staff-schedule.test.tsx`.

**As a** Temple Admin or Kitchen Manager, **I want** the staff schedule to show what each day
*needs* beside who is in, **so that** a day three weeks out that has nobody to cook dinner is
something I see rather than something I discover.

**Assumptions:** Both halves already existed and had never been put on the same screen. The grid
showed supply only — seven columns of hours under a foot reading *In that day · 4 staff, 2
volunteers* — while the requirement sat on the planner, where `MealComposer` warns when
`crew.rostered < crewRequired`. So a gap was visible only to somebody who happened to open the right
meal on the right day. `crew_required` has been on `meal_plans` since `V67`, set by the planner when
the meal is planned; it is not recorded after the fact.

### Decisions

**D1 — Colour by shortfall, never by head count.** The comment asked for a heatmap, and a heatmap of
how many people are in would be decoration: a busy day with eight cooks and a quiet day with eight
cooks would look identical, and `DESIGN_SYSTEM.md` reserves status colour for status. Being short of
hands is a status; being well attended is not. **The raw-headcount heatmap is therefore rejected**,
and what replaced it is coverage against requirement.

Two tones and no invented threshold. Any shortfall is a warning, which is what that colour is
already for. Danger is kept for the one categorically different case — a meal that named a number
and has **nobody at all** — which is not a deeper shade of short-staffed but a meal with no kitchen,
and is a fact rather than a cut-off somebody chose. Colour never carries it alone: every cell says
its shortfall in words and names the meal that is short, and the colour only makes it findable
across seven columns.

**D2 — Per meal, per day; the day takes the deepest meal's shortfall, not the sum.** Two cooks
splitting 06:00–14:00 and 14:00–22:00 make a comfortable-looking day and leave dinner three short,
so a day-grain comparison would report the day as fine. The deepest short meal wins and travels with
the number so the screen can name it — *Dinner — 1 of 4* — rather than leave a manager opening three
meals to find out which. **Adding the meals' shortfalls together was rejected**: it answers a
question nobody asked, and the same cook can be short at two meals.

**D3 — `CREW_NOT_SET` is not `COVERED`.** A day whose meals carry no crew figure is not a day that
needs nobody. Null is not zero, and drawing the two alike is how a month of unplanned days comes to
look reassuring; the list under the grid says out loud how many such days are ahead rather than
reading as an all-clear it has not earned. Four states, and each is a different sentence:
*No meals*, *Crew not set*, *Covered*, *N short*.

**D4 — This screen counts nobody.** `CrewCoverageService` reads `WorkforceService` for the roster
and `MealCrewService` for what each meal takes and has, and folds them onto one line per day. A head
count of its own would be a fifth opinion about how many cooks there are on the same Thursday, and
the one screen meant to settle the question would be the one that reopened it. That honours
**E6-S11 D5** (the foot of the column is the single source) and **E6-S14 D2** (one number, computed
once), and a test asserts the footer figure and the coverage endpoint agree.

**D5 — One shortfall number over staff and volunteers together, with the two still reported
apart.** Not a new decision — E6-S14 D3 keeps them apart for every other reader, and a meal is
satisfied when staff plus volunteers reaches the planned number without caring which. So the
shortfall is *three short* and the day still says *4 staff, 2 volunteers* beside it, because a
manager filling the gap needs to know which kind of person is missing even though the meal does not.

**D6 — No month view.** A month of days by staff is a wall in which the three days that need a
telephone call are no easier to find than they are today. The question is *where am I short*, and it
is answered by the week's footer plus a *short of hands in the next 30 days* list. **The month grid
was considered and rejected** on that, not on effort.

**Requirements:**
- `GET /api/v1/crew-coverage?from=&to=` behind `MANAGE_STAFF_SCHEDULE`, capped at 62 days — the same
  cap as `/api/v1/workforce` and `/api/v1/meal-crew`, the two endpoints it reads. Every date in the
  range comes back, including the ones with nothing planned: a caller drawing seven columns needs
  seven answers.
- One endpoint, not two. The screen asks it *this week* for the grid's foot and *the next thirty
  days* for the list; splitting them would leave two shapes to keep agreeing about the same Thursday.
- The existing `/staff-schedule` screen gains a **Short of hands** footer row above *In that day* —
  need above supply, in the order the question is asked — and dates under the day names on the column
  headers, because a shortfall a manager is about to act on wants a date and not a weekday.
- A *Short of hands in the next 30 days* list under the grid, each entry naming the day, how many
  short, and the meal with what it has against what it asked for.
- Marking somebody off or changing their hours reloads the coverage as well as the roster: a footer
  left on the old figure would quietly contradict the row above it.
- No migration, no new error code, and no new menu entry — this is the schedule screen doing its job,
  not a second screen about the same thing.

**Acceptance criteria:**
- [x] A day with no meals is its own state and is not drawn as covered.
- [x] A day whose meals carry no crew figure reads as *Crew not set*, never as covered.
- [x] The day's shortfall is the deepest meal's, and the meal is named with its rostered and required figures.
- [x] A day with more hands than it asked for reads as covered, and the shortfall never goes negative.
- [x] A volunteer counts towards the shortfall exactly as a staff member does.
- [x] Every date in the range comes back, including empty ones.
- [x] The roster figures are the same numbers the foot of the grid already shows.
- [x] A range over 62 days is refused.
- [x] Someone without `MANAGE_STAFF_SCHEDULE` cannot read it.
- [x] Every coloured cell also states its shortfall in words.
- [x] The thirty-day list marks the case where nobody at all is rostered, and says how many days ahead have no crew figure.
- [ ] The column headers carry the date under the weekday. *(Real, and only provable by eye — no test asserts it.)*

---

## E6-S16 — Conduct notes on an employment record

**Status:** DONE 2026-08-31 (review item STAFF1, signed off by Rajeev).

**Verified by:** [UAT-079](../uat/UAT-079-staff-conduct-notes.md). Automated cover:
`StaffConductNoteIT`, and `frontend/__tests__/staff-conduct-notes.test.tsx`.

**As a** Temple Admin, **I want** to write a dated, attributed note about how a member of staff has
conducted themselves, **so that** what happened is recorded by the person who saw it, on the day
they saw it, and is still there when it matters.

**Assumptions:** `staff_profiles.notes` exists and is the wrong place. It is an unlabelled `TEXT`
column shown as a single-line input called *Notes*, with no author, no date and no history, which
anybody holding `MANAGE_STAFF` may overwrite. It stays exactly as it is, carrying *prefers the early
shift*; nothing here reads it, writes it or migrates it.

### Decisions

**D1 — Append-only, because an editable employment note is worth nothing.** The whole value of
*written on 3 March by the head cook* is that it was written on 3 March by the head cook and has not
moved since. An editable note is not evidence; it is the current opinion of whoever edited it last,
which is what `notes` already is. `V84` uses `make_append_only()`, the mechanism the stock ledger,
the audit log and equipment state changes already use, and the integration test proves the refusal
against the unprivileged application role rather than trusting the service. A note written in error
is corrected the way every other append-only record in this system is corrected — by adding another
that says so. **There is deliberately no retraction flag**: a retraction is a later note, and a flag
would be a fourth column arriving through the back door.

**D2 — Its own permission, held narrowly.** `MANAGE_STAFF_CONDUCT_NOTES`, and today the Temple Admin
alone holds it, for reading as much as for writing. **The danger is the reading.** These are notes
about colleagues who work in the same room, and a permission that arrived with the roster or with
the hiring paperwork would mean the first thing that happens is a kitchen manager reading their
colleague's warning. There is direct precedent one step out: E6-S8 D9 split `MANAGE_STAFF` from
`MANAGE_STAFF_SCHEDULE` so that giving somebody the roster did not hand them everyone's date of
birth and PAN. **Folding conduct into `MANAGE_STAFF` was considered and rejected on exactly that
argument**, one step further in — somebody who may hire, pay and dismiss still cannot read these
unless they are given this permission separately.

**D3 — The dated note, and nothing else.** No rating, no severity, no category, no warning type, no
acknowledgement. Each of those is a structured judgement about a real person that some future screen
would sort, filter or total, and nobody has yet named the reader who would act on the total. A
rating nobody reads is a permanent judgement about somebody's character kept for no purpose, which
is the harm this table exists to avoid. **If an enum of note types ever appears here, this decision
is being reopened, and it should be reopened out loud** rather than in a migration.

**D4 — Deliberately disconnected from the employment ban (E9-S2, `V65`).** In both directions, and
it is a decision rather than an omission. A ban is raised at a dismissal, out of words an
administrator writes at that moment and signs their name to; it must not be assembled from remarks
other people wrote months earlier for another purpose. And nothing in the ban flow surfaces a
conduct note: a ban travels to another temple as the answer to one question at one hire, and
widening what travels — from one sentence an administrator stands behind to the internal remarks
file — would change what this platform publishes about a person. See `BACKLOG.md` **BL-6** for why
an accusation about a private individual is handled this carefully. There is no foreign key either
way and no column here a ban could read. Connecting them may one day be right; it would be its own
story.

**Requirements:**
- `V84__staff_conduct_notes.sql`: body, author, timestamp, and nothing else, with
  `enable_tenant_rls()` and `make_append_only()`, a non-blank check on the body and a 4,000-character
  ceiling so one note cannot become a filing cabinet.
- `Permission.MANAGE_STAFF_CONDUCT_NOTES`, held by Temple Admin alone in `RolePermissions`.
- `GET` and `POST /api/v1/staff/members/{id}/conduct-notes`, both behind that permission. There is no
  `PUT` and no `DELETE`: the table refuses both, so a route offering either would be a button that
  cannot work.
- The author is the signed-in user and the timestamp is the database's; neither is taken from the
  request, because a note whose author and date a caller could choose would prove nothing.
- `KMS-4012 CONDUCT_NOTE_EMPTY` for a note with nothing in it — the record is permanent, so an empty
  one would sit on somebody's file for good.
- Audit action `STAFF_CONDUCT_NOTE_ADDED`, recording **that** a note was written and by whom and
  never its words: the log is read behind `VIEW_AUDIT_LOG`, a differently drawn audience, and
  copying the text there would hand it over by the back door.
- A panel on `/staff/[id]` and `/staff/[id]/edit`, newest first, each note above its author and the
  moment it was written, saying before anything is typed that a note cannot be changed or removed.
  Anybody without the permission sees no panel and the screen asks the server for nothing.

**Acceptance criteria:**
- [x] A note comes back dated, attributed and newest first.
- [x] An empty or whitespace-only note is refused with `KMS-4012`.
- [x] `staff_profiles.notes` is left exactly as it was.
- [x] The table refuses UPDATE and DELETE through the application's own unprivileged role.
- [x] Append-only does not break the foreign keys that reference it.
- [x] A Kitchen Manager is refused both reading and writing; so is Kitchen Staff.
- [x] Notes appear nowhere in the roster or profile views that `MANAGE_STAFF` alone reaches.
- [x] Nothing structurally connects a conduct note to an employment ban, and raising a ban picks up no note.
- [x] The screen says a note cannot be changed before there is anything to press, and clears the box and re-reads the list after saving.
- [x] A staff member with no notes says so plainly.

