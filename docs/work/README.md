# How a batch of work gets built

Written for a session that has lost its context and is picking this up cold. Everything the
machine knows is in files, deliberately, because a conversation long enough to build a batch of
work is a conversation that will be compacted several times, and each compaction loses resolution.
Nothing important is allowed to live only in the transcript.

If you are that session: read this file, then `docs/work/DISPATCH.md`. Between them they tell you
what is queued, what is in flight, what is proven and what has shipped.

## The shape of it

```
Rajeev's list
     │
     ▼
work-manager ──── orders it into waves, reserves the contended slots, writes DISPATCH.md
     │
     ├──▶ builder T-001 ─┐
     ├──▶ builder T-002 ─┤ concurrent, disjoint files, proof file each
     └──▶ builder T-003 ─┘
     │
     ▼
main session ──── reads the proofs; sends anything unproven back
     │
     ▼
release ────────── ONE at a time: commit → CI → deploy → report
```

Three roles, three agent definitions in `.claude/agents/`, and none of them overlaps another:

- **`work-manager`** orders and dispatches. It never edits product code.
- **`builder`** implements one task inside a fixed list of paths. It never commits.
- **`release`** is the only thing that commits, pushes, watches CI and deploys. Never two at once.

## Why the main session stays small

It holds four things and nothing else: the ledger's state, the proof files it has checked, what it
told the release agent to ship, and what came back. Every transcript that would have crowded it out
— the file reads, the failed test runs, the search that went nowhere — happens inside a subagent
and dies there. That is the entire point of the arrangement.

So: do not read source files in the main session to "check" a builder's work. Read its proof. If
the proof does not convince you, send the task back rather than doing it yourself.

## Collisions, and how they are actually prevented

Not by hoping. By three mechanisms, in order of how much they carry:

**1. Disjoint path contracts.** Every task declares every file it will touch before it starts. Two
tasks run concurrently only if those sets do not intersect. A builder that discovers it needs a
path outside its contract stops and reports; it does not widen its own contract.

**2. Reservations for the files everyone wants.** Some files are touched by nearly every task —
`frontend/lib/api.ts` in 23 of the last 120 commits, `ErrorCode.java` in 14 — and refusing to
parallelise anything that needs them would serialise the whole batch. So the work manager edits
them itself, once, before the wave starts, and hands each builder the slice it was given. This is
the only mechanism that catches the **migration version** collision: two builders writing
`V95__a.sql` and `V95__b.sql` create no file conflict at all, and nothing notices until Flyway
refuses to boot.

**3. A lock on the verify phase.** `tools/work-lock.sh`. Concurrent editing is safe; concurrent
`./gradlew test` and `next build` in one checkout are not, because they share `backend/build`,
`.next/` and the Gradle project lock. Builders queue for the test suite and run it backgrounded.

Where any of this is in doubt, the rule is Rajeev's: **do not parallelise it.** A serialised task
costs minutes. A file two agents wrote at once costs the wave, and it is not always obvious that
it happened.

## Why one release agent

Because the alternatives all fail in the same way. Two agents committing to `main` interleave; two
watching CI each see the other's run; two calling `deploy.sh` race for the same Cloud Run revision
and the loser's change silently is not live. It is also the reason the release agent takes a
fail-fast lock instead of a queue — a second one starting is a mistake to report, not a turn to
wait for.

One consequence worth naming: this project pushes **straight to `main`**, so CI runs *after* the
push. CI is confirmation, not a gate. The real gate is the release agent's full-suite run against
`git archive HEAD` in a clean directory, which is also the only run that proves a fresh clone
builds — `.gitignore` has hidden a source file from a checkout before, for two days.

## The files

| Path | What it is | Who writes it |
|---|---|---|
| `docs/work/DISPATCH.md` | The execution ledger: every task, its paths, its reservations, its state | work-manager, and release on state change |
| `docs/work/proof/T-nnn.md` | One builder's evidence: commands run and the output they returned | that builder, nobody else |
| `.work-locks/` | Live locks. Gitignored; empty is the normal state | `tools/work-lock.sh` |

`docs/CHANGELOG.md` and `docs/WORK_QUEUE.md` are written **only** by the release agent, at commit
time. That is not bureaucracy — it is what keeps two of the repo's hottest files permanently out of
contention.

## Five things the protocol has had to learn, and where they came from

Each of these cost a wave something — the last two cost nothing, because two builders did them
without being asked and the lesson was to make that standard. They are here rather than in one task's
row because the next person to hit them will be planning a different task.

**1. Where a value is stored as a *name* rather than a *reference*, the question is never who
hardcodes it — it is who resolves it.** From wave 4b, T-005. The planning pass established that no
`BREAKFAST`/`LUNCH`/`DINNER` literal is hardcoded anywhere in `frontend/app`, `frontend/lib` or
`frontend/components`, and concluded from that that renaming a meal kind was safe. The check was
correct and it answered the wrong question: the kind is stored as a **name string** in three tables,
and eight `require()` sites on the server resolve those stored names — one of them on a read path, so
the breakage would have surfaced only when somebody reused a plan. Two of the three tables were
touched by this very batch, in V64 and V95, and neither review asked this question. A grep of the
frontend is not evidence about a name that lives in the database.

*Confirmed from the other end in wave 4c, by T-038 — the very task this lesson produced.* Asked to
decide whether the rename cascade should match the old name exactly or case-insensitively, it found
that **`ShiftService.java:157` stores `shifts.meal_kind` as `trimToNull(request.mealKind())` — what
the caller typed, never passed through `require()`** — while `staff/MealMoment` folds both sides when
matching. So a shift linked to `"lunch"` against a temple storing `"Lunch"` is a **working row
today**, proved by `ShiftMealLinkIT`. An exact-match cascade would have renamed the plans and recorded
meals around that shift and stranded it, reintroducing the exact defect the task existed to fix. The
lesson generalises past renaming: **where a value is a name rather than a reference, ask who *writes*
it as well as who resolves it.** Three writers of one column had three different degrees of
canonicalisation and nothing in the schema said so — because no foreign key was available to say it.

*Third and fourth instances, 2026-09-08, and the general form is now clear: **a default is not a
deployment.*** Wave 4c's release report, `docs/CHANGELOG.md` and `docs/WORK_QUEUE.md` all stated that
T-042 was "inert as deployed" because `GEOCODING_PROVIDER` was unset on staging, and one of them
listed it as *waiting on Rajeev*. It was set, to `nominatim`, before wave 4c ever deployed:
`kms.geocoding.provider: none` is only the default in `application.yml`, while
`infra/environment/main.tf:437` hardcodes the variable and the live revision was confirmed to carry
it. **The config file was read and the deployed environment was not.** The feature had been working
in a browser the whole time, and the next session would have gone looking for a switch already
thrown.

That makes four instances of one shape in a day, **by four different authors** — a builder, this work
manager, a release agent and the coordinator — and the shape is worth stating in its most general
form, because each author met it in a different medium: *a grep of the frontend is not evidence about
a name in the database; a passing type-check is not evidence about what a spread omits; a config
default is not evidence about a deployment.* **Whenever a claim crosses a boundary, the evidence has
to come from the far side of it.** Ask who writes the value, not only who reads it — here the writer
was Terraform, and nobody looked.

**2. An audit trail must record what was *stored*, never what was *asked for*.** From wave 4b, T-008,
and it is the best find of the batch. Its first backend run failed on what was reported as `jsonb`
spacing; the pasted output showed the before-state reading `"12.971600"` against an after-state built
from the request that would have said `"12.9716"`. **Every audit event on that temple would have
claimed its coordinates had moved, in a field nobody edited.** That is worse than a missing entry: it
is a trail that lies, and it would have been believed. The fix — read the after-state back from the
row — is also the general rule, and any task that writes a before/after snapshot should be held to it.

**3. The merged-tree run is the work manager's step, not an accident of timing.** A builder verifies
with a targeted run, which is right: the verify lock is the bottleneck and the full suite is minutes.
But **a targeted `vitest` run never loads a repo-wide guard test**, so no builder can catch one by
construction, however careful it is. Wave 4a happened to get a merged-tree run because its last
builder's run postdated every other file, and that was luck written up as method. Wave 4b's was run
deliberately after every builder was out of the tree, and it immediately found T-008 tripping
`design-system.test.ts`'s hard-coded-timezone guard — green in the builder's own four-file run, red on
the tree that ships. Run the full suite over the finished wave before handing anything to the release
agent, and do it **after** the last shared-file edit, not before.

**4. Ask every builder for a negative control. A passing test proves the code does something; only a
failing-*without*-the-fix run proves it does this.** From wave 4c, where two of four builders did it
unprompted. T-043 patched both of its fixed lines back out inside a single hold of the verify lock,
watched three tests fail with `expected … to have property "eventName" with value null`, restored the
file through an `EXIT` trap and diffed it byte-for-byte against the verified copy. T-038 reverted its
whole service to `HEAD` with the tests untouched and got **5 of 8 failing**, including the reuse
preview returning `409 KMS-400071` — *the live defect reproduced on demand*, which is a far stronger
statement than "the fix works".

Why this is worth making standard rather than admiring once: a test written **after** a fix, against
the fixed code, passes whether or not it exercises the defect at all, and nothing in a green run
distinguishes the two. That is precisely the failure mode that let `eventName` survive for as long as
the feature existed — every test was green the whole time. The cost is one extra run inside a lock the
builder already holds. Two conditions: the restore must be **trapped rather than trusted**, because a
builder that dies mid-control leaves the tree broken for everybody else in the wave; and the control
must be run against the *tests as written*, never against tests adjusted to make it fail.

**And a third condition, from wave 5-2's T-059, because it defeats this whole rule silently: a
control has to be shown to have *applied*.** Its first control script patched the fix out with the
wrong indentation, so the patch matched nothing. Gradle saw an unchanged file, reported
`:test UP-TO-DATE`, and the run ended **`BUILD SUCCESSFUL`** — a control that passed while
controlling nothing, and "the negative control was green" is precisely the sentence a reader takes as
reassurance rather than as an alarm. The builder found it, fixed it with `set -e` and correct anchors,
got the real 3-of-6 failure, and **wrote the false green into its proof instead of quietly dropping
it.**

This is the same shape as every other lesson in this file, in the one medium the file had not covered:
*a green control is not evidence that the control ran.* Two cheap guards, and a control should carry
at least one — make the patch step **fail loudly** when it matches nothing (`set -e`, and a `patch`
or `git apply` whose non-zero exit stops the script), and **prove the tree actually changed** before
running anything, with a `git diff --stat` or a `--rerun-tasks` that denies the build its up-to-date
shortcut. An incremental build cannot tell a control that failed to apply from a control that applied
and passed. Nothing downstream can either.

**Two counting rules that come with it, both learned the same day.** A negative control's failure
count needs its own explanation **whenever any test asserts an absence** — such a test passes
vacuously once the feature is gone, so "four new tests, three failures" looks like a hole and is not
one. T-045 volunteered that rather than letting a reader wonder. And when the defect *is* an absence,
**`objectContaining` cannot test it**: a missing property and an explicit `false` read identically to
it. Inspect `Object.keys(...)` and then assert the value. Three defects in wave 4c had exactly this
shape — a field the client never sent, a `boolean` that deserialised an absent key to `false` — and a
test written the convenient way would have passed against every one of them.

**And the stronger form of the same idea, which wave 4c found by accident and is worth asking for
deliberately: a negative control that tests the *explanation* rather than only the fix is the best
evidence this arrangement produces.** T-044's brief, the sweep that generated it, and two sections of
`DISPATCH.md` all asserted the same mechanism — that the defect compiled *because* `mealFacts()`
lacked a return-type annotation and the spread carried its result past TypeScript's excess-property
check. The builder's control removed the annotation **and** the fields, and `tsc` still errored: a
spread exempts **excess** properties, never **missing required** ones. So the annotation only moves
where the error surfaces, and what actually closes the hole is the required-and-nullable declaration
on the client type.

**No passing test would ever have found that**, and four documents would have gone on repeating it
until somebody relied on it. When a brief tells a builder *why* something broke, that "why" is a
claim like any other and it should be tested as one — the cheapest way is to break the mechanism the
brief names and see whether the compiler or the suite actually reacts the way the brief says it will.

**And the two rules a *removal* wave adds, both from wave 4d, because deleting a feature inverts
almost everything above.**

**A removal criterion must name live references, never text matches.** Wave 4d's briefs both carried
"`grep -rni sattvic` returns nothing" as an acceptance criterion, and on the backend it was
**impossible by the brief's own instruction**: `MANAGE_SATTVIC_POLICY` had to survive because it
gates the *Ekadashi* flag, the recipe library uses "sattvic" as ordinary English in 39 taglines, and
three migrations carry historical comments that must never be edited. 104 hits, every one correct.
On the frontend the criterion was achievable and *cost* something — four "why" comments now say
*"the other one"* rather than naming what went. The criterion with meaning is **no identifier of the
deleted feature is referenced by any live statement**, and it generalises past this wave: **a
codebase carries its own history in prose**, so a text match cannot distinguish a live reference from
a migration comment, a changelog entry, or an English word that happens to collide. Both builders
worked out the right check for themselves and said so; the brief should have asked for it.

**On a removal wave, expect the *inverted* control, and prefer a named absence to a fabricated one.**
The standing rule above is that a builder proves its fix by watching the tests fail without it. A
wave that deletes a guard cannot do that, and wave 4d found a second, sharper reason why: **once the
work manager has written its reservations, `git show HEAD:<service>` no longer compiles**, because it
references constants already deleted from reserved files — which a builder cannot restore even
temporarily without breaching its contract. T-050 hit exactly this on three separate blocks, and
**said so rather than manufacturing something that looked like a control.** That is the behaviour to
want. The fix is *not* looser contracts: it is to ask a removal wave for a control that demonstrates
**the accepted consequence** instead. T-050's did — a recipe naming garlic saves `201`, the import
that used to answer `KMS-400104` returns `201`, the flag endpoint returns `404` — which proves the
guard is *gone rather than merely inert*, and that is a stronger statement than a revert could have
made. Ask for it deliberately, and read "I could not run that control, and here is why" as evidence
rather than as a gap.

**And the third removal-wave rule, which cost this batch a whole extra task: a removal's blast
radius includes the documents that promised the feature — and locked ones need sign-off *before* the
code, not after.** D-18 deleted the sattvic flag. It also falsified two entire UAT scripts, a story
that *was* the feature, nine further UAT files, five further story files, and **three documents
locked under Commandment 8**, which cannot be edited at all without Rajeev's explicit sign-off. None
of that was in the ruling's scope when the wave was planned; it was found by grepping the tree after
the code was written, and it became D-20 and a task of its own.

The sequencing is the lesson, not the extra work. **A locked document cannot be amended by the wave
that falsifies it unless the sign-off already exists**, so a removal wave either carries that
approval from the start or it ships code that contradicts an approved document — and the gap between
those two states is a window in which the governing documents are wrong. Scope a removal by asking
*what promised this?* before asking *what calls this?* The second question has a compiler to help;
the first has nobody.

*Two corollaries worth having.* **Withdraw, do not delete** — a story is a record of what was decided
and a UAT script a record of what was tested, so removing them loses the fact that the rule once
existed and was dropped on purpose, while marking them stops somebody running a script for a feature
that is gone. And **distinguish a live promise from a historical record inside the same file**:
`REQUIREMENTS.md:68` promises a flag and must be amended, `:229` records what a past round resolved
and must not, or the amendment falsifies the history the withdrawal exists to preserve.

**And the rule for repairing drift, from wave 4e's T-052: the proof is that the tool now proposes
nothing.** Terraform was found to be missing six environment variables the running service carries,
so `terraform apply` — **step 2 of this project's own deploy runbook** — would have stripped them and
silently broken three shipped features. The acceptance criterion for the repair is **not** that
`apply` succeeds. It is that **`plan` shows no change**. A green apply proves the tool ran; a no-op
plan proves the file describes what is actually running, which is the fact in question.

That is the same instinct as the negative control and the mutation test, stated for a third kind of
artefact: **do not accept evidence that would look identical if the thing were broken.** A successful
apply looks the same whether the file was right or wrong. Generalise it past Terraform — whenever the
work is *reconciling a description with a reality*, the proof is the diff the tool declines to
propose, never the command that exits zero.

**And the corollary wave 6 paid for, which is lesson 3 arriving through a *neighbouring file* rather
than a repo-wide guard: a task that modifies a screen must be granted that screen's existing test up
front.** T-026 changed `frontend/app/orders/page.tsx` and `frontend/__tests__/orders.test.tsx` went
red — its `next/navigation` mock supplied `useRouter` alone, and the screen now calls
`useSearchParams`. **CI would have gone red on a wave whose every builder reported green.**

The reason it is worth a rule rather than a shrug: **no builder could have caught it by
construction.** Each runs a targeted suite over its own files, so the test that already covered the
screen was the one file nobody ran — and it was owned by nobody, so the builder that broke it could
not legally repair it. The merged-tree run is the general safety net and it *would* have caught this,
but it catches it at the end of the wave, after a stop-and-report round trip that a one-line grant at
planning time makes unnecessary. The same wave shows the fix working: T-027 was granted
`shopping-list.test.tsx` and `ShoppingListIT.java` up front for exactly this reason and never had to
ask.

So when writing a contract, add every existing test that covers a file being modified — a `grep` for
the screen's route or the class's name is enough to find them — and grant it. It costs nothing when
unused, because a test nobody needed to change is a test nobody changed.

*(And when a widening is asked for anyway: the rule is ownership, not frozen contracts. Grep every
other contract in the flying wave for that path and hand it over if none holds it. Wave 6 granted
one, checked against three other contracts first, and recorded the check in the builder's own proof
so the record shows a deliberate widening rather than a quiet one.)*

**5. A builder that declines the brief's suggested approach, with better reasoning than the brief
had, is the outcome to want — not a delay.** Three waves running, the sharpest correction has come
from the builder rather than from the plan. 4a's T-035 handed back a widening the work manager offered
and its diagnosis was the right one. 4b's T-005 refused two of its own acceptance criteria and saved
every existing meal plan. 4c's T-041 rejected a payload mechanism the brief itself suggested — hidden
inputs — because a hidden input round-trips `latitude` through `Number()`, so **a lost value arrives
as `0` rather than as an error**, silently relocating a temple; the same shape of defect was found
independently by that wave's contract sweep and became T-044. So: name a suggested approach in a
brief **and say that it is a suggestion**, and read a builder's refusal as evidence before reading it
as a delay.

## What this is not

It is **not a fourth backlog**. The project already has three lists and they each mean something
different:

- `docs/OUTSTANDING_BUILD_LIST.md` — Rajeev's own review list. Binding, unordered, and **nothing
  leaves it until he has seen it working and said so.**
- `docs/WORK_QUEUE.md` — the ordered "what next", still the human answer to that question.
- `docs/stories/BACKLOG.md` — explicitly *not* scheduled.

`DISPATCH.md` is an execution ledger over the top of those: it says who is building what, right
now, in which wave, touching which files. A task in it always names the list it came from, and
closing it means updating that list — not this one.
