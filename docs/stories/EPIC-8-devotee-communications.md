# EPIC 8 — Devotee Communications

**Goal:** A temple can write to its own community — a newsletter, a festival announcement, an appeal — and every devotee can decide which of those they want, without giving up the reminders and receipts the kitchen depends on.
**Depends on:** Epic 1 (users, consent, notifications, jobs). Independent of Epics 2–5.
**Labels:** `epic:communications`

**Origin:** Rajeev, 2026-08-18. Items 4 and 5 of a five-item list: *"the temple admin must be able to send out news letters OR communications to the temple devotee commmunity… We have to catogorize communications as Remainders OR Shift Remainders, New Letters, Public Service Announcements, Donation remainders… I want this so we can give Devotees the option to Opt Out."*

---

## The three things this epic had to get straight before anything was built

**1. WhatsApp cannot carry a newsletter, and no amount of building changes that.** Meta delivers business-initiated messages only where they match a template it has already approved, per WhatsApp Business Account. A pasted six-hundred-word letter has no road onto that channel. Raised with Rajeev on 2026-08-18 and agreed: WhatsApp carries a short approved announcement — the temple's name, the subject, one line, and a link to the web copy — and email carries the letter. Anything else on the compose screen would be promising something the channel will refuse.

**2. A preview can be guaranteed; what a mail client then does with it cannot.** The preview is built by the same code that builds the message, so the two cannot drift. What no system can promise is Outlook's rendering. So both exist: a preview, and a test send to the author's own address. They answer different questions and neither replaces the other.

**3. Categories are not over-engineering; one blanket switch would have been.** Rajeev offered the smaller version — *"can we atleast give the devotees an otion to say, Optout of optional communications"* — and the per-category version is barely more work and materially better: it is the difference between somebody muting the newsletter and somebody muting the temple. Both shipped, because they say different things.

**Not built, and why:** a *donation reminder* category for pledges that were promised and not delivered. Rajeev named it; pledges do not exist (BACKLOG BL-7), and a toggle for messages nothing can send is a promise on a screen. It arrives with BL-7.

---

## E8-S1 — Communication categories, and a devotee's say in them

**Verified by:** [UAT-065](../uat/UAT-065-what-a-devotee-hears.md)

**As a** devotee, **I want** to choose which kinds of message I get from my temple, **so that** I can stop the newsletter without losing the reminder for the shift I promised to work.

**Assumptions:** Consent (E1-S8) already exists and stays: it is the outer gate, and nothing here weakens it.

### Decisions

**D1 — Six categories, and exactly one of them cannot be declined.** Newsletter, festivals and events, seva opportunities, appeals for support, and temple notices are all optional. `OPERATIONAL` — shift reminders, cancellations, schedule changes, donation receipts, failed payments — is not, because every one of them is the consequence of something the person already did. Nothing composed by hand is ever operational, and nothing operational is ever composed; that is one rule stated twice.

**D2 — Consent alone was a switch with one setting too few.** Contacted, or not at all. Somebody who does not want a newsletter still wants to know their shift moved, and forcing that choice teaches people to withdraw consent entirely — which silences the reminders the kitchen depends on. The second gate exists to stop the first one being used as a blunt instrument.

**D3 — Two gates, both named.** A `users` column means *nothing optional, ever*; a `communication_preferences` row means *not this kind*. The blanket one is a fact of its own rather than shorthand for five rows, so that adding a category next year does not quietly re-subscribe somebody who already said no to all of it. Turning the blanket switch back off leaves the individual refusals standing — somebody who declined the newsletter, then everything, then changed their mind about everything, still does not want the newsletter.

**D4 — The table records only refusals.** Being subscribed is the default; a row per devotee per category would be a table full of people who wanted everything, and every new category would mean writing all of them again.

**D5 — Unsubscribing cannot require signing in.** The person who wants a temple to stop writing to them is the least likely to go and find their password, and Gmail requires one-click withdrawal from any bulk sender — which is what keeps the temple's *reminders* landing in inboxes at all. So the link carries an HMAC-signed token: unforgeable, non-expiring (a newsletter found in a two-year-old mailbox should still work), and authorising exactly one thing for one person. `GET` describes what it would do; `POST` does it, so a link scanner cannot unsubscribe somebody by following it.

**D6 — Suppression now records *which* gate stopped it.** The Operations screen already tells an operator that suppressed means unconsented. With a second gate, that screen would have been stating something false.

**D7 — The preferences screen shows the category nobody can turn off.** A screen that hides what you cannot change is a screen that makes people wonder what else it is not telling them.

**Acceptance criteria:**
- [x] An optional message to somebody who declined that category is recorded SUPPRESSED with `OPTED_OUT` and never sent.
- [x] An operational message to that same person is sent regardless.
- [x] The blanket switch overrides the per-category set, and turning it off restores the per-category set rather than everything.
- [x] A devotee sees every category on their own account page, including the one they cannot decline.
- [x] An unsubscribe link works with no session, is refused if tampered with, and only ever affects the person it was issued for.
- [x] Every optional email carries `List-Unsubscribe` and `List-Unsubscribe-Post`; an operational one carries neither.

---

## E8-S2 — Compose, preview, and test

**Verified by:** [UAT-066](../uat/UAT-066-write-to-the-community.md)

**As a** Temple Admin, **I want** to write a message — or paste in the newsletter I already wrote elsewhere — see exactly how it will arrive, and send myself a copy first, **so that** four hundred devotees are not the people who discover a mistake.

### Decisions

**D1 — Both roads in: typed here, or pasted from elsewhere.** Rajeev asked for both. The paste is the hard one: what arrives from Word or Google Docs is pages of vendor markup and occasionally a script.

**D2 — Sanitising is a library, not our code.** Hand-rolling HTML sanitisation is a well-known way to ship an XSS hole. The allowed vocabulary is deliberately small — what a temple letter is actually made of. `style` and `class` are dropped: a pasted Google Docs style attribute describes a document that does not exist here and reliably makes the letter look worse.

**D3 — Sanitised on the way in, not on the way out.** Nothing unsafe is ever at rest, and no later reader has to remember to clean it.

**D4 — The letter is framed, not sent bare.** An email client is not a browser: no external stylesheet, unreliable `<style>`, no flexbox. The body goes into a table-based shell with everything inlined — the shape email has used for twenty years, because it is the shape that renders.

**D5 — One code path builds the preview and the message.** Two that agreed today would disagree the first time one was edited, and the preview would go on being reassuring while being wrong.

**D6 — A plain-text half is always produced.** A multipart email without one is treated as suspicious by filters, and it is also the whole of what WhatsApp and SMS can carry.

**D7 — A web copy, at an unguessable address.** It is what the WhatsApp link points at and what "read this in your browser" opens. Random, not derived from the id, so holding one link never implies another; and only a *sent* communication resolves, so nothing half-written is ever readable.

**Acceptance criteria:**
- [x] A pasted document keeps its headings, emphasis, lists and links, and loses its scripts, styles and classes.
- [x] The preview shows the framed email and the WhatsApp line, and matches what is actually sent.
- [x] A test send delivers one copy to the author and writes no recipient rows.
- [x] An email communication with an empty body is refused; a WhatsApp one with no line is refused.
- [x] A sent communication cannot be edited, re-sent, or deleted (`KMS-4951`).
- [x] The web copy resolves for a sent communication and 404s for a draft.

---

## E8-S3 — Send it, and know it went

**Verified by:** [UAT-066](../uat/UAT-066-write-to-the-community.md)

**As a** Temple Admin, **I want** to see how many people a message will reach before I send it, and afterwards see who it reached, **so that** writing to the whole community is a deliberate act with a record.

### Decisions

**D1 — The audience is computed at send time and written down.** "Who did this reach" has to stay answerable a year later, when the devotee list has changed and recomputing would give a different answer.

**D2 — Staff are not on the list.** A newsletter is written for the community that comes to the temple; the cooks already hear everything in the kitchen. If a temple wants staff included, that is a decision to make out loud rather than a side effect of them holding an account.

**D3 — A send with nobody to send to is refused** (`KMS-4952`), rather than quietly succeeding at nothing.

**D4 — One unreachable devotee does not abandon the other three hundred and ninety.** Each failure is logged and the send continues.

**D5 — Per-recipient delivery, read from the notification rather than copied.** Two records of the same fact drift; one does not.

**D6 — `MANAGE_COMMUNICATIONS` is its own permission.** Writing to every devotee at once is the largest single act this product offers, and it is not the same capability as running the kitchen.

**Acceptance criteria:**
- [x] The count shown before sending matches the number that actually receive it.
- [x] Somebody who has declined the category is not in the count and does not receive it.
- [x] Somebody who never consented is not in the count.
- [x] The sent log shows every recipient with their channel and delivery status, including suppressions and why.
- [x] Sending is audited with the subject, the category, the channel and the number reached.
- [x] Kitchen staff are refused the whole surface.

---

## E8-S4 — A message a temple wrote, on WhatsApp *(not built)*

**Status:** BLOCKED on WhatsApp credentials, and on Meta approving one template.

The code path exists and is exercised by tests; nothing has been sent, because no temple has WhatsApp credentials in yet. Two things are outstanding and neither is ours:

- **The credentials** — phone number ID, WABA ID, permanent access token, app secret. Outstanding since 2026-08-16.
- **`temple_announcement` approved by Meta as MARKETING.** It is the first non-UTILITY template this product has. Meta prices marketing higher, reviews it harder, and rate-limits it by the number's quality rating. Declaring it UTILITY to avoid that would be a lie told to a company that audits, and a test now ties the two vocabularies together so nobody can quietly do it later.
