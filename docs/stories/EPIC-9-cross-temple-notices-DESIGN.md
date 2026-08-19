# EPIC 9 (draft) — Dismissal, ineligibility, and a notice that travels

**Status: DESIGN, NOT BUILT. Six questions at the top need Rajeev's answers before code.**

**Origin:** Rajeev, 2026-08-19: *"plan and implement the Employee Termination and marked as ineligible to rehire flow. In this flow the temple admin has to clearly document why they cannot be rehired and also draft a message that will be sent to all sites as a notice. So, you will have to design and build the multi site notification system too."* Backlog item **BL-6**, raised 2026-08-15.

I have not built this. Not because the engineering is hard — the notice board is a day's work — but because this is the only feature in the product that **deliberately crosses tenant isolation**, and it is the only one that can hurt somebody who has done nothing wrong. The six questions below change what gets built, not merely how.

---

## The six questions

### Q1 — Does the notice name the person? *(the one that matters)*

This is the fork the whole design turns on, and I want to argue for the answer that is not the obvious one.

**The obvious design:** the notice says *"Gopal Das, dismissed from ISKCON South Bengaluru for theft, not to be re-engaged"*, and every temple sees it.

**Why I'd argue against it.** That publishes an accusation about a named private individual to every organisation on the platform, permanently, on the say-so of one administrator, with no process behind it and no way for the person to answer. Three consequences:

- **It is a defamation exposure** for the temple that raises it and for us as the publisher, if it is ever wrong or overstated.
- **Under the DPDP Act** it is personal data processed for a purpose the person never consented to, disclosed to parties with no relationship to them.
- **It gets it wrong at scale.** BL-6's own note says the failure mode to design against is *"a confident false positive against a devotee who has done nothing, in a community whose stated posture is to welcome everyone."* A name on a list, seen by two hundred temples, is that failure mode with an amplifier attached.

**What I'd build instead — and it does the job better.** Split the two things that are currently one:

1. **The notice**, which travels to every temple, describes the *situation* and names the *temple*, not the person: *"ISKCON South Bengaluru has recorded a dismissal for misappropriation of kitchen funds and marked the person ineligible for re-engagement."* That tells the community what it needs to be alert to.
2. **The identity**, which does not travel at all. It is checked **at the moment a temple is actually hiring** — the hire form asks the platform "is this person flagged anywhere?" and shows the answer to that one admin, who has a legitimate reason to ask, at the moment they need it.

That inverts the flow: instead of pushing one person's name to two hundred temples who will never meet them, it lets one temple ask one question when it matters. Same protection, a fraction of the exposure.

**And there is a match signal that needs nothing to be broadcast at all.** Staff PANs are already stored encrypted with a *keyed fingerprint* — a blind index (`PanCipher.fingerprint`, built for E7-S7). The same PAN produces the same fingerprint in every temple, and the fingerprint reveals nothing about the PAN. So a temple hiring someone can be told **"this person is flagged elsewhere"** with certainty, without any temple learning anything about any other. Name matching stays fuzzy and advisory on top of that.

**My recommendation: the notice describes the conduct; the name never leaves the temple that recorded it; matching happens at hire, on the PAN fingerprint first and names second.** But this is your call and your community — say the word and I will build the named version instead.

### Q2 — Who may raise a notice that every temple sees?

Any temple admin, immediately? Or does a platform operator review it first?

**My recommendation: any temple admin, immediately — but never anonymously and always withdrawable.** Every notice carries the raising temple's name in the open, the raiser is on the platform audit log, and it can be withdrawn (which travels the same rails, so temples see the retraction). Operator pre-moderation sounds safer and is not: it puts a person between an urgent warning and the temples who need it, and there is no operator on duty at 9pm on a Sunday.

### Q3 — Does the person get told what was recorded about them?

Nothing in this product currently tells anybody what has been written about them.

**My recommendation: yes.** Someone marked ineligible for re-engagement sees, on their own account page, that their temple recorded it and the reason given. It is uncomfortable, and that is the point: a flag the subject can read is a flag an administrator thinks about before setting. It is also what the DPDP Act's access right asks for.

### Q4 — How long does a notice live?

**My recommendation: 30 days on the Today screen, forever on a Notices page.** A notice that never leaves Today becomes wallpaper and stops being read; one that vanishes entirely cannot be found again when it matters.

### Q5 — Every temple, or nearby temples?

**My recommendation: every temple.** Somebody dismissed in Bengaluru turning up in Mayapur is precisely the case BL-6 exists for, and a radius would exclude it.

### Q6 — Where do received notices live in the audit trail? *(you asked this directly)*

You asked whether every notice a site receives should go into its audit log. **My recommendation: no — and the reason is a rule we already set.**

The temple audit log records *what this temple did*. A notice arriving is not an act of theirs. Copying every platform notice into every temple's audit log would put the same row into two hundred logs, and it is the cross-tenant firehose we deliberately ruled out when we decided operators read audit per-tenant and never as a feed.

Instead:
- **The Notices page is itself the permanent record** — append-only, every notice ever received, dismissed or not, searchable. That is where an admin goes back to look.
- **Dismissing** a notice *is* an act of that temple, so that goes in their audit log.
- **Raising** one goes in the raising temple's audit log *and* the platform audit log (E1-S14), which is the only place a cross-tenant act belongs.

---

## The design, assuming the recommendations above

### E9-S1 — A notice board that spans the platform

The carrier, built first and on its own, because BL-6 is right that barring someone is only its first use: a Janmashtami advisory, a food-safety recall or a platform outage would all ride the same rails, and a carrier built for one message is a carrier rebuilt for the second.

- `platform_notices` — **not tenant-owned**, sitting beside `platform_audit_events`: id, raised_by_tenant, raised_by_user, kind, headline, body, severity, created_at, expires_at, withdrawn_at, withdrawn_reason.
- **RLS by role, not by tenant** — the same policy shape as `platform_audit_events`: readable when the connection's verified identity resolves to a `TEMPLE_ADMIN` or `SUPER_ADMIN`. A kitchen-staff account sees nothing; an unauthenticated one matches nothing.
- `platform_notice_dismissals` — tenant-owned, ordinary RLS. Dismissing is per temple, not per person: one admin dismissing it means the temple has seen it.
- **Today**, for admins only: undismissed notices inside their window, above everything else, styled by severity. Dismiss removes it from Today and never deletes it.
- **/notices**: every notice ever received, dismissed ones included, with who raised it and when.
- New permission `VIEW_PLATFORM_NOTICES` (every temple admin) and `RAISE_PLATFORM_NOTICE`.

### E9-S2 — Dismissal, and the record of why somebody should not be re-engaged

Extends E6-S8's end-employment flow, which already distinguishes a dismissal from a resignation.

- On `TERMINATED` only: `not_eligible_for_rehire` and a **required** `ineligibility_reason` — your *"clearly document why"*. A flag with no reason is refused.
- The reason is the temple's own record. Under Q1's recommendation it does not travel; it is what the temple sees if that person ever applies again, and what the person themselves can read (Q3).
- Setting it offers — and does not require — raising a notice (E9-S1), with the temple drafting the words. Two acts, because a dismissal is not always something other temples need to hear about.

### E9-S3 — Asking, at the moment of hiring, whether this person is flagged anywhere

The half that actually protects a temple, and the half BL-6 calls *"the hard half, and it is probabilistic."*

- On the hire form: before the record is created, the platform is asked whether this candidate is flagged.
- **Exact, on the PAN fingerprint.** A blind index that is identical across temples and reveals nothing. If it matches, the answer is certain.
- **Advisory, on name / phone / email**, scored, and shown as *a question, not a verdict* — every candidate listed, the decision the admin's, the outcome recorded either way. Above 50%, per BL-6.
- What comes back is deliberately thin: *that* a flag exists, when, and the conduct described — **never which temple, never their record**. A temple learns what it needs to ask the candidate about, and nothing about another temple's affairs.
- **Every ask is recorded**, on the platform audit log, because a query that crosses the boundary is itself a cross-tenant act.
- The admin's decision is recorded either way. *Hired anyway* is a legitimate answer and often the right one.

### What is missing until BL-5 exists

BL-5's photograph is the only durable signal against somebody who changes their name, email and number — which is exactly what somebody evading this would do. Without it, E9-S3 catches the careless and not the determined. Worth knowing before deciding how much weight this carries.

---

## What I would build, in what order

1. **E9-S1**, the notice board, on its own. It is useful the day it lands, independent of any of the above, and every question except Q2/Q4/Q6 leaves it unchanged.
2. **E9-S2**, the dismissal record. Small, and entirely inside one temple.
3. **E9-S3**, matching at hire — last, because it is the piece the answer to Q1 rewrites.

Say the word on the six and I will build it. If you would rather talk it through, Q1 and Q3 are the two worth talking about; the rest I am confident in.
