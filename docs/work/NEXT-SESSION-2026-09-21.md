# What is left, 2026-09-21

Written for a session with no memory of the one before it. Everything below is either **waiting on
Rajeev**, **half-done and blocked**, or **queued and not started**. What was finished is in
`docs/CHANGELOG.md` and in the five release records of 2026-09-20 under `docs/work/proof/`.

`origin/main` is at `fe6ca4b5`. Staging runs it: api `kms-staging-api-00178-fmv`, web `00166-nvm`,
worker `00160-ldj`, Flyway **v159**. Next free migration **V160**, next free error code
**KMS-400192**.

---

## 1. Blocked on a permission — finish this first, it is ten minutes

`docs/DESIGN_SYSTEM.md` §4 is **half-amended and uncommitted** in the worktree
`/Users/Rajeev/Workspace/kms-detail-pattern`. The new rule is written; the bookkeeping around it is
not, because the session's own permissions refused further edits to a locked document partway
through ("Modify Shared Resources").

What is already in the file, replacing the old "Where it stops: five":

> **Where it stops: a record, not a field count.** Anything the application keeps a record of — an
> ingredient, a supply, an inventory item, a vendor, a vendor's price for a supply, a kind of meal, a
> festival occasion, a member of staff — is opened from its list **by pressing its name**, gets a
> screen of its own showing everything about it, and is changed from there: **Edit**, then a form
> with **Save and Cancel**. However few fields it has, and with no Edit button in the row. Inline
> editing survives only where there is no record to open — a cell in a working table that exists for
> the length of one task: a shopping-list line, a delivery's received quantity, a roster's
> attendance mark.

**Three things still to do to it:**

1. The amendment note inside it says `(v1.1)`; it must say **`(v1.15)`**.
2. The **Status line** at the top still opens `**Status:** v1.14 —`. A v1.15 entry goes in front of
   it, in that line's own style. Suggested wording: *"v1.15 — a record is opened from its list by its
   name, gets a screen of its own and is edited behind Edit with Save and Cancel, however few fields
   it has, replacing the five-field rule; applies to what is already built, 2026-09-20 (§4)."*
3. **`docs/versions/DESIGN_SYSTEM_v1.15.md`** does not exist. The convention — stated in
   `docs/CHANGELOG.md`'s own preamble — is an immutable snapshot per locked version. Copy the
   amended root file to it, and add a `DESIGN_SYSTEM.md` entry to the changelog.

The authority for the change is Rajeev's instruction of 2026-09-20, quoted in the amendment itself.
**The code already follows the new rule** — Inventory, Ingredients, Supplies and Staff were all
converted on 2026-09-20 and are live. Only the document is behind.

*(If the worktree has been removed by then, the text above is the whole of the change.)*

---

## 2. Waiting on Rajeev — six questions, none blocking a build

Put these to him one at a time, in his own words, with a recommendation. Do not batch them into a
table.

1. **₹54.79 a plate.** ₹94,347 of materials for 1,722 plates on the seeded day, from the catalogue's
   market rates. Nobody here can say whether that is right for this temple; he can.
2. **Should an Aadhaar scan have its own, narrower permission?** He said the same permission as the
   rest of the staff record, and that is what was built. Worth his second look because the **PAN
   number is AES-encrypted and a scan of the same card is not**, and because the argument that
   produced `MANAGE_STAFF_CONDUCT_NOTES` applies at least as well here.
3. **Should reading a staff photograph be audited?** It is today, so opening any record with a photo
   writes an audit row — which will be most of what that action ever logs and makes a genuine
   Aadhaar read harder to spot. One line to exempt it.
4. **The link colour.** A staff name link is `rgb(81,86,92)` against body ink `rgb(35,37,40)`; only
   weight and hover mark it as a link. It is `--accent-text`, so it is a theme question for every
   link in the app, not a Staff one.
5. **On an ingredient's record, three things can still be changed without pressing Edit** — the "Not
   bought" tick, the Add pack size form, and Change market rate. They are arguably sub-records rather
   than fields, which is why they sit there. Do they go behind Edit too?
6. **Cancel and Save on an edit screen return to the record** (Inventory, Ingredients). **Staff's
   edit screen returns to the list.** One of the two is wrong; he should say which.

Smaller, same category: the inventory record's subtitle no longer repeats where the item is stored
(it has a labelled box instead), saving shows a confirmation nobody asked for, "Move to Ingredients"
was removed alongside "Move to Supplies" on his own reasoning, and a row's Delete button is 36px
against the 44px touch target.

---

## 3. Queued, not started

- **`docs/WORK_QUEUE.md` item 3.4 — "the kitchen staff role is the least finished in the product"**,
  and the rest of that gap sweep. Read the item before scheduling it: that list's own header warns
  that a third of it turned out to be built already.
- **The Kannada word-order defect** (item 3 of the queue's headline items).
- **A library recipe line cannot say what kind of thing an ingredient is** — no field for
  supply-or-food, Ekadashi, or category, so an import files leaf plates as food, allowed on Ekadashi,
  with a guessed category. Write-up at `docs/work/intake/LIBRARY-LINE-SUPPLY-2026-09-20.md`.
  **Recommendation on file: do not add the field.** The curated catalogue has no supply lines at all
  (460 lines, 103 names, every one food), and `is_supply` is read in exactly two backend files. Widen
  the existing "added by import" review list and its notice instead — that mechanism already exists
  and is durable. Add the line field the day a book actually names a supply.
- **`tools/seed/01c-whole-counted-stock.py` has never been run against staging.** It repairs counted
  items still holding fractions (Coconut 400.98 pieces, Lemon 1,080.74), reversing only quantities a
  person typed and refusing where the fraction is the application's own arithmetic. **Run it with
  `--dry-run` first** and read what it says it cannot fix; Coconut may need a market rate set before
  stock can be put back, and the script refuses rather than inventing a price.
- **`docs/OUTSTANDING_BUILD_LIST.md`: D1 still reads NOT STARTED.** It was done on 2026-09-20 —
  staging was reset to day one and seeded with a month of operations. Mark it `DONE — verified
  2026-09-20` with a line saying what was run, and **leave the block in place**, as that file's own
  banner requires.
- Several items on that list are built but marked "not yet seen working by Rajeev". They need his
  eyes, not another build.

---

## 4. Two things a session should know before touching staging

**Staging holds a seeded temple, and it is the demo.** 44 curated recipes, 111 store items, 31
purchase orders, 36 invoices, 95 meals over 29 days, 17 volunteers with attendance history, 24 gifts.
`tools/seed/README.md` explains how it was made. Do not re-run a phase against it without reading
that file; several phases are only re-runnable through their own ledger.

**The three SQL-only jobs still exist** on `iskcon-kms-2026`: Cloud Run jobs `kms-day1-reset`,
`kms-backdate`, `kms-volunteer-history`, each reading its script from a secret of the same name.
A subagent is refused `gcloud secrets create` and `gcloud run jobs create`; the conductor can run
them. **Delete all three when the seeded data is signed off** — they are loaded guns.

---

## 5. Working rules that were added on 2026-09-20 and are easy to miss

Both are at the top of `CLAUDE.md` and both came from Rajeev:

- **An hour a task, two at the absolute maximum.** Past that, question the worker; kill it if the
  answer is vague, unless killing it breaks something genuinely hard to repair. The wave apparatus —
  work-manager, path contracts, merged-tree runs — is for a feature. A small fix goes to one builder,
  or the conductor does it. A seven-hour one-word heading change is what produced this rule.
- **When you remove a screen, a card or an endpoint, grep the docs for its name in the same change.**
  Three waves in a row left `docs/uat/` and `docs/stories/` describing screens that no longer exist.

---

## 6. One stale report, checked, nothing to do

A builder from the Epic 12 wave (T-356, job card per kitchen) handed back its report a day late — its
wait-loops had been left running and only surfaced when the session's leftover processes were killed.
Its work is **already on main and live**: `V152__card_version_is_per_kitchen.sql` and
`JobCardPerKitchenIT` are both in the tree, and Flyway on staging is past them at v159. The two
`WhichKitchenMigrationIT` failures it flagged were closed before that release; the suite is green.

It named three things it did not do, which are still true and are candidates rather than defects: the
job card lists the whole temple's roster rather than the kitchen's own staff; the card says "Planned
crew" where the screens say "People needed" (changing it rewrites every stored card fingerprint); and
`documents.kitchen_id` is not shown in the documents list or in the download filename.
