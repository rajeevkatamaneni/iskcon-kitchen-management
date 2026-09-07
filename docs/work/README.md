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

## Three things the protocol has had to learn, and where they came from

Each of these cost a wave something. They are here rather than in one task's row because the next
person to hit them will be planning a different task.

**1. Where a value is stored as a *name* rather than a *reference*, the question is never who
hardcodes it — it is who resolves it.** From wave 4b, T-005. The planning pass established that no
`BREAKFAST`/`LUNCH`/`DINNER` literal is hardcoded anywhere in `frontend/app`, `frontend/lib` or
`frontend/components`, and concluded from that that renaming a meal kind was safe. The check was
correct and it answered the wrong question: the kind is stored as a **name string** in three tables,
and eight `require()` sites on the server resolve those stored names — one of them on a read path, so
the breakage would have surfaced only when somebody reused a plan. Two of the three tables were
touched by this very batch, in V64 and V95, and neither review asked this question. A grep of the
frontend is not evidence about a name that lives in the database.

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
