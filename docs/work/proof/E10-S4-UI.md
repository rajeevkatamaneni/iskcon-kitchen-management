# E10-S4-UI (T18) — the draft card's Confirm and Reject, wired

Worktree `/Users/Rajeev/Workspace/kms-e10-s3-perms`, branch `e10-s4-ui` (from `agentic-mvp` dfbb32bd). Not committed.

**Files changed** (all inside the contract):

- `frontend/lib/api.ts`, assistant section only: `AssistantProposal.result: {label, href} | null`;
  `AssistantMessage.proposals?: AssistantProposal[]`; `confirmAssistantAction(actionId, token)` and
  `rejectAssistantAction(actionId, token)`, both `POST /api/v1/assistant/actions/{id}/confirm|reject` returning `AssistantProposal`.
- `frontend/components/assistant/useAssistantConversation.ts`: `decide(entryId, actionId, "confirm"|"reject")`;
  draft entries carry `refusal`; reload builds cards from each reply's `proposals`.
- `frontend/components/assistant/AssistantParts.tsx`: the card wired, its after-states, refusals, expiry by clock, empty-state copy.
- `frontend/__tests__/assistant-drafts.test.tsx` (new): 11 tests.
- `docs/work/proof/E10-S4-UI.md` (this file).

Outside the contract, not tracked by git (`frontend/app/dev-*/` is ignored): I extended T13's local harness
`frontend/app/dev-assistant-panel/page.tsx` with stub confirm/reject/conversation endpoints so the after-states
could be measured, and put the screenshots in `frontend/app/dev-assistant-panel/shots/s4/`.

**How the card behaves**

- PROPOSED: Reject and Confirm enabled. A press makes that button busy (`aria-busy`) and disables both. A ref drops
  any second press before React re-renders, so a double-click sends one request (negative control below).
- On success the card re-renders from the proposal the server returns. CONFIRMED shows the mock's words with the
  result: "Saved as draft PO-0047. Not sent to Sri Balaji Traders yet. Open PO-0047" (the vendor comes from the
  fact labelled "Vendor"; a meal draft says "Added to the planner. Open …"). The link is `next/link`, so it
  navigates in-app and the panel, mounted in the root layout, stays open. REJECTED and EXPIRED use the mock's sentences.
- EXPIRED is shown from `status`, or when a PROPOSED draft's `expiresAt` has passed. A timer flips it while it is on screen.
- Refusals show inside the card with the server's message, next step and code, laid out like the conversation's error box:
  - KMS-400194: amber notice, and the card turns Expired with no buttons (the plain "expired" sentence is not repeated).
  - KMS-400195: amber notice, buttons removed (it would be refused again). Badge stays "Not saved yet".
  - KMS-400196: neutral info notice, and the card is read back from `GET conversation` to show what happened (e.g. Saved, with the link).
  - Anything else (unreachable, permission): red notice, buttons stay so it can be pressed again; the next press clears the notice.
  - Tones follow the colour rule: amber = they must ask again; info = nothing to do; red = failure. This is my judgement, not in the mock.
- Reload: cards come back after their reply's words, each with the server's current status.
- Idempotent second Confirm (contract): the backend returns 200 with the same CONFIRMED proposal, so the card just shows confirmed. Nothing special is needed in the UI.

**Deviations from the mock (Rajeev should see):**

1. The empty state now says "Ask about stock or orders" / "The assistant can check stock and draft a purchase order. It asks before it saves anything." The suggestions are "How much ತೊಗರಿ ಬೇಳೆ do we have?" and "Make a purchase order for toor dal". The mock said "…for Sri Balaji Traders". I changed it because a sample vendor name is not a vendor in a real temple. The meal suggestion is still left out, because meal drafts aren't built.
2. The "Not saved yet" badge wrapped onto two lines (measured 97×43 at 1280, 80×43 at 375; the problem came from T13's card). It is now held on one line (99×24 at both widths), and the title wraps instead.

**Commands run and what came back** (run directly in the worktree, which has its own `node_modules`, `.next` and `build`, as T13 did)

```
$ npx tsc --noEmit; echo "tsc exit $?"
tsc exit 0

$ npm test; echo "npm test exit $?"            (final run, after the badge fix)
 Test Files  194 passed (194)
      Tests  2828 passed (2828)
npm test exit 0

$ npx next build; echo "next build exit $?"     (after the badge fix)
○  (Static)   prerendered as static content
ƒ  (Dynamic)  server-rendered on demand
next build exit 0

$ npx vitest run __tests__/assistant-drafts.test.tsx __tests__/assistant-panel.test.tsx __tests__/design-system.test.ts
 Test Files  3 passed (3)
      Tests  45 passed (45)
```

Negative control: I removed the ref guard (`if (!onDecide || inFlight.current) return;` → `if (!onDecide) return;`):
```
   × Confirm > a double-click sends one request
     → expected [ { …(2) }, { …(2) }, { …(2) } ] to have a length of 1 but got 3
      Tests  1 failed | 10 skipped (11)
```
Then I restored it.

**Tests in `assistant-drafts.test.tsx`:** confirm (one POST to `/actions/p1/confirm` with the Bearer token, busy and both disabled while it is in flight, then Saved with the link `/orders/o47`); double-click sends one request; reject; 400194; 400195; 400196 (re-reads the conversation and shows the link); unreachable keeps the buttons and a retry clears the notice; expired by past `expiresAt`; flips to Expired on screen (fake timers); reload restores two cards with their own statuses and in order; empty-state suggestions.

**Measurements.** Production build (`next start -p 3014`), headless Chrome on its own port. Widths were set by CDP `Emulation.setDeviceMetricsOverride` (375×812 mobile, 1280×900). Rajeev's Chrome was not touched. DOM geometry is left,top,width,height. "past edge" counts elements in the card whose right edge is past the viewport. In every state, at both widths, past edge = 0 and nothing overflows the card. The only scroll mismatch is `THEAD 83/1` at 375, which is the phone card layout hiding the header on purpose.

375 (card 17..358):
- proposed: sentence `34,621,307,24`; Reject `157,657,82,44`; Confirm `246,657,95,44`; badge `242,…,99,24`
- pressing: Confirm `disabled busy=true`, Reject `disabled` (Reject pressed: the reverse)
- confirmed: line `34,653,307,48` (2 lines); link "Open PO-0047" `117,680,97,17`
- rejected: `34,677,307,24` (1 line). Expired (clock): `34,653,307,48`
- 400194: notice `34,554,307,148`, code row `50,670,275,20`. 400195: notice `34,554,307,148`, no buttons
- 400196: notice `34,518,307,124`, then confirmed line `34,653,307,48` with the link

1280 (panel 832..1280, card 849..1263):
- proposed: sentence `866,741,180,48`; Reject `1062,743,82,44`; Confirm `1151,743,95,44`; Confirm's right edge is 1246, the same as the footer's
- confirmed: line `866,741,380,48`, link `895,768,97,17`. Rejected: `866,765,380,24`. Expired (clock): `866,741,380,48`
- 400194: notice `866,665,380,124`. 400195: notice `866,641,380,148`. 400196: notice `866,629,380,100`, then the confirmed line

**Acceptance criteria**
- Type changes and confirm/reject functions: in api.ts; tsc is clean.
- Busy state with no double submit: test plus negative control; measured during a press at both widths.
- Re-render from the returned proposal (CONFIRMED with the link, REJECTED): tests, and measured.
- EXPIRED from status or `expiresAt`: tests (static, and flipping on screen); measured.
- Refusals 400194/5/6 with code, text and next step; 400196 refreshes: tests; measured.
- Confirmed link navigates in-app with the panel open: it is a `next/link` inside the panel that the root layout keeps mounted. **Not verified by clicking it in a browser.** The harness's stub sign-in lives on the harness page only, so leaving that page loses the panel there.
- Reload restores the cards: test.
- Empty-state suggestions updated: test.
- tsc, npm test and next build pass: output above.

**Not done / needs Rajeev**
- No end-to-end run against the real backend (S4-API is being built in parallel). My refusal texts are stand-ins; the card shows whatever the server sends.
- Not smoke-tested by hand in a signed-in app or on a phone. It was driven only through the harness, in headless Chrome.
- After a 400195 refusal the badge still says "Not saved yet" (amber). That is true, and the notice says to ask again. Say if you'd rather it read differently.
- The two deviations above.
