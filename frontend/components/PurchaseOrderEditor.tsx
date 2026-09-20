"use client";

import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { HintedField } from "@/components/ds/InfoHint";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM, TH_ACTIONS_FIXED, TD_ACTIONS_FIXED } from "@/components/ds/table";
import { FOOD_UNITS, leadTimeWarning, quantity, stepForUnit, unitLabel } from "@/lib/format";
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
 * Brings the refusal a failed save has just put on the screen into view, and moves focus to it
 * (T-304).
 *
 * <p>Measured on a phone (390) before this: the order page draws its refusals at the top of the
 * page, and Save is at the foot of this form, so pressing Save on an empty order changed nothing
 * that could be seen. The refusal was 1,000px or more above the viewport. The same is true of the
 * shopping list's panel, and of Create a purchase order once somebody has scrolled down to its
 * items. Two ways to fix it were weighed:
 *
 * <ul>
 *   <li><b>Put the refusal beside Save.</b> Right for the refusals this form makes itself, but a
 *       refusal from the server on the same press ("this order was sent from another screen", a
 *       line the server names) is drawn by the host, at the top, with the field errors it sends,
 *       and would still be off-screen. And Create a purchase order has no button at the foot to put
 *       it beside: its Create order sits in the sticky header.</li>
 *   <li><b>Take the person to the refusal, wherever the screen draws it.</b> One behaviour for every
 *       refusal, local or from the server, on all three screens, and the screen reader hears the
 *       refusal read out because focus lands on it. Chosen.</li>
 * </ul>
 *
 * <p>It looks for the first visible `role="alert"` in `scope` (the panel on the shopping list, the
 * page elsewhere): every refusal and ErrorNotice in the application is drawn with that role. It
 * waits a frame, and a few more if it has to, because the host draws the notice on its next render,
 * after the save that caused it has returned. Centred rather than scrolled to the top, so a sticky
 * header never covers it. The notice takes `tabindex="-1"` so it can hold focus without becoming a
 * stop when somebody tabs through the page.
 */
export function revealNotice(scope: ParentNode = document, stillWanted: () => boolean = () => true): void {
  if (typeof requestAnimationFrame !== "function") return;
  let tries = 0;
  const look = () => {
    // A save that succeeded closes the form on the host's next render: nothing to show then.
    if (!stillWanted()) return;
    const notice = Array.from(scope.querySelectorAll<HTMLElement>('[role="alert"]')).find(
      // Next.js's own announcer is an alert too, and is never the thing to show.
      // `checkVisibility` skips one inside something hidden; a browser without it, and jsdom, take
      // the first.
      (el) => el.id !== "__next-route-announcer__" && (el.checkVisibility?.() ?? true)
    );
    if (!notice) {
      if (++tries < 10) requestAnimationFrame(look);
      return;
    }
    notice.scrollIntoView?.({ block: "center" });
    if (!notice.hasAttribute("tabindex")) notice.setAttribute("tabindex", "-1");
    notice.focus({ preventScroll: true });
  };
  requestAnimationFrame(look);
}

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
  /**
   * The pack this line is ordered in — "4 × Bag (25 Kg)" (R-SL-3, T-264) — or absent.
   *
   * <p><strong>When it is set, `quantity` is the number of packs, not an amount.</strong> The box
   * then reads "4" beside "× Bag (25 Kg)", because that is what the vendor is being asked for, and
   * the stock-unit amount it comes to (100 Kg) is printed under it rather than typed. On save the
   * line goes out with `packSizeId` and `packCount`, and `quantity` = count × `perPackQty` in
   * `unit`, which is what the server stores for stock and costing (T-260).
   *
   * <p>Optional, and additive on purpose: the shopping-list panel is the only host that sets it
   * today. A host that never sets it — the order screen — gets exactly the form it had, and the
   * lines it saves carry no pack keys at all.
   */
  pack?: { packSizeId: string; label: string; perPackQty: number } | null;
  /**
   * What the vendor's list price is, in words, printed under the item — "List price ₹1,500 / bag ·
   * ₹60 / Kg" (T-264). Written by the host, which is the one that read the vendor's supplies, and
   * absent where there is nothing to say. Optional for the same reason `pack` is.
   */
  priceNote?: string | null;
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

  // Where a refusal is looked for once a save fails (see revealNotice): inside the panel when this
  // form is in one, so a notice on the page behind it is never the one chosen; the page otherwise.
  const self = useRef<HTMLElement>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const reveal = () =>
    revealNotice(self.current?.closest('[role="dialog"]') ?? document, () => mounted.current);
  const refuse = (message: string) => {
    onRefuse(message);
    reveal();
  };

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    // An order with nothing on it is not an empty order, it is a cancelled one — and this is where
    // that is said, in words, rather than by greying the last Remove button (T-135).
    if (lines.length === 0) {
      refuse(words.emptyOrder);
      return;
    }

    const quantities = lines.map((l) => Number(l.quantity));
    if (quantities.some((q) => !Number.isFinite(q) || q <= 0)) {
      refuse("Every line needs a quantity above zero. Remove a line you no longer want.");
      return;
    }
    // A vendor sells whole bags. Only a line ordered in a pack is asked this, so a host that never
    // sets `pack` never sees the sentence (T-264).
    if (lines.some((l, i) => l.pack && !Number.isInteger(quantities[i]))) {
      refuse("Order whole packs. Change the number of packs to a whole number.");
      return;
    }

    // The one thing about this date that is refused rather than warned about, mirrored from the
    // server's KMS-400014 so the refusal arrives before the round trip rather than after it. The
    // server is still the guard; this only saves a wasted submit.
    if (neededBy !== "" && neededBy < minNeededBy) {
      refuse(words.dateBeforeFloor);
      return;
    }

    await onSave({
      neededBy: neededBy === "" ? null : neededBy,
      // Both halves of the subject travel, always. `description` is required-and-nullable on
      // PoLineInput rather than optional precisely so that this object literal cannot quietly
      // omit it — an omitted optional field is exempt from the excess-property check when it is
      // spread, arrives as undefined, and would turn every described line back into a line with
      // no subject at all, which the server then refuses with KMS-400128.
      //
      // A pack line adds its two keys and states its amount as packs × pack size. The server takes
      // the pack as the truth and would store that product whatever was sent beside it (T-260), so
      // sending the same product keeps the request honest rather than relying on that.
      lines: lines.map((l, i) =>
        l.pack
          ? {
              ingredientId: l.ingredientId,
              description: l.description,
              quantity: Number((quantities[i] * l.pack.perPackQty).toFixed(6)),
              unit: l.unit,
              expectedPrice: l.expectedPrice,
              packSizeId: l.pack.packSizeId,
              packCount: quantities[i],
            }
          : {
              ingredientId: l.ingredientId,
              description: l.description,
              quantity: quantities[i],
              unit: l.unit,
              expectedPrice: l.expectedPrice,
            }
      ),
    });
    // Both hosts close this form when the save succeeds. Still here, it failed, and the host has
    // drawn why: the server's refusal, at the top of the page or the panel. (The close may not have
    // been drawn yet when the save returns, which is why revealNotice asks again each frame.)
    if (mounted.current) reveal();
  }

  return (
    <section ref={self} className="card mb-6 px-6 py-5" aria-labelledby={headingId}>
      <h2 id={headingId} className="text-lg">{words.heading}</h2>
      {words.intro && (
        <p className="mt-1 max-w-prose text-sm text-ink-secondary">{words.intro}</p>
      )}
      <Form className="mt-4" aria-label={words.formLabel} onSubmit={save}>
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
                ? "no notice — they are a walk-in vendor"
                : leadTimeDays === 1
                  ? "1 day’s notice"
                  : `${leadTimeDays} days’ notice`}
              .
            </span>
          )}
        </div>
        <table className={`${RULED_TABLE} text-sm`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Item</th>
              <th className={TH_FIXED}>Quantity</th>
              <th className={TH_ACTIONS_FIXED}><span className="sr-only">Remove</span></th>
            </tr>
          </thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={l.key} className={TR}>
                <td className={TD_PRIMARY}>
                  {subjectOf(l)}
                  {l.priceNote && (
                    <span className="block text-xs text-ink-muted">{l.priceNote}</span>
                  )}
                </td>
                <td className={TD_FIXED_NUM}>
                  <input
                    type="number"
                    min="0"
                    // Whole either way it can be whole: a pack line counts bags, and a plain
                    // line in a counted unit counts the things themselves (T-424).
                    step={l.pack ? "1" : stepForUnit(l.unit)}
                    value={l.quantity}
                    // A pack line's box holds a count, and there can be two lines for one ingredient
                    // when sizes are mixed, so its name says which pack it counts.
                    aria-label={l.pack ? `Quantity of ${subjectOf(l)}, in ${l.pack.label}` : `Quantity of ${subjectOf(l)}`}
                    onChange={(e) => setLines((cur) => cur.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))}
                    className={`${l.pack ? "w-20" : "min-w-28"} rounded-control border border-hairline px-2 py-1 tabular-nums`}
                  />{" "}
                  {l.pack ? (
                    <>
                      <span className="text-ink-secondary">× {l.pack.label}</span>
                      {/* The stock-unit amount the packs come to (R-SL-3): what goes into stock and
                          costing. Said, not typed — the count above is what decides it. */}
                      {Number(l.quantity) > 0 && (
                        <span className="block text-xs text-ink-muted">
                          = {quantity(Number(l.quantity) * l.pack.perPackQty, l.unit)}
                        </span>
                      )}
                    </>
                  ) : (
                    // The bare label, never a promoted one: the box beside it holds and submits the
                    // line's own unit, so a readout that said "gm" over a figure in kilograms would
                    // invite a thousandfold error. A host that wants "3 Kg" rather than "3000 gm"
                    // hands the line over in Kg (the shopping list does, T-264).
                    <span className="text-ink-secondary">{unitLabel(l.unit)}</span>
                  )}
                </td>
                <td className={TD_ACTIONS_FIXED}>
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
                    aria-label={l.pack ? `Remove ${subjectOf(l)}, ${l.pack.label}` : `Remove ${subjectOf(l)}`}
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
      </Form>
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
    /*
      T-269: nothing in here may be wider than the card. A select is as wide as its longest option,
      so a catalogue holding "Sri Lakshmi cold-pressed groundnut oil" made "Add an ingredient" wider
      than the card on a 390px phone, and the 256px box for an item not in the catalogue did the
      same inside the shopping list's narrower panel: measured 8px past the card there (T-264 saw
      14px with its data). `[&>*]:max-w-full` caps each field at the width of its row and
      `max-w-full` on the control caps it at its field, so on a phone a field is at most the card's
      width and the text in a long option is cut by the select's own box, as every select does.
      `grid-cols-1` is what makes those caps bite: it is `minmax(0, 1fr)`, where the default grid
      column is as wide as the widest thing in it, so the select would widen the column and the
      rows with it. On a wide screen none of this is reached and nothing moves.
    */
    <div className="mt-4 grid grid-cols-1 gap-4 border-t border-hairline pt-4">
      <div className="flex flex-wrap items-end gap-3 [&>*]:max-w-full">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Add an ingredient</span>
          <select
            value={chosen}
            onChange={(e) => setChosen(e.target.value)}
            className="min-h-touch max-w-full rounded-control border border-hairline px-3"
          >
            <option value="">Choose…</option>
            {available.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
          </select>
        </label>
        <Button variant="secondary" disabled={busy || chosen === ""} onClick={add}>
          Add line
        </Button>
      </div>

      <div className="flex flex-wrap items-end gap-3 [&>*]:max-w-full">
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
              className="min-h-touch w-64 max-w-full rounded-control border border-hairline px-3"
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
        <Button variant="secondary" disabled={busy || described.trim() === ""} onClick={addDescribed}>
          Add described line
        </Button>
      </div>
    </div>
  );
}
