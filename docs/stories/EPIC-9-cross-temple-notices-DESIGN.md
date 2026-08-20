# EPIC 9 — What crosses between temples

**Goal:** The two things in this product that deliberately leave one temple and reach another — a
notice board spanning the platform, and the record a temple raises at a dismissal that another
temple is told about at the moment it is hiring. Nothing else in the system crosses tenant
isolation, and nothing else should.
**Depends on:** Epic 1 (auth, roles, platform audit log), E6-S8 (the employment record a ban is
raised from).
**Labels:** `epic:cross-temple`

**Status: BUILT 2026-08-20.** This file was a design carrying six open questions. All six were
answered — §10 and §11 of `BUILD-BRIEF-2026-08-20.md` are where — and the answers changed the shape
of it, so the design is recorded below rather than deleted, and the two stories that were actually
built follow it.

*The filename still says DESIGN. It is left as it is because the build brief and BL-6 both name this
path, and a closed record should not acquire a broken link for the sake of tidiness.*

**Origin:** Rajeev, 2026-08-19: *"plan and implement the Employee Termination and marked as
ineligible to rehire flow. In this flow the temple admin has to clearly document why they cannot be
rehired and also draft a message that will be sent to all sites as a notice. So, you will have to
design and build the multi site notification system too."* Backlog item **BL-6**, raised 2026-08-15,
now closed.

---

## The six questions, and their answers

Kept because every one of them is a decision somebody may want to overturn, and because the argument
matters more than the conclusion.

**Q1 — Does the notice name the person? No, and the broadcast was dropped altogether.**
The design proposed splitting the two things: a notice describing the *situation* and naming the
*temple* rather than the person, plus a check at the moment of hiring. Rajeev's argument closed the
first half of that as well, and it is his that won:

> An unnamed notice — *"an employee has been blacklisted"* — is a rumour with no handle on it. It is
> useful to nobody, and corrosive anyway.

He is right. A notice nobody can act on is not a warning, it is an atmosphere; it invites every
temple to wonder who, which is precisely the harm the unnamed version was meant to avoid. And the
named version was never available: publishing an accusation about a private individual to every
organisation on the platform, permanently, on one administrator's say-so, with no process behind it
and no way for the person to answer, is a defamation exposure for the raising temple and for us as
the publisher, and it is personal data processed under the DPDP Act for a purpose the person never
consented to. **So the notice about a dismissal does not exist in either form.** The identity never
travels. It is *asked for*, once, by the one temple with a reason to ask, at the moment it is
hiring — E9-S2.

**Q2 — Who may raise a platform notice? Any temple admin, immediately.** Never anonymously, always
withdrawable. Operator pre-moderation sounds safer and is not: it puts a person between an urgent
warning and the temples who need it, and there is no operator on duty at nine on a Sunday evening.

**Q3 — Is the person told what was recorded about them? No — reversed from the design's
recommendation, on Rajeev's ground-truth argument.** The design argued yes, on the grounds that a
flag the subject can read is a flag an administrator thinks about before setting. His answer: they
lose access at termination anyway, and disclosure at the moment of firing invites retaliation, which
is a real risk in India borne by his people. The DPDP Act's right here is to information *on
request*, which a documented out-of-band process satisfies; it is not a duty of proactive
disclosure. The consequence is accepted explicitly and is written into E9-S2: because the subject is
no longer a check on a wrong entry, **retraction, the ten-year fade and naming the raising temple on
every finding carry the whole of the error correction between them.**

**Q4 — How long does a notice live? Thirty days on Today, permanently on the board.** Or until
dismissed, whichever comes first.

**Q5 — Every temple, or nearby temples? Every temple.** Somebody dismissed in Bengaluru turning up
in Mayapur is precisely the case this exists for, and a radius would exclude it.

**Q6 — Do received notices go in the receiving temple's audit log? No.** A temple's audit log
records what that temple did, and a notice arriving is not an act of theirs; copying every notice
into two hundred logs is the cross-tenant firehose we ruled out when we decided operators read audit
per-tenant. Instead: the Notices page is itself the permanent record, dismissing is an act of that
temple and is audited there, and raising one goes to the raising temple's log *and* to the platform
audit log, which is the only place a cross-tenant act belongs.

---

## E9-S1 — A notice board that spans the platform

**Status:** DONE 2026-08-20 (build brief §11). Built last in the build, because it depends on
nothing else in it.

**Verified by:** UAT to be written. Automated cover: `PlatformNoticeIT`,
`frontend/__tests__/notices.test.tsx`.

**As a** Temple Admin, **I want** a message that has to reach every temple to reach every temple,
**so that** a supplier recall found in my kitchen on a Sunday evening is read in Mayapur on Monday
morning.

**Assumptions:** The generic carrier BL-6 argued for, and deliberately decoupled from dismissals
entirely — barring somebody is not one of its uses at all any more (Q1). A food-safety recall, a
festival advisory and a platform outage all ride these rails.

### Decisions

**D1 — One row, read by everybody; no fan-out and no copy per temple.** `platform_notices` carries
no `tenant_id` and sits beside `platform_audit_events` as platform-scoped. Copying a row per temple
was the alternative and is worse in every direction: two hundred copies of one recall, two hundred
withdrawals to push when it is retracted, and a fan-out job standing between an urgent message and
the people who need it.

**D2 — Row-level security keyed on identity, not on tenant.** Readable by any connection whose
verified identity resolves to an *active* user — deliberately not narrowed to admins, because the
notices that matter most are about food and the cook is the person who needs to read them. Insert
admits a person only as themselves and only for their own temple, so forged attribution is
impossible underneath the permission check; the one shape an unauthenticated connection may write is
the notice attributed to no person and no temple, which is the automation case. Update is the
withdrawal rule expressed a second time. There is no delete policy at all: a notice is never
removed, only withdrawn.

**D3 — Dismissal is per person, not per temple.** Settled 2026-08-20, reversing the design. A temple
with three admins where the first clears a food-safety recall before the other two have read it is a
temple where two people never saw it; the cost of that beats the cost of a second admin clicking
dismiss, and most temples have one admin anyway. `platform_notice_dismissals` therefore carries a
policy *stricter* than tenant isolation — a person sees and writes their own rows and nobody else's.
It still carries `tenant_id`, because `delete_tenant_cascade` finds the tables it must purge by
looking for that column name, and it carries a second policy admitting that purge alone.

**D4 — Three severities, and only urgent is loud.** Information, important, urgent. A board where
everything shouts is a board nobody reads, and the one time it matters they will scroll past that
too. A withdrawn notice is drawn quiet whatever it was raised as — a retracted recall that kept
shouting would be worse than the recall.

**D5 — Off Today after thirty days or when dismissed, whichever comes first; permanent on the
board.** The window is measured from the withdrawal where there is one, so a retraction is news on
the day it happens whatever the age of the thing it retracts.

**D6 — No pre-moderation; withdrawal stands in for it.** Three things carry that: the raising
temple's name is on every notice in the open, the raiser is on the platform audit log, and it is
withdrawable — by the temple that raised it and by a platform operator, whose ability to take down
an abusive notice is what makes going without a reviewer defensible. A withdrawal travels the same
rails, so temples see the retraction rather than being left with the original, and a reason for it
is mandatory.

**D7 — Plain text, rendered as text.** No rich text and no HTML anywhere. This is the one payload in
the product that one temple writes and another temple's browser renders, so escaping is doing real
security work here rather than tidiness — and there is no formatting a recall needs that a line
break cannot give it.

**D8 — Attribution survives the temple.** `raised_by_label` is captured at write time, the way
`audit_events` captures `actor_label`, and the pointers are `ON DELETE SET NULL`. A notice outlives
the temple that raised it because every other temple was told, and their record of what they were
told must not depend on that temple still existing. The same reasoning forced
`platform_audit_events.actor_user_id` to become nullable: its actors used to be operators only, and
without that change the first temple to raise a notice would have become a temple that could never
be deleted.

**Requirements:**
- V66: `platform_notices`, `platform_notice_dismissals`, their policies, and the widened
  platform-audit insert policy — any verified active user may append, for a notice, attributed to
  themselves, and reading the platform log stays super-admin only. A temple can write to that log
  and can never read it, which is the correct asymmetry for a record kept to catch the writer.
- New permissions `RAISE_PLATFORM_NOTICE` (temple admin and operator) and
  `WITHDRAW_ANY_PLATFORM_NOTICE` (operator). Reading needs no permission — it is the RLS read policy,
  so a cook sees a recall.
- Raising: operators find it beside Operations, where a downtime notice is an operations act; temple
  admins find it under Temple, beside the audit log and settings, because raising one is rare and
  serious enough to sit somewhere deliberate rather than one click from the day's work.
- Receiving: undismissed notices inside their window, at the top of Today, above everything else.
- `/notices` is the permanent board — nothing filtered out, dismissed and withdrawn notices
  alongside the rest, because a temple that cleared a recall in March and needs it again in June has
  exactly one place to look.
- Subject 1–120 characters, body 1–4,000: long enough for a recall with batch numbers and a phone
  number, short enough that nobody posts a newsletter to two hundred temples.

**Acceptance criteria:**
- [x] A notice raised by one temple appears on every other temple's Today screen, with the raising temple named.
- [x] A kitchen-staff account sees notices; an unauthenticated request sees none.
- [x] Dismissing removes it from that person's Today and from nobody else's, and never deletes it.
- [x] Only urgent is visually loud; a withdrawn notice is quiet whatever it was raised as.
- [x] A notice leaves Today after thirty days and stays on `/notices` for ever.
- [x] The raising temple can withdraw its own; an operator can withdraw anyone's; a third temple is refused (`KMS-4308`).
- [x] Withdrawing twice is refused (`KMS-4966`); a withdrawal without a reason is refused.
- [x] A notice cannot be posted attributed to a temple the raiser does not belong to, even with a forged payload.
- [x] Raising one is recorded on the platform audit log.
- [x] Deleting a temple that raised a notice succeeds, and the notice keeps its attribution.

---

## E9-S2 — The record raised at a dismissal, and the check run at a hire

**Status:** DONE 2026-08-20 (B9, build brief §10). Replaces the design's E9-S2 and E9-S3, which are
one act rather than two once the broadcast is dropped.

**Verified by:** UAT to be written. Automated cover: `EmploymentBanIT`, `BanMatcherTest`,
`frontend/__tests__/staff-ban.test.tsx`.

**As a** Temple Admin, **I want** to be told, at the moment I am hiring somebody, that another
temple dismissed them for cause, **so that** I can telephone that temple and hear the other half of
the story before I decide.

**Assumptions:** This is the only feature in the product that deliberately crosses tenant isolation,
and the only one that can hurt somebody who has done nothing wrong. Nearly every decision below is a
constraint accepted on purpose rather than a capability.

### Decisions

**D1 — It flags. It never blocks.** A finding is handed to the hiring admin with the raising temple
named and their account quoted, and the admin decides. *Hired anyway* is recorded as the legitimate
answer it usually is. A hard block would move the judgement from the person in the room — who can
ring the other temple and ask — to a similarity threshold in `BanMatcher`, and a confident false
positive against a devotee who has done nothing is the exact failure this feature was designed
against.

**D2 — Queried at a hire and nowhere else.** There is no search endpoint and no browsable list, and
adding one would defeat the design however convenient it looked. Three things enforce that together:
the check runs *as part of* creating a staff record, so a query cannot exist without a hire attempt
behind it; a hire attempt leaves a staff record at the asking temple; and every query, including
every query that found nothing, lands on the platform audit log — which is exactly the query
somebody fishing would run. Re-hiring somebody is a new hire and is checked; correcting a phone
number on an existing record is not.

**D3 — The reason is a category and free text, and both are mandatory** (`KMS-4010`). The category
is what another temple can compare; the free text carries the account of what happened and is what
turns a finding into a telephone call. `BanCategory` deliberately has **no `OTHER`** — every other
controlled vocabulary in this product has one, and an `OTHER` bucket on a list whose entire purpose
is comparability becomes the list. The cost is accepted knowingly: an admin whose case sits between
two categories chooses the nearer and explains the rest in their own words.

**D4 — The subject is never shown any of it in the app** (Q3), and the consequence is the design.
Retraction, the ten-year fade and naming the raising temple carry the whole of the error correction,
so all three are on the screen in plain words and none of them is in small print. A retracted record
stays on file: erasing it would erase the evidence that a wrong entry was ever made, which is the
opposite of correcting one.

**D5 — The exact signal is the PAN fingerprint, and it reveals nothing.** `PanCipher.fingerprint` is
an HMAC over the PAN alone — no tenant, no per-tenant salt — so the same PAN produces the identical
value at every temple on the platform, and it is unusable to anyone without the key. A match on it
is a match on the person, and neither side learned anything to find that out. Phone number is
compared exactly too, and listed as exact *honestly rather than flatteringly*: numbers get
reassigned, so it is an exact comparison of a value that is not proof of identity.

**D6 — The fuzzy layer is trigram similarity over the normalised name and address, and it flags
rather than decides.** It exists for the case the whole feature is for — somebody dismissed in
Bengaluru does not arrive in Mayapur with the same phone number. `BanMatcher` is pure static
functions over values, no database and no Spring, so the rule can be read, argued with and tested on
its own. The one thing that must not happen to a matcher like this is for it to become folklore
nobody can check.

**D7 — Aadhaar is matched without ever storing the number.** The UIDAI-signed offline eKYC QR yields
the holder's name, date of birth and last four digits; that triple beats a typed number outright
because it cannot be fabricated. A CHECK constraint on four digits makes storing a full number here
structurally impossible rather than merely discouraged, and the triple matches as a triple or not at
all — two thirds of it is a false confidence, not a weaker signal. **The QR capture is not in this
build** (E6-S9 owns it); these columns are the seam it lands on, and the arm is inert until then.

**D8 — Bans fade at ten years.** `BAN_LIFETIME` in Java, and a parameter on the matching function
rather than a hardcoded interval, because the figure is provisional — Rajeev to confirm it with the
temple.

**D9 — The raising temple owns the record.** Only it may amend or retract; another temple is refused
with `KMS-4307` rather than a not-found, and the case that decides it is real: a hiring temple shown
a finding knows that record's id and may quite reasonably try to take it down. "Not found" would
leave them hunting a bug; naming the owner is the telephone call this design is trying to bring
about. The function behind that refusal returns the owning tenant's id and nothing else — no
category, no account, no name, no date.

**D10 — The record is raised at the dismissal, on that request, in that transaction.** It rides on
`EndEmploymentRequest` rather than being a call of its own: an employment ended without the record
the admin asked for is worse than neither, and a record that could be raised against anybody at any
time is a different and far more dangerous feature. The option is unticked by default and has to be
chosen deliberately — most dismissals raise nothing, and a screen that made this the easy path would
produce a list of hundreds of people whose worst day at work follows them round the country.

**D11 — Row-level security is enabled and deliberately not forced, and that is the mechanism.**
Every other tenant-owned table calls `enable_tenant_rls()`, which sets both. This one cannot: the
raising temple must read its own rows, and the hiring temple must be told about *one person* without
ever being able to read the list. So the policy confines the application role to its own temple's
rows, and `FORCE` is withheld so the table's owner stays exempt — because the owner is what the
`SECURITY DEFINER` matching function runs as. That function takes one person's identifying details
and there is no argument to it that returns the table: all-null arguments match nothing, an empty
token array overlaps nothing, and its widest arm is a *blocking key* whose rows are then scored in
Java, with only findings above the threshold ever shown to anybody. **The absence of `FORCE` here is
not an oversight. Read the header of V65 before changing anything in that file.**

**D12 — The match signals are copied onto the record when it is raised, not read through the staff
row.** A temple editing a former employee's phone number two years later must not silently rewrite
what a ban raised in 2026 was about, and the staff row may in any case be purged with its temple
while this record outlives it. `staff_profiles` gains its own PAN fingerprint so that raising a ban
never has to decrypt a PAN and quietly become a `STAFF_PAN_VIEWED` — filled forward on every hire
and edit, and computed lazily for one row on the day a ban is raised against a record that predates
V65.

**Requirements:**
- V65: `employment_bans` (raising temple, the employment it came from, category, mandatory account,
  the match signals, retraction), the `match_employment_bans` function, the narrow platform-audit
  insert escape for a temple admin's ban and ban-check events, and the hire-check columns on
  `staff_profiles`.
- One live record per person per raising temple (`KMS-4964`); a retracted one may be replaced and
  both stay on file. Retracting twice is refused (`KMS-4965`).
- The check runs inside the hire: findings come back instead of a staff record, and re-submitting
  the same hire with the check's id is the admin's recorded decision to proceed. A check that found
  nothing is recorded too.
- A finding shows the raising temple's name, what they wrote verbatim, when, which details matched,
  and whether any of them was exact.
- Everything behind `MANAGE_STAFF`. `/staff/bans` lists this temple's own records and nobody else's;
  there is no screen anywhere that shows another temple's, and no endpoint that would serve one.
- The findings the admin saw are frozen onto the hire, because the ban rows may be amended or
  retracted later by their owner and what that admin was looking at when they decided must not
  change under them.

**Acceptance criteria:**
- [x] A dismissal can raise a record; a resignation offers nothing; the option is never pre-ticked.
- [x] A record without both a category and free text is refused (`KMS-4010`).
- [x] Hiring somebody with a matching PAN fingerprint at another temple returns findings and creates no staff record.
- [x] Re-submitting the hire with the check id creates the record and files the decision as *proceeded*.
- [x] A check that finds nothing still lands on the platform audit log with an id.
- [x] A finding names the raising temple and quotes their account in full.
- [x] A fuzzy name or address match flags and never blocks; an exact match never blocks either.
- [x] No endpoint returns another temple's records, and no argument to the matcher returns the table.
- [x] A second live record for the same person at the same temple is refused (`KMS-4964`).
- [x] Another temple attempting to retract is refused (`KMS-4307`), and told whose record it is.
- [x] A retracted record stops appearing at a hire and stays on file.
- [x] A record older than the fade does not appear at a hire.
- [x] There is no subject-facing surface anywhere in the product.

---

## What is still missing

**BL-5's photograph.** It is the only durable signal against somebody who changes their name, email
and number — which is exactly what somebody evading this would do. Without it, E9-S2 catches the
careless and not the determined. Worth knowing before deciding how much weight this feature carries.

**E6-S9's Aadhaar QR.** Built as a seam and inert until the reader exists.

**The ten-year figure.** Rajeev to confirm with the temple.
