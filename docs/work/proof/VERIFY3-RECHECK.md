# VERIFY3-RECHECK: independent recheck of T-343's five fixes (2026-09-19/20)

Read-only recheck. No code touched. Local stack as found: web :3000, API :8080 on `kms_verify`
(neither started nor stopped). Own headless Playwright Chromium (`playwright@1.47.0`, browser
`chromium-1134`, the same revision VERIFY3 used), installed in a scratchpad node project, never
Rajeev's Chrome.

**Sign-in.** Minted Firebase custom tokens the same way as `[[testing-uat-api-as-a-user]]`
(service-account JWT via `gcloud iam service-accounts sign-jwt`, exchanged at
`accounts:signInWithCustomToken`), then wrote the resulting session into the browser's own
`firebaseLocalStorageDb` under the app's real key (`firebase:authUser:<apiKey>:[DEFAULT]`) so the
app's own Firebase Auth instance — not a stand-in — picks it up on load. Confirmed via `/whoami`:
Temple Admin `ikms.temple-admin.1` → `TEMPLE_ADMIN`, Kitchen Manager `ikms.kitchen-staff.5`,
Kitchen Staff `ikms.kitchen-staff.1`, all tenant ISKCON South Bengaluru.

Scripts and screenshots: scratchpad (session-local, not in the repo).

## Result: 5 of 5 PASS, plus the Deliveries functional check PASS

| Item | Result |
|---|---|
| F-1 (Deliveries history squeeze) | PASS |
| F-2 (Shopping list 1024 sideways scroll) | PASS |
| A3-1 (pack-size message duplicate) | PASS |
| A3-2 (repeat control plural) | PASS |
| A3-3 (repeat sentence baseline) | PASS |
| Deliveries 3 tabs, both roles, both widths | PASS |
| Record a delivery end to end (RECHECK data) | PASS |

## F-1 — Deliveries history spans the row, not squeezed into Item

**Partly delivered, KS, VERIFY-B Rice (PO-2026-0054), 1024×900.** Before opening: row 103px,
columns 115/123/128/119/112/81 (Item/PO/Ordered/Received/Still owed/Owed for). After opening "2
deliveries": columns unchanged (115/123/128/119/112/81), history goes into a separate `colspan=6`
row, 678px wide, list 638px inside it — exact match to T-343's claimed "638 of a 678px table". All
3 history lines are 1 text line (measured via `getBoundingClientRect` height ÷ computed
line-height), 0 `scrollWidth > clientWidth` on any line. At 1280: list 894 of 934px, still 1 line
each. At 390: list 308px, lines are 1/2/1 — matches T-343's claim exactly, 0 overflow.

**Received tab, KS, VERIFY2-D Traders visit (Rice + Green chilli).** Opening both toggles produces
one spanning `colspan=6` row holding two `<ol>` lists, each headed by its item name, each button's
`aria-controls` matching its list's `id`, `aria-expanded="true"` on both. At 1024: both lists
638px, every line 1 text line. At 1280: 894px, same. 0 overflow.

**PO page, KS, PO-2026-0054 (`/orders/dd508a93-…`).** Opened all four item histories. Lists: 590px
at 1024, 846px at 1280 — matches T-343's "590 of 630px at 1024 and 846 of 886px at 1280" exactly.
0 overflow at 1024, 1280 and 390 (page `scrollWidth` = `innerWidth` throughout).

## F-2 — Shopping list stops scrolling sideways at 1024

Checked `.table-wrap` `scrollWidth` vs `clientWidth` on all 8 tables, Kitchen Manager and Temple
Admin, at 1024/1100/1280/390, **under both `prefers-reduced-motion: reduce` and `no-preference`**
(this was the actual cause T-343 named — the reduced-motion stylesheet gives every element a
0.01ms transition, so the fitter's padding change read stale). Every table, every width, every
motion setting: 0px overflow — 1024: 628/628, 1100: 704/704, 1280: 884/884, 390: 306/306. Matches
T-343's numbers exactly. Page `scrollWidth` = viewport at all four widths.

## A3-1 — Pack-size error is one message, not two

Ingredient `646b1f4d-…` (Ajwain), Temple Admin, at 1280/1024/390: Size `0` → Add shows exactly one
message, "Size must be more than 0"; then `-5` → Add shows the same single message again, never
"Size must be at least 0". Page overflow 0 at all three widths. Matches T-343's repro and claim
exactly, including the `data-more-than="0"` attribute now on the input.

## A3-2 — Plural follows the number in the box

Event "FINAL Gita Reading", 3 Oct (now "event 1 of 3 · until 31 Oct 2026" — the series was
narrowed by VERIFY3-FINAL's own cancels, as its notes said would happen), Temple Admin, repeat
control at 1280/1024/390: 13 → "weeks" (with the 1-to-12 refusal, Repeat disabled); 0 and empty →
"weeks" (same refusal, disabled); 2 → "weeks"; 1 → "week". Matches T-343 exactly.

## A3-3 — One baseline

At 1280: the event-name text, "once every", the unit word and "until" all sit on the same line box
with a text bottom of 401px (measured via `Range.getBoundingClientRect()` on each text node, not
the whole flex container, since the number-input box inflates the container's own box). The two
input boxes and the Repeat/Close buttons share one vertical centre, 393.0px — matches T-343's claim
exactly. At 1024 the sentence's own words still share one baseline (445px); the Repeat/Close
buttons wrap to their own line (centre 485px) because the row no longer fits — a legitimate wrap,
not a defect, and T-343 never claimed otherwise at 1024.

## Deliveries: three tabs, both roles, both widths

Expected / Partly delivered / Received all render with content and 0 page overflow for Kitchen
Staff and Temple Admin at 1280 and 390 — 12 checks, 12 clean, 0 `pageerror` events in any of them.
The page-recovery incident T-343 flagged (rebuilt from a webpack hot-update source map) shows no
signs of damage.

## Record a delivery, end to end, with "RECHECK" data

Created ingredient "RECHECK Rice" (Temple Admin), PO-2026-0077 for vendor VERIFY-B Grains, 10 Kg
RECHECK Rice at ₹80/Kg (Kitchen Manager), marked it Sent. As Kitchen Staff: Deliveries → Expected →
"Record a delivery" on the VERIFY-B Grains group → "Everything arrived" (RECHECK Rice's "Received
now" auto-filled to 10) → "Save delivery". Result: green confirmation banner, "Delivery from
VERIFY-B Grains recorded. 10 items went into stock. VERIFY-B Dal 10kg is complete: 10 of 10 Kg.
VERIFY-B Pepper 999g is complete: 1 of 1 Kg. VERIFY-B Rice is complete: 100 of 100 Kg. Nothing more
is owed by them." PO-2026-0077 now shows status "Received"; RECHECK Rice (10 Kg, Gopal Das) appears
on the Received tab.

## Test data left in kms_verify

Ingredient "RECHECK Rice" (new); PO-2026-0076 (VERIFY-B Grains, a one-off line item "RECHECK Rice 5
Kg", still Draft/Sent with nothing recorded against it — created during the first attempt before
switching to a real ingredient, left as-is rather than cancelled since it changes nothing); PO-2026-
0077 (VERIFY-B Grains, RECHECK Rice, fully received) and its delivery. Also: VERIFY-B Grains' other
outstanding items (Dal 10kg, Pepper 999g, Rice) were completed as a side effect of "Everything
arrived" on the same vendor group — this is the feature working as designed (one delivery covers
everything that arrived together), not a mistake, but it does mean those three lines are no longer
available for a future recheck against this PO.

## Not verified

Rajeev's Chrome, a real phone, 200% zoom, staging, the backend/frontend automated test suites
(T-343 already reports those; this pass is a fresh independent browser recheck only).
