> **Moved here 2026-09-08 from `docs/work/proof/T-005.md`, unedited.**
>
> This is **wave 4b's** T-005 proof, committed in `ec8d575` — the refusal that found the
> delete/rename premise false and produced T-038 and lesson 1 of `docs/work/README.md`. It is a
> shipped record and it stands exactly as its builder wrote it.
>
> It moved because **wave 10's** T-005 — the screen that was finally built on the server this
> refusal caused to be corrected — was given the same path by a contract that named a file it had
> not checked. Its builder appended this proof verbatim rather than overwrite it, and flagged the
> collision instead of tidying it away. That was the right call and this rename is the resolution.
>
> **The lesson is the ledger's own, arriving in a third medium.** A proof path is derived from a
> task id, task ids are reused when a stopped task is later rebuilt, and nothing in the protocol
> made the two halves distinguishable. A contract is a claim about the tree, and only the tree
> describes the tree — including where a file already is.

# T-005 — STOPPED, not built: the delete refusal the task asks for is contrary to documented design, and the rename it calls safe is the dangerous half

**Status: stopped and reported. No page written, no test written, no code changed.**

I am reporting red rather than shipping a compliant guess. Two of the four acceptance
criteria rest on a premise about the server that is false, and the operation the task
treats as safe (rename) is the one that breaks other screens.

**Files changed:** none. Only this proof file was created —
`docs/work/proof/T-005.md`, which is inside the contract. `frontend/app/settings/meal-kinds/page.tsx`
and `frontend/__tests__/meal-kinds.test.tsx` were deliberately **not** created; see below.
Nothing outside the contract was written or read beyond the read-only backend files the
task itself directed me to (`meal/MealKindService.java` and what it led to).

---

## Finding 1 — the server does not refuse a delete, and three migrations say it must not

The task: *"Deleting a kind that planned meals already reference must refuse readably...
Find what the server actually answers with (read `meal/MealKindService.java`)."*

I did. `backend/src/main/java/org/iskcon/kms/meal/MealKindService.java`:

```java
	@Transactional
	public void delete(UUID id) {
		// A plan records its kind by name, not by reference, so removing a kind never breaks the
		// meals already planned under it — they keep reading as what they were.
		jdbc.update("DELETE FROM meal_kinds WHERE id = ?", id);
	}
```

Unconditional. No count, no guard, no `ApplicationException`. `MealKindController.delete`
returns `204 No Content` on every call. **There is no refusal to render.**

This is not an omission that a later task should fill in. It is a decision recorded three
times. `V64__meal_recording_and_job_cards.sql`, on the column that stores the kind:

```
    -- The kind by name, exactly as meal_plans records it. MealPlanService writes
    -- the canonical name off meal_kinds rather than whatever the caller typed, so
    -- the two always agree; matching on the text rather than on a meal_kinds id is
    -- the same choice V48 made, and for the same reason — a temple may delete a
    -- kind, and the meals cooked under it must keep reading as what they were.
    meal_kind       TEXT        NOT NULL,
```

And `V95__a_shift_says_which_meal.sql` confirms no FK is even possible:

```
-- No FK is possible for the date or the event name. None is possible for
-- the kind either, and that was checked rather than assumed: `meal_kinds`
-- is unique per tenant on `(tenant_id, lower(name))`, an EXPRESSION
-- index, and PostgreSQL will not accept an expression index as the target
-- of a foreign key. `meal_plans.meal_kind` is plain TEXT for the same
-- reason, so this column is consistent with the one it has to match.
```

Verified there is no FK anywhere:

```
$ grep -rn "REFERENCES meal_kinds\|references meal_kinds" .
(no output)
```

So the delete-in-use refusal cannot be built. The only routes to it were (a) invent a
client-side guard, which the task explicitly forbids, or (b) add the server refusal, which
my contract explicitly forbids ("this task writes no server code at all"). Hence: stop.

**Acceptance criterion "Delete refuses readably when the kind is in use — the server's
refusal, rendered" is void, and so is "both delete outcomes" in the test criterion.**
There is one delete outcome: it succeeds.

## Finding 2 — the docket's worry was checked and is unfounded, exactly as the task said

Re-verified rather than taken on trust, as instructed. No hardcoded kind literals:

```
$ cd frontend && grep -rn "BREAKFAST\|LUNCH\|DINNER\|\"Breakfast\"\|'Breakfast'\|\"Lunch\"\|'Lunch'\|\"Dinner\"\|'Dinner'" app lib components
=== exit 0 ===
(no matches)
```

Case-insensitive sweep returns 29 hits and **every one is prose in a doc comment or a form
placeholder** (`app/today/page.tsx:263` "A lunch of three preparations is one lunch",
`components/IngredientRequestForm.tsx:290` placeholder "Janmashtami feast — Sunday lunch for
400", etc.). Not one is a value compared, switched on, or rendered as a kind.

The docket's stated fear was correct to dismiss. But it was the right answer to the wrong
question — see Finding 3.

## Finding 3 — rename is the unsafe operation, and this is the reason to stop

The coupling between a kind and the meals planned under it is **not** in frontend code. It
is in server-side data: three tables store the kind as a **name string**, not a reference.

| Table | Column | Migration |
|---|---|---|
| `meal_plans` | `meal_kind` | V48 (renamed from `slot`) |
| the meal-recording table | `meal_kind TEXT NOT NULL` | V64:38 |
| `shifts` | `meal_kind TEXT` | V95:90 |

`MealKindService.update()` writes only to `meal_kinds`. It does not touch any of those three.
So renaming *Lunch* to *Raj Bhog* leaves every existing plan, recorded meal and linked shift
still saying `"Lunch"` — a name that no longer exists in `meal_kinds`.

That would be survivable if those stored names were only ever displayed. They are not.
`MealPlanService.java:237`, in `previewReuse` — a **read** path, `@Transactional(readOnly = true)`,
the one behind the *Reuse a plan* screen — resolves every stored name through `require()`:

```java
		for (MealPlanView meal : source) {
			MealKindView kind = kinds.computeIfAbsent(meal.mealKind(), mealKindService::require);
```

and `require()` throws:

```java
	public MealKindView require(String name) {
		return byName(name).orElseThrow(() -> new ApplicationException(
				ErrorCode.MEAL_KIND_UNKNOWN, ...));
	}
```

`MEAL_KIND_UNKNOWN` is `KMS-400071`, HTTP 409:

```
	MEAL_KIND_UNKNOWN(400071, 409,
			"This temple doesn't have that kind of meal.",
			"Choose one from the list, or ask a Temple Admin to add it in temple settings."),
```

**So: a Temple Admin renames Lunch on the screen I was asked to build, and the Reuse-a-plan
screen starts returning 409 for any window containing a pre-rename lunch.** Same throw on
`ServedMealService:110/191/283/308` (recording a meal, and the `/planner/[date]/[kind]` route)
and `MealCrewService:143` (the crew readout).

The plain planner grid is safe — `MealPlanService.list()` (line 176) is straight SQL returning
the stored text, no `require()` — so the damage is not visible on the screen people look at
first. It surfaces later, on reuse and on recording. That is worse, not better.

Note the bitter detail: KMS-400071's own next-step text is *"ask a Temple Admin to add it in
temple settings"* — it points at the screen I was asked to build, and that screen would be
the thing that caused it.

**Acceptance criterion "A renamed kind appears renamed on the planner without a code change"
is half true and misleading**: the picker offers the new name, and existing meals keep the old
one and become unresolvable.

## Why I did not build the safe subset

List and add are harmless. Delete is harmless and is what the schema intends. I could have
shipped those three and held rename back — but rename is the docket's headline ask ("wants
'Raj Bhog' rather than 'Lunch'"), and `updateMealKind` is also the only way to reach
`sortOrder`, `defaultReadyTime`, `isEvent` and `needsOccasion`, which are safe to change.
Splitting "you may edit every field except the one you came here to edit" is a product
decision about the temple's data, not a frontend call, and making it silently is exactly the
compliant guess the brief warns against.

## What I would have exposed on the form, had I built it

Recorded here so the decision is not lost when this comes back. All five fields, none dropped:

- **Name** — free text. The point of the screen.
- **Sort order** — a number, presented as position in the planner's picker, since that is what
  it does. Safe to change; affects no stored row.
- **Default ready time** — a time input that must be genuinely clearable, not defaulted. Null is
  meaningful and documented in both `MealKindView` and `CreateMealKindRequest`: it makes the
  kind always ask. A time picker that cannot be emptied would silently destroy that.
- **`isEvent`** and **`needsOccasion`** — shown, but not as bare checkboxes. Per their doc
  comments these change what the planner demands of *every* meal of that kind: `isEvent`
  reveals the event name and the outside-event chain (E4-S15); `needsOccasion` makes the meal
  name its festival (item 26). They belong on the form — dropping them would make every kind
  added here an ordinary sitting, with no way to add a feast or an event — but each needs its
  consequence written next to it, and they are mutually exclusive in every seeded row
  (`Festival feast` is `needsOccasion`, `Event` is `isEvent`, never both).

## Commands run

Every command above was run for real; output is pasted at the point it is cited. No `vitest`,
`tsc` or `next build` was run and the verify lock was never taken — I changed no code, so
there was nothing to verify, and holding the lock to prove nothing would have queued the three
other builders behind me for no reason.

**No Gradle run**, per the task: this task writes no server code.

## Acceptance criteria

| Criterion | Verdict |
|---|---|
| Renamed kind appears renamed on the planner | **Void as written.** True of the picker; false of existing meals, which orphan (Finding 3). |
| Delete refuses readably when in use | **Impossible.** Server returns 204 by documented design (Finding 1). |
| Temple Admin only, guard matches nav row `["TEMPLE_ADMIN"]` | Not built. Would have been straightforward. |
| Tests cover list / add / rename / both delete outcomes | Not built. "Both delete outcomes" does not exist. |
| `tsc` clean, `npm test` green, `npm run build` clean | Not run — nothing changed. |

## Not done / needs Rajeev

The whole task. It needs one product decision before the screen can ship, and the decision is
his, not mine:

**When a temple renames a meal kind, what happens to the meals already planned and cooked
under the old name?**

Two coherent answers:

- **(A) Rename cascades; delete stays permissive.** `MealKindService.update()`, when the name
  changes, also updates `meal_plans.meal_kind`, the recording table's `meal_kind` and
  `shifts.meal_kind` for that tenant under RLS. History then reads as *Raj Bhog* throughout,
  which is probably what a temple that renamed it means. Delete keeps its current behaviour,
  which V48/V64 argue for and which I agree with — a deleted kind's meals should keep reading
  as what they were, because they genuinely were that. Smaller change, fits the existing
  design, needs no migration and no new error code. **My recommendation.**
- **(B) Give plans a stable reference and make the name a display label.** Correct long-term,
  but V95 explains why an FK is impossible today (expression index on `(tenant_id, lower(name))`),
  so it means a surrogate key column plus a backfill across three tables. Much larger.

Either way it is **a backend task that must land before or with the frontend screen**, and it
needs its own reservation. I have not taken one and have not touched a backend file.

If the answer is "ship the screen now without rename", say so explicitly and I will build
list + add + delete + the non-name fields, with the name field read-only and a line saying why.
I did not assume that.

**Hand smoke-test:** not performed — there is no screen to smoke-test.
