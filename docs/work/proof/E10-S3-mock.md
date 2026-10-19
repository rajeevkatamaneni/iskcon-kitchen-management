# E10-S3-mock — throwaway mock of the Kitchen Assistant chat, inside the real app

**Files changed:** (all inside the contract, `frontend/app/dev-assistant/`, plus this proof)

- `frontend/app/dev-assistant/page.tsx` (new; first line `// LOCAL PLAYGROUND — delete before any commit or deploy`)
- `frontend/app/dev-assistant/README.md` (new)
- `frontend/app/dev-assistant/shots/final/*.png` (8 screenshots) and `shots/*.png` (low-resolution earlier captures, safe to delete; I could not delete them, the rm was blocked by a safety check)
- `docs/work/proof/E10-S3-mock.md` (this file)

No shared component, token, stylesheet, nav, routes or api file was touched. `git check-ignore` confirms the folder is excluded:
`.gitignore:144:frontend/app/dev-*/	frontend/app/dev-assistant/page.tsx`

**Commands run and what came back:**

```
$ tools/work-lock.sh run verify 'cd frontend && npx tsc --noEmit; echo "tsc exit $?"'
tsc exit 0
work-lock: 'verify' released; command exited 0
```

The dev server was run from a copy of `frontend/` in the session scratchpad (node_modules symlinked), port 3011, so it never wrote the shared `frontend/.next/`. The page file was copied across after each edit and is byte-identical to the checkout's (`diff -q` → same).

**Measurements** (DOM geometry, page loaded in a same-origin iframe of exactly the stated size; the Chrome window could not be made taller than 401px, so the iframe was scaled for viewing and measured unscaled). Format left,top,width,height in px.

At 1280×900:
- A meal draft: no horizontal overflow, 0 elements past the right edge. Title x=453, card x=453 w=640, box x=453 — one left edge (before the fix the card sat at 453 and the title/box at 460; the inner scroller's scrollbar moved it, so A now scrolls the window). Textarea 44 tall, mic 44×44, Send 72×44, all on one row. Draft table 590 wide in a 590 box (scrollWidth 590, nothing cut). Table head not sticky.
- B order draft, panel open: panel 448 wide at the right edge; 0 elements past the right edge. Table 365 in a 365 box. Textarea, mic, Send all 44 tall. Before the fix the four-column order table needed 402px and pushed the card 21px past the panel (right=1301); price now sits under quantity.
- All buttons 44px tall except the app's own "i" hint (20px, unchanged shared component).

At 375×812 (the iframe shows a classic 15px scrollbar when content is long; a phone's scrollbar overlays, so a phone has 15px more):
- A empty: Send ends at 359, gutter 16 both sides; "New" sits beside the title (291,80). 0 elements past the edge.
- A meal / order drafts: 0 elements past the edge; tables 294 in 294; box row 16→344 with the scrollbar. The composer row used to be 353px in a 343px column and pushed Send 10px over (textarea min-content inside a grid); fixed.
- B order draft: panel full screen; table 277 in 277; 0 elements past the edge.
- Typing a 140-character message grows the box to 138px (five lines) and then it scrolls.

Interaction check (scripted clicks in the iframe): suggestion "Plan lunch for Sunday" → "Checking the calendar…" with Stop showing at 0.9s → asks for dishes and head counts → typed answer → asks which Majjige Huli → "Ash gourd" → draft with "Not saved yet" → Confirm shows busy → "Added to the planner" + "Open lunch on 11 October" + "Saved". Card state Expired from the mock bar shows "expired after 24 hours". Switching to B shows "Ask the assistant". No console errors.

Screenshots, `frontend/app/dev-assistant/shots/final/`: A-1280-empty, A-1280-meal-draft, A-1280-asks-for-details, A-375-meal-draft, A-375-errors, B-1280-closed, B-1280-po-draft, B-375-po-confirmed. A-375-errors and A-1280-asks-for-details were taken before the last two tweaks ("New conversation" became "New"; the small buttons became 44px), so they show the older labels and sizes.

**Acceptance criteria:**

- Page at `frontend/app/dev-assistant/page.tsx`, opens with the playground line: done.
- Built from the app's parts (PageHeader, Button, Badge, Card, StatTile, InlineNotice, InfoHint, SegmentedControl, CookingLoader, ruled-table constants, FIELD_LABEL, unitLabel, money, navForRole): done. The menu is a copy of the Sidebar markup, as dev-deliveries did, because nav.ts has no Assistant entry and may not be changed.
- Sample data in state, no API, fake streaming with timers: done.
- Options A and B, switchable: done.
- States: empty with suggestions; streaming with "Checking stock…"; asks for missing details; meal-plan and purchase-order draft cards with Confirm/Reject; confirmed (link), rejected, expired; errors with code and next step; input with Send and a disabled microphone slot: all done and clicked through.
- 375 and desktop checked by measurement: done, numbers above.
- tsc clean: done.

**Not done / needs Rajeev:**

- KMS codes are a placeholder (KMS-000000). The real ones come with T11.
- Hand smoke test was by scripted clicks and screenshots in Chrome on this Mac, not on a real phone.
- The real build will need two things the mock found: a draft card's table head must not be sticky (every app table's head sticks from 1024 up, and in a chat it floated over the messages), and a four-column table does not fit a 448px panel.
