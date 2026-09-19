# Prompt for the next session: build procurement (PO → delivery → invoice → payment)

You are building the procurement rework for the ISKCON kitchen management system: PO → delivery → invoice →
payment, plus **duplicate-ingredient prevention (§9A)**, **market rate (R-ING-3)** and **packs, bags and tins
as alternate units (R-ING-1)**. Everything was
decided with Rajeev on 2026-09-19. Your job is to build **all of it**, prove it works, and release it.
Read this whole prompt before doing anything.

## Read first, in this order
1. `CLAUDE.md`, especially the top sections: **"Verify, never estimate — MANDATORY"**, the
   before/after-building questions, the layout rules, and "How to talk to Rajeev".
2. **`docs/work/PROCUREMENT-REQUIREMENTS.md`**: the binding spec. Every requirement has an ID
   (`R-…`) and acceptance criteria (`AC`). §11 is how you must run the work, §12 lists the open
   questions, and §13 lists the known gaps.
3. The three mocks in **`docs/work/mocks/`**: `dev-po.page.tsx`, `dev-deliveries.page.tsx` and
   `dev-invoices.page.tsx`. To see one, copy it temporarily to `frontend/app/<name>/page.tsx` and open
   `http://localhost:3000/<name>`. **Delete it from `app/` before any commit or deploy**, because
   `deploy.sh` ships the working tree.
4. `docs/DESIGN_SYSTEM.md` (v1.13), `docs/work/LOCAL-STACK.md` (the local stack, how to start and stop
   it, and how to refresh data from staging), and `docs/DEPLOYMENT.md`.

## The mocks are the specification: pixel-perfect, zero deviation
- **Note:** the mocks predate the packs/bags/tins decision (they show plain Kg/L), and the vendor
  onboarding table has no mock. The requirements document says how to handle both: keep the mocks'
  layout, follow the document for units, and put the onboarding table on the Desk for Rajeev's review.
- The real screens must be **pixel-perfect replicas of the mocks**: same layout, spacing, sizes, order,
  labels, wording, colours, states, empty states and phone layout.
- **Prove it by measuring.** For each screen, measure the real page and the mock side by side at 1280
  and 390 wide, using DOM geometry and a pixel comparison of full-resolution screenshots, and record the
  numbers in the proof. "Looks the same" is not evidence.
- **The only allowed differences** are the ones `PROCUREMENT-REQUIREMENTS.md` states explicitly. For
  example, design A is the chosen invoice layout; the mock's menu copy on the Deliveries page is replaced
  by the real menu; the PO page keeps "Return to vendor". List every difference in the proof with the
  requirement ID that allows it. Anything else that differs is a defect.
- Where the mock and the document disagree and the document doesn't settle it, **ask Rajeev** (below).
  Never pick one yourself.

## How to run it (so compaction can't lose detail)
- **You are a conductor, not a worker.**
  - Don't read big files or agent transcripts into your own context.
  - Keep `docs/work/PROCUREMENT-PROGRESS.md` updated: one line per requirement, with its status and
    proof path, after every step. A compaction or a new session then resumes from that file.
- **Separate agents:**
  - **Build agents** (`builder`): one requirement group each, never two on the same file at once. Each
    writes `docs/work/proof/<task>.md` with real command output and measurements, and reports in at
    most 150 words.
  - **Verify agents:** fresh agents that did not build the work. Each checks one group against the
    document's ACs and the mocks only, measures everything, signs in locally as the role the screen is
    for, and reports pass or fail per AC.
  - **One clarifier agent:** answers build and verify agents' questions **only from
    `PROCUREMENT-REQUIREMENTS.md` and the mocks**, quoting the section. When those are silent or
    ambiguous, it says so, and you take the question to Rajeev. It never invents an answer.
- **Questions for Rajeev** go on the **Decisions Desk**: https://claude.ai/artifact/EZBQnhDRRX8w2fLV15v2yr.
  Its db has a `questions` collection in the CLAUDE.md decision shape, a `notes` collection, and
  `status/agents` for the ticker of running agents. **Start a session cron that polls it every 5
  minutes.** Put open questions Q-1 to Q-9 (§12) there at the start. Don't block on them: build
  everything that doesn't depend on an answer, and build the dependent parts as soon as he answers.
  Keep the ticker updated as agents start and finish.
- The order of work is in §11 of the document. Parallelise where files don't overlap.

## Don't stop until it's done
1. Build every requirement in the document.
2. Test it all locally on the local stack, signed in as Temple Admin, Kitchen Manager, Kitchen Staff,
   and a user without the new permission. Walk the full story in §10 end to end.
3. Fix every bug found, then re-test. Repeat until the verify agents report **every AC passing** and a
   final full-story run finds **no defects**. Only then is it done. Don't hand back partway, and don't
   report "mostly working".
4. Run the full local checks: frontend `npx tsc --noEmit && npx eslint . --max-warnings=0 && npx vitest run`,
   backend `./gradlew test`. All green, nothing skipped.
5. Commit straight to `main` in tidy groups. Verify the committed tree (`git archive HEAD` into a clean
   folder, then build and test there, including `next build`). Push.
6. Watch CI until it's green. Fix and re-push if not.
7. **Staging deploy:**
   - **Take an on-demand backup of the staging database first** (there are migrations), and record its id.
   - Deploy from a clean tree.
   - Confirm the new revisions, the Flyway version and `/health` UP.
   - **Timing:** Rajeev's demo is Sun 20 Sep 2026 at 9 AM IST (Sat 19 Sep, 20:30 PDT). **Don't deploy
     to staging between Sat 19 Sep 17:00 PDT and Sun 20 Sep 01:00 PDT** unless Rajeev says so on the
     Desk.
   - Record the release in `docs/work/proof/RELEASE-<date>.md`.
8. **While the deploy runs:** update the UAT material in `docs/uat/` (read `docs/uat/TRACEABILITY.md`
   first):
   - create or update the UAT stories and test cases so **every feature in this build is exercised 100%**
   - add any missing UAT tests: every R-… requirement maps to at least one test, with the role, steps
     and expected result
   - update the traceability table
   - commit and push the UAT docs
9. After the deploy, run the full story on staging once. Staging-only items (real translation, real PDF
   files, file uploads to cloud storage) are checked there. Then tell Rajeev in a few plain lines what is
   live, what was verified where, and anything not verified.

## Also in the working tree
Uncommitted changes from 2026-09-19, built and tested but **not yet seen by Rajeev**:
- about 45 hand-made buttons converted to the shared button, with the press effect (`docs/work/proof/T-242.md`)
- local-only 8-hour idle sign-out (`NEXT_PUBLIC_KMS_IDLE_LIMIT_MINUTES` in the gitignored `frontend/.env.local`)
- `docs/work/PROCUREMENT-*.md`, `docs/work/mocks/`, `docs/work/reference/`, and proofs T-245 to T-247

Include them in your first commit group, after your own full test run passes.

## Talking to Rajeev
Plain English, shortest form that keeps the details, answer first. No tables or essays unless the content
is tabular. When you need a decision, use the CLAUDE.md decision shape on the Desk. Never say something
works unless you measured it.
