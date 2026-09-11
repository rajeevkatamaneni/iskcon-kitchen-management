"use client";

import { useId, useState, type ReactNode } from "react";
import { Button } from "@/components/ds/Button";
import { HintedField } from "@/components/ds/InfoHint";
import {
  TABLE, THEAD, TR, TH_TEXT, TH_NUM, TH_ACTIONS, TD_TEXT, TD_NUM, TD_ACTIONS, WRAP,
} from "@/components/ds/table";
import { FOOD_UNITS, leadTimeWarning, unitLabel } from "@/lib/format";
import type { IngredientView, PoLineInput } from "@/lib/api";

/**
 * The purchase-order edit form: one needed-by date for the whole order, the lines and their
 * quantities, a Remove on each, and two ways to add a line — an ingredient out of the catalogue, or
 * something the catalogue has never heard of.
 *
 * <p><strong>It is mounted in two places and it exists once (T-134).</strong> Rajeev, 2026-09-10,
 * dictating the ordering journey he wants (D-24 §6): pressing a vendor tile's "Generate purchase
 * order" on the shopping list opens "the purchase-order edit screen as a panel over the shopping
 * list", and — the sentence that governs the build — <em>"The panel and the edit screen are the
 * same thing. If they are built twice they will drift."</em> So this file was lifted out of
 * {@code /orders/[id]} rather than copied into the shopping list, and both screens render it:
 *
 * <ul>
 * <li><b>/orders/[id]</b> edits a draft that already exists. Saving is a PUT.</li>
 * <li><b>/shopping-list</b> builds one that does not exist yet. Saving is a POST, and the order is
 * created by that press and not before it.</li>
 * </ul>
 *
 * <p><strong>The two differences between those mounts are both handed in, and neither is a mode
 * flag.</strong> What the host does with the finished draft is {@code onSave}; the words that can
 * only be written by the host — a heading, the sentence under it, and the two refusals that name a
 * way out that differs by screen — are {@code words}. There is deliberately no {@code mode} prop:
 * the moment this component can ask which screen it is on, the two screens can be made to differ,
 * which is the thing that was ruled against.
 *
 * <p><strong>What is NOT here, and why that is right.</strong> "Cancel this purchase order" — the
 * reason box and the never-delivered tick — stays on {@code /orders/[id]}, at the foot of the page,
 * where T-135 put it. It is not part of this form: it is shown in view mode as well, and it is
 * gated on the order having a purchase-order number rather than on which screen is rendering it.
 * That gate answers itself in the panel, where there is no order and so no number, which is exactly
 * what Rajeev asked for — <em>"The Cancel this PO control must not appear in that panel... Show it
 * only when there is a purchase-order number."</em>
 *
 * <p><strong>It owns its own working copy.</strong> The lines and the date are seeded from props
 * once, on mount, and nothing is written back until Save — so abandoning an edit costs nothing, and
 * the host has no draft state to keep in step. Hosts mount it when editing starts and unmount it
 * when editing ends, which is what makes the seeding safe: there is no second render to re-seed
 * from, and therefore no half-typed quantity that a reload of the parent could overwrite.
 */

/**
 * A line as it is being edited. The quantity is held as the text in the box rather than a number so
 * a person can clear the field and retype it; it becomes a number once, on save.
 *
 * <p>`ingredientId` and `description` are exclusive, exactly as they are on the server: a line
 * either names a catalogue ingredient or describes something that is not in it. `subjectOf` is how
 * either is read for display.
 *
 * <p>`key` is a React key and nothing else. It is never sent: the update endpoint replaces a
 * draft's lines wholesale, so a line has no identity across a save. It exists because the table
 * used to be keyed on `ingredientId`, which collides the moment two lines are described and both
 * carry null — React would then reuse one row's DOM for the other and the quantity typed into one
 * box would appear in the wrong row. An existing line uses its own server id; a line just added
 * gets a fresh uuid.
 */
export interface PurchaseOrderDraftLine {
  key: string;
  ingredientId: string | null;
  ingredientName: string | null;
  description: string | null;
  quantity: string;
  unit: string;
  expectedPrice: number | null;
}

/** A finished draft, in the shape both endpoints take. */
export interface PurchaseOrderDraft {
  /** "" in the box means a date deliberately cleared, which is a real order with nothing to meet. */
  neededBy: string | null;
  lines: PoLineInput[];
}

/**
 * The words only the host can write.
 *
 * <p>Grouped into one prop rather than spread across five, because they belong together: they are
 * the whole of what one mount says differently from the other, and a reader comparing the two call
 * sites can see all of it at once.
 */
export interface PurchaseOrderEditorWords {
  /** The heading above the form. */
  heading: string;
  /** The form's accessible name. Not the heading: screens name the act, headings name the thing. */
  formLabel: string;
  /** The sentence under the heading, or nothing. */
  intro?: ReactNode;
  /** Refusing an order with no lines on it. It names the way out, which differs by screen. */
  emptyOrder: string;
  /** Refusing a needed-by date behind `minNeededBy`. */
  dateBeforeFloor: string;
}

/**
 * What a line is for, in words — the catalogue name, or the description when there is no catalogue
 * entry (T-024).
 *
 * <p>Never `l.ingredientName` on its own. A described line's name is null, and null renders as
 * nothing at all in JSX: the row would keep its quantity and its price and lose the one column that
 * says what is being bought, with no error anywhere. The `?? ""` at the end is unreachable — the
 * database CHECK guarantees one of the two is set — and is there because TypeScript cannot know
 * that and a crash on a screen is worse than an empty cell.
 *
 * <p>Exported because the order screen prints the same subject in three other tables, and two
 * copies of this rule is how one of them comes to print an empty cell for a described line.
 */
export function subjectOf(l: { ingredientName: string | null; description: string | null }): string {
  return l.ingredientName ?? l.description ?? "";
}

export function PurchaseOrderEditor({
  words,
  initialLines,
  initialNeededBy,
  minNeededBy,
  leadTimeDays = null,
  vendorName = null,
  ingredients,
  busy,
  onSave,
  onCancel,
  onRefuse,
}: {
  words: PurchaseOrderEditorWords;
  /** Seeded once, on mount. */
  initialLines: PurchaseOrderDraftLine[];
  /** "" where there is no date yet. */
  initialNeededBy: string;
  /**
   * The earliest day the order may be wanted: the day the order was raised on an existing draft,
   * and the temple's today on one that does not exist yet. The server refuses anything behind it
   * (KMS-400014) and this is the same refusal said where the person is looking.
   */
  minNeededBy: string;
  /**
   * What this order's vendor asked to be given, in days, or null where nobody has said (T-137).
   *
   * <p>A lead time is agreed at onboarding and it is the vendor's own number — Rajeev: *"they might
   * say, we are good with 1 day lead time BUT we want to be safe than sorry so we need 3 days
   * notice"* — so it is worth saying out loud to whoever is choosing the date.
   *
   * <p><strong>What this component deliberately does not do with it is arithmetic.</strong> It does
   * not work out an order-by date, does not decide which of the three zones today is in, and does
   * not refuse anything. All of that is the server's, once, on Mark sent; a second copy of the sum
   * living in a form is exactly how the planner, the order screen and the dashboard come to give
   * three answers about one order. This prints a fact and stops.
   *
   * <p>Null on the shopping-list panel, where the order does not exist yet and so has no vendor
   * lead time to read.
   */
  leadTimeDays?: number | null;
  /** Whose promise it is. Only used beside `leadTimeDays`, and null where that is. */
  vendorName?: string | null;
  ingredients: IngredientView[];
  busy: boolean;
  /** The host writes the draft to the server and decides what happens next. */
  onSave: (draft: PurchaseOrderDraft) => void | Promise<void>;
  onCancel: () => void;
  /**
   * A refusal decided here, before the network. It goes to the host's own error notice rather than
   * being rendered inside this card: on the order screen that notice sits above the form with the
   * field errors the server sends back, and in the panel it sits inside the panel — two right
   * answers that only the host knows.
   */
  onRefuse: (message: string) => void;
}) {
  const [lines, setLines] = useState<PurchaseOrderDraftLine[]>(initialLines);
  const [neededBy, setNeededBy] = useState(initialNeededBy);
  // Generated rather than written out, because the heading is what names the section and two of
  // these could yet be rendered on one page by a third caller.
  const headingId = useId();

  // Advisory only, and recomputed as the date is typed. A date inside the notice THIS vendor asked
  // for is a thing worth saying out loud and not a thing worth refusing — see leadTimeWarning.
  //
  // `leadTimeDays` is the vendor's own figure, sent by the server. Until T-137 this compared every
  // date against a hard-coded two days, which was a second answer to the question T-137 exists to
  // make single: the form would say "2 days" on a screen whose Mark sent had just been refused
  // against five. Null means nobody has recorded a lead time for this vendor, which is silence.
  const neededByWarning = neededBy === "" ? null : leadTimeWarning(neededBy, leadTimeDays);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    // An order with nothing on it is not an empty order, it is a cancelled one — and this is where
    // that is said, in words, rather than by greying the last Remove button (T-135).
    if (lines.length === 0) {
      onRefuse(words.emptyOrder);
      return;
    }

    const quantities = lines.map((l) => Number(l.quantity));
    if (quantities.some((q) => !Number.isFinite(q) || q <= 0)) {
      onRefuse("Every line needs a quantity above zero. Remove a line you no longer want.");
      return;
    }

    // The one thing about this date that is refused rather than warned about, mirrored from the
    // server's KMS-400014 so the refusal arrives before the round trip rather than after it. The
    // server is still the guard; this only saves a wasted submit.
    if (neededBy !== "" && neededBy < minNeededBy) {
      onRefuse(words.dateBeforeFloor);
      return;
    }

    await onSave({
      neededBy: neededBy === "" ? null : neededBy,
      // Both halves of the subject travel, always. `description` is required-and-nullable on
      // PoLineInput rather than optional precisely so that this object literal cannot quietly
      // omit it — an omitted optional field is exempt from the excess-property check when it is
      // spread, arrives as undefined, and would turn every described line back into a line with
      // no subject at all, which the server then refuses with KMS-400128.
      lines: lines.map((l, i) => ({
        ingredientId: l.ingredientId,
        description: l.description,
        quantity: quantities[i],
        unit: l.unit,
        expectedPrice: l.expectedPrice,
      })),
    });
  }

  return (
    <section className="card mb-6 px-6 py-5" aria-labelledby={headingId}>
      <h2 id={headingId} className="text-lg">{words.heading}</h2>
      {words.intro && (
        <p className="mt-1 max-w-prose text-sm text-ink-secondary">{words.intro}</p>
      )}
      <form className="mt-4" aria-label={words.formLabel} onSubmit={save}>
        {/* The standing advice — that the date may be left off — is the "i" beside the label. The
            warning underneath is not: it is recomputed as the date is typed and is about the day
            actually in the box, so it has to be on the screen rather than behind a press. */}
        <div className="mb-5 flex max-w-xs flex-col gap-1">
          <HintedField label="Needed by" hint="Leave it blank if there is no date to meet">
            {/* min is the floor the server measures against, so the picker itself will not offer a
                day behind it. The server refuses it regardless (KMS-400014): a browser attribute is
                a courtesy, not a guard. */}
            {(fieldId) => (
              <input
                id={fieldId}
                type="date"
                value={neededBy}
                min={minNeededBy}
                onChange={(e) => setNeededBy(e.target.value)}
                className="min-h-touch rounded-control border border-hairline px-3"
              />
            )}
          </HintedField>
          {neededByWarning && (
            <span className="pl-field-inset text-sm text-warning">{neededByWarning}</span>
          )}
          {/* The vendor's own promise, said where the date is being chosen (T-137, D-25). Not a
              warning and not a gate: where this order stands against that promise is decided on
              the server when it is sent, and is shown on the order screen.

              Hidden while the warning above is showing, because that sentence already names the
              same number — "Sooner than the 5 days' notice this vendor asked for" — and printing
              both would say one fact twice in two shapes. */}
          {leadTimeDays != null && neededByWarning === null && (
            <span className="pl-field-inset text-sm text-ink-secondary">
              {vendorName ?? "This vendor"} asked for{" "}
              {leadTimeDays === 0
                ? "no notice — they are a walk-in supplier"
                : leadTimeDays === 1
                  ? "1 day’s notice"
                  : `${leadTimeDays} days’ notice`}
              .
            </span>
          )}
        </div>
        <table className={`${TABLE} text-sm`}>
          <thead className={THEAD}>
            <tr>
              <th className={`${TH_TEXT} ${WRAP}`}>Item</th>
              <th className={TH_NUM}>Quantity</th>
              <th className={TH_ACTIONS}>Remove</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={l.key} className={TR}>
                <td className={`${TD_TEXT} ${WRAP}`}>{subjectOf(l)}</td>
                <td className={TD_NUM}>
                  <input
                    type="number"
                    min="0"
                    step="any"
                    value={l.quantity}
                    aria-label={`Quantity of ${subjectOf(l)}`}
                    onChange={(e) => setLines((cur) => cur.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))}
                    className="w-28 rounded-control border border-hairline px-2 py-1 tabular-nums"
                  />{" "}
                  {/* The bare label, never a promoted one: the box beside it holds and submits the
                      line's own stored unit, so a readout that said "gm" over a figure in kilograms
                      would invite a thousandfold error. */}
                  <span className="text-ink-secondary">{unitLabel(l.unit)}</span>
                </td>
                <td className={TD_ACTIONS}>
                  {/*
                    Rajeev, 2026-09-10, driving the deployed app: the Remove button is "washed out —
                    fix the styling" so that it reads as an available control. Three things were
                    making it look unavailable, and only one of them was a colour.

                    It was disabled whenever the draft was down to its last line, which dims it to
                    45% and says nothing about why. An order with nothing on it really is a
                    cancellation rather than an empty order — but there is nowhere on a greyed
                    button to put the sentence that would explain it, so the button stays live and
                    `save` refuses an empty order in words.

                    `variant="ghost"` is the design system's neutral second action — a solid pane
                    with a resting border, full-strength ink — and it is already what the identical
                    Remove on /orders/new/lines uses. Danger was the wrong material as well as the
                    paler one: a line taken off a working copy that has not been saved destroys
                    nothing.

                    And `type="button"`, which was missing. A <button> inside a <form> defaults to
                    type="submit", so pressing Remove both dropped the line and submitted the form —
                    and because React had not yet applied the state update, it saved the order with
                    the line still on it.
                  */}
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    aria-label={`Remove ${subjectOf(l)}`}
                    onClick={() => setLines((cur) => cur.filter((_, j) => j !== i))}
                  >
                    Remove
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <AddLine
          busy={busy}
          ingredients={ingredients}
          alreadyOnOrder={lines.map((l) => l.ingredientId).filter((x): x is string => x !== null)}
          onAdd={(line) => setLines((cur) => [...cur, line])}
        />

        {/*
          Two buttons, and these are them (D-24 §4). Rajeev, 2026-09-10: "Why do we need all the
          other buttons in edit mode?" — so the host's own bank of buttons is not rendered at all
          while this form is open, and what is left is Save and Cancel.

          They are the same two words in the panel, deliberately. The panel creates the order rather
          than updating one, and a "Create" button there would be the first crack in "the panel and
          the edit screen are the same thing".

          A real button rather than the underlined text "Discard" it replaces: the second of two
          actions is still an action, and a line of text that turns out to be pressable is the shape
          Rajeev objected to on the calendar screen.
        */}
        <div className="mt-5 flex items-center gap-3">
          <button type="submit" disabled={busy} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">Save</button>
          <Button type="button" variant="ghost" disabled={busy} onClick={onCancel}>Cancel</Button>
        </div>
      </form>
    </section>
  );
}

/**
 * Adding a line to a draft — either an ingredient from the catalogue, or something that is not in
 * it at all.
 *
 * <p>A picker rather than a box to paste an identifier into: nobody knows an ingredient by its id,
 * and the vendor page and the invoice form both choose one this way already.
 *
 * <p><strong>The second half is what T-024 adds, and it is the only place in the application where
 * a described line can be created.</strong> Four plastic stools from a furniture shop are bought on
 * a purchase order like anything else, and until now the only way to put them on one was to invent
 * an `ingredients` row — which then appeared in the recipe picker, in the low-stock job and on the
 * ingredients screen for ever. So: a description, a quantity's unit, and nothing else. It gets no
 * expected price for the same reason the ingredient half gets none.
 *
 * <p>Two controls rather than one combined box, because the two are genuinely different acts and
 * the server treats them as exclusive. Pressing either "Add" adds one line; neither offers the
 * other's field, so a line with both — which the server refuses with KMS-400128 — cannot be built
 * here by accident.
 */
function AddLine({
  busy, ingredients, alreadyOnOrder, onAdd,
}: {
  busy: boolean;
  ingredients: IngredientView[];
  alreadyOnOrder: string[];
  onAdd: (line: PurchaseOrderDraftLine) => void;
}) {
  const [chosen, setChosen] = useState("");
  const [described, setDescribed] = useState("");
  const [describedUnit, setDescribedUnit] = useState("PIECES");

  // An ingredient already on the order is edited on its own row; offering it twice would produce
  // two lines for one thing and leave the vendor to work out which is meant. Described lines are
  // deliberately not de-duplicated this way: "Extension cord" twice may well be two different
  // things, and there is no id to say otherwise.
  const available = ingredients.filter((i) => !alreadyOnOrder.includes(i.id));

  function add() {
    const ingredient = available.find((i) => i.id === chosen);
    if (!ingredient) return;
    // No expected price: that figure is the vendor's last-known price, and the client does not know
    // it. The server fills it in from `vendor_supplies` when the order is created, so a line added
    // here carries the same price a generated one would (T-134); on an edit there is nothing to
    // fill it from and the sheet prints a dash, which is truthful.
    onAdd({
      key: crypto.randomUUID(),
      ingredientId: ingredient.id,
      ingredientName: ingredient.name,
      description: null,
      quantity: "",
      unit: ingredient.unit,
      expectedPrice: null,
    });
    setChosen("");
  }

  function addDescribed() {
    const text = described.trim();
    if (text === "") return;
    onAdd({
      key: crypto.randomUUID(),
      ingredientId: null,
      ingredientName: null,
      description: text,
      quantity: "",
      unit: describedUnit,
      expectedPrice: null,
    });
    setDescribed("");
  }

  return (
    <div className="mt-4 grid gap-4 border-t border-hairline pt-4">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Add an ingredient</span>
          <select
            value={chosen}
            onChange={(e) => setChosen(e.target.value)}
            className="min-h-touch rounded-control border border-hairline px-3"
          >
            <option value="">Choose…</option>
            {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
          </select>
        </label>
        <button
          type="button"
          disabled={busy || chosen === ""}
          onClick={add}
          className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
        >
          Add line
        </button>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        {/* The hint says what this is for and, more usefully, what it costs: a described line is
            never taken into stock, which is the whole reason it does not need a catalogue entry.
            Saying so here is cheaper than saying it at the receiving table, where somebody has
            already gone looking for a box to type into. */}
        {/* Rajeev, 2026-09-10 (D-24 §4): "Or describe something not in the catalogue" becomes "An
            item not in the catalogue". The old label described the act of typing; this one names
            the thing being added, which is what the person is looking for. */}
        <HintedField
          label="An item not in the catalogue"
          hint="For things the store room doesn’t track — a plastic stool, an extension cord. It goes on the order and the bill, but never into stock."
        >
          {(fieldId) => (
            <input
              id={fieldId}
              value={described}
              maxLength={200}
              placeholder="Plastic stool"
              onChange={(e) => setDescribed(e.target.value)}
              className="min-h-touch w-64 rounded-control border border-hairline px-3"
            />
          )}
        </HintedField>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Counted in</span>
          {/* FOOD_UNITS, in its own order, and never a list typed out here. E11-S2's one
              vocabulary: six screens each used to carry their own copy of the five unit names, so
              adding a unit meant finding all six and forgetting one meant a dropdown that silently
              offered less than its neighbours. This box was briefly a seventh, with the five
              reordered to put pieces first — which is where almost everything reaching it lands.
              That preference is expressed by the initial value instead, which costs nothing and
              leaves the vocabulary reading the same here as on every other screen.

              All five rather than PIECES alone: a described line might be twenty litres of floor
              cleaner, and the column's CHECK admits the same five whatever the line's subject. */}
          <select
            value={describedUnit}
            onChange={(e) => setDescribedUnit(e.target.value)}
            className="min-h-touch rounded-control border border-hairline px-3"
          >
            {FOOD_UNITS.map((u) => (
              <option key={u} value={u}>{unitLabel(u)}</option>
            ))}
          </select>
        </label>
        <button
          type="button"
          disabled={busy || described.trim() === ""}
          onClick={addDescribed}
          className="min-h-touch rounded border border-hairline px-4 transition-colors duration-state hover:bg-sunken disabled:opacity-60"
        >
          Add described line
        </button>
      </div>
    </div>
  );
}
