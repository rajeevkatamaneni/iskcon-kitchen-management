# EPIC 6 — Workforce Management

**Goal:** Staff scheduling, volunteer shift posting with per-event reminder configuration, signup/release with waitlist auto-promotion, WhatsApp-first reminders honoring per-user channel preference, and one-off broadcasts.
**Depends on:** Epic 1 (auth, roles, notifications, jobs). Independent of Epics 2–5.
**Labels:** `epic:workforce`

---

## E6-S1 — Staff profiles and weekly schedule

**Verified by:** [UAT-047](../uat/UAT-047-staff-schedule.md)

**As a** Temple Admin, **I want** to maintain full-time staff schedules, **so that** who works when is visible to everyone who needs it.

**Assumptions:** Simple recurring weekly pattern + per-date exceptions (day off, swapped shift) — no payroll, attendance, or leave-balance accounting in release 1 (prior proposal's staff attendance/leave module is Phase 2+). Staff = users with KITCHEN_STAFF role + a staff profile (designation, e.g. Head Cook / Prep — free text).

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
