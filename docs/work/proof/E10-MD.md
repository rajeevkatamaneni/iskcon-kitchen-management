# E10-MD — the assistant's replies are drawn as Markdown, not shown as raw pipes and asterisks

**Files changed** (all inside the contract):

- `frontend/components/assistant/AssistantMarkdown.tsx` (new): the renderer.
- `frontend/components/assistant/AssistantParts.tsx`: `AssistantTurn` uses it; the streaming cursor moved to its own line under the reply.
- `frontend/__tests__/assistant-markdown.test.tsx` (new): 10 tests.
- `frontend/package.json`, `frontend/package-lock.json`: `react-markdown 10.1.0`, `remark-gfm 4.0.1`, `remark-breaks 4.0.0`, pinned exact.
- Gitignored, not committed: `frontend/app/dev-assistant-panel/page.tsx` gained three replies to ask for: `telugu`, `stock`, `wide`. Screenshots are in `frontend/app/dev-assistant-panel/shots/md/`.

**What was chosen and why.** react-markdown with remark-gfm. It's maintained, it builds React elements instead of an HTML string, and it never parses raw HTML unless a plugin is added, and none is. remark-gfm adds tables. remark-breaks keeps a single newline as a line break, which the old `whitespace-pre-wrap` did, so "Ajwain: 0 gm" on one line and "Asafoetida: 0 gm" on the next don't run together.

**How it draws a reply:**

- A table uses `RULED_TABLE` and the `components/ds/table` constants, so the shared fitter spaces it from 1024px up and it becomes phone cards below that. A small rehype step picks each column's kind. The first column is the name (`TH_PRIMARY`). A column where every filled cell is a figure ("0 gm", "₹2,400/Kg", "12%") is `TD_FIXED_NUM`. Anything else is `TD_SECOND`. Every cell after the first gets `data-label`, so a phone card reads "On hand 0 gm" and not just "0 gm".
- The table sits in a hairline frame that scrolls sideways inside itself. The page never does.
- Lists, bold and italic are drawn in the app's own styles. Inline code is mono, nothing more. A heading is shown as a bold line at body size.
- HTML in the text appears as literal text. A Markdown image is never fetched; its description is shown instead, because an image address can smuggle data out. A link shows its words and goes nowhere.
- While a reply streams, three things are held back: a table row until its line ends; a table heading until the dashes under it have arrived; and on the line still arriving, a `**` or backtick that has not closed yet. Everything else is drawn as it comes.

**Commands run and what came back**

```
$ tools/work-lock.sh run verify 'cd …/frontend && npx tsc --noEmit && echo TSC-OK && npm test 2>&1 | tail -8 && npx next build 2>&1 | tail -15'
TSC-OK
 ✓ __tests__/calendar-names.test.ts  (4 tests) 1ms
 ✓ __tests__/routes.test.tsx  (3 tests) 1ms

 Test Files  195 passed (195)
      Tests  2838 passed (2838)
   Start at  20:01:56
   Duration  25.99s (transform 5.68s, setup 8.32s, collect 24.04s, tests 119.60s, environment 47.26s, prepare 9.02s)

├ ○ /wishlist/new                        1.9 kB          157 kB
+ First Load JS shared by all            87.4 kB
○  (Static)   prerendered as static content
ƒ  (Dynamic)  server-rendered on demand
work-lock: 'verify' released; command exited 0
```

After the last test was added (the bold/code hold-back):

```
$ npx vitest run __tests__/assistant-
 ✓ __tests__/assistant-markdown.test.tsx  (10 tests) 29ms
 ✓ __tests__/assistant-panel.test.tsx  (11 tests) 103ms
 ✓ __tests__/assistant-drafts.test.tsx  (11 tests) 176ms
 Test Files  3 passed (3)
      Tests  32 passed (32)
$ npx eslint components/assistant __tests__/assistant-markdown.test.tsx --max-warnings=0
(no output)
```

**Measured in headless Chrome.** It used its own profile and CDP device emulation, and never touched Rajeev's window. Dev server on :3015 in this worktree, harness `/dev-assistant-panel`. Each reply was sampled every 20ms while it streamed, checking whether `|`, `---` or `**` ever showed as text.

```
375-telugu  streamBad 0 samples 312 sawTable True  page 375/375   frame w341 clientW 339 scrollW 339 overflowX auto
375-stock   streamBad 0 samples 382 sawTable True  page 375/375   frame w341 clientW 339 scrollW 339
375-wide    streamBad 0 samples 418 sawTable True  page 375/375   frame w341 clientW 339 scrollW 339
1280-telugu streamBad 0 samples 314 sawTable True  page 1280/1280 panel x832 w448  frame w414 clientW 412 scrollW 412 overflowX auto
1280-stock  streamBad 0 samples 385 sawTable True  page 1280/1280 frame w414 clientW 412 scrollW 412
1280-wide   streamBad 0 samples 426 sawTable True  page 1280/1280 frame w414 clientW 412 scrollW 476 overflowX auto
```

There is no page-level sideways scroll at either width. Two-column tables fit the 448 panel at 1280, one line per cell (rows 52px tall). At 375 every table is phone cards, labelled.

**Three faults found by measuring and fixed before reporting:**

1. The reply's `grid` grew to the table's width: 478px in a 414px column at 1280, cut off by the panel. Now `grid-cols-1` (minmax 0).
2. From 1024px up, `globals.css` sets every table box to `overflow: clip` and expects the fitter to make each table fit. A five-column table can't fit 412px; it measured 476 and its Vendor column was cut off. The frame now scrolls. `lg:!overflow-x-auto` did not work, because Tailwind also generated an important copy of the globals clip rule (it names `.overflow-x-auto`). It is written as `lg:![overflow-x:auto]` instead. The header is not sticky (`lg:!static`, as in DraftTable), so nothing is lost.
3. While streaming, "**reorder" showed its asterisks for 5 samples (about 100ms) before turning bold. An unclosed `**` or backtick on the line still arriving is now held back. After the fix: 0 samples.

**Acceptance criteria**

- Table renders as a real table: test "draws a table as a real ruled table" (Telugu headings, `kms-table`, `kms-primary`, `kms-num`, `data-label`, no `|`/`---`/`**` left), plus the screenshots.
- Bold and list: test "draws bold and a list".
- No raw HTML executed: test "never runs HTML" (`<img onerror>` and `<script>` stay as text; no `img`, `script` or `[onerror]` in the DOM; the global flag is unset). Test "fetches no Markdown image and follows no link".
- Partial lines while streaming: three `settledMarkdown` tests, and the 20ms sampling above with 0 bad samples at both widths.
- Telugu and Kannada render: screenshots `375-telugu.png`, `1280-telugu.png`, `375-wide.png`, all in the app's Anek fonts.
- Fits at 375 and 1280 with no page scroll: measured above.

**Not done / needs Rajeev**

- **Deviation from the brief: figures are not right-aligned.** The brief said "right-aligned per DESIGN_SYSTEM". DESIGN_SYSTEM §Tables rule 1 (v1.12, his instruction of 2026-09-18) says the opposite: every column is left-aligned, figures included, with tabular figures. I followed the design system and drop any `|---:|` the model asks for. If he wants figures right-aligned in the assistant only, that is a one-line change, but it would break the rule.
- **A five-column table at 1280 is cramped.** In the 448px panel the fitter squeezes it as far as its last resort: "4.5 Kg" goes onto two lines and names wrap to three lines. It then scrolls 64px inside its frame (`1280-wide.png`). That is the shared fitter's designed behaviour, and nothing is cut off. The real fix is to ask the prompt for at most three columns. That belongs to the `runner/prompts` builder, so I didn't touch it.
- **Links show their words only.** If he wants in-app links (for example to an order), that needs an allow-list of app paths.
- The streaming cursor is now a small block on its own line under the reply, not inline after the last word.
- Hand smoke test: done in the harness with a stubbed API (screenshots above). Not tested against the real runner or model.
