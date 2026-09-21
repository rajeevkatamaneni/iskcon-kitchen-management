"use client";

import Link from "next/link";
import { useId, useState } from "react";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { HintedField } from "@/components/ds/InfoHint";
import { ErrorNotice } from "@/components/ErrorNotice";
import { PriceTrend } from "@/components/PriceTrend";
import {
  api,
  toApiError,
  type ApiError,
  type IngredientSupplyView,
  type IngredientView,
  type PreferredVendorView,
  type SetVendorSupplyInput,
  type VendorView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { unitLabel } from "@/lib/format";
import {
  ACTIONS_ROW, RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED, TD_FIXED_NUM,
  TH_ACTIONS_FIXED, TD_ACTIONS_FIXED,
} from "@/components/ds/table";
import {
  BOX,
  LEAD_TIME_HINT,
  PriceBox,
  ReplacesText,
  SellsAsSelect,
  STOCK_UNIT,
  boxValue,
  listPriceText,
  previousPriceText,
  supplyInput,
} from "@/components/ingredient/supply";

/**
 * The ingredient's vendors, seen from the ingredient (R-ING-2), and the dropdown that links another.
 *
 * <p><strong>The same row as the vendor page, both ways.</strong> The table has the vendor page's
 * Supplies columns — Sells it as, List price with its trend arrow, Lead time, Preferred — and its Edit
 * sends what that page's Edit sends (`setVendorSupply`, all six keys). Linking a vendor sends what that
 * page's Other ingredients → Save sends (`addVendorSupplies` with one row), so a price typed here lands
 * in the price history exactly as one typed there does. The conductor ruled on 2026-09-19 that Sells it
 * as and the price per pack come across too, since R-ING-2 creates "the same vendor_supplies row".
 *
 * <p><strong>The dropdown offers only active vendors who do not supply this yet</strong> (the
 * conductor, 2026-09-19). A vendor who already does is edited in the table, never overwritten from the
 * dropdown: the server writes the whole row, so a link made over an existing one would wipe its pack,
 * price and lead time with whatever the empty form held.
 *
 * <p>Everything here is behind `MANAGE_VENDORS`, which every endpoint it calls declares. The page does
 * not render this section for anyone without it.
 */
export function IngredientVendors({
  ingredient,
  supplies,
  vendors,
  preferred,
  onChanged,
}: {
  ingredient: IngredientView;
  supplies: IngredientSupplyView[];
  /** Active vendors, from `listVendors(true)`. */
  vendors: VendorView[];
  preferred: PreferredVendorView[];
  onChanged: () => void;
}) {
  const { getToken } = useAuth();
  const [editing, setEditing] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  /** The vendor that holds the preference for this ingredient now, unless it is `vendorId` itself. */
  function heldElsewhere(vendorId: string | null): string | null {
    const holder = preferred.find((p) => p.ingredientId === ingredient.id);
    return holder && holder.vendorId !== vendorId ? holder.vendorName : null;
  }

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setError(null);
    try {
      await mutation(await getToken());
      onChanged();
      return true;
    } catch (e) {
      setError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  const linked = new Set(supplies.map((s) => s.vendorId));
  const linkable = vendors.filter((v) => v.active && !linked.has(v.id)).sort((a, b) => a.name.localeCompare(b.name));

  return (
    <section className="card px-6 py-5" aria-labelledby="vendors-heading">
      <h2 id="vendors-heading" className="text-lg font-semibold text-ink">Vendors</h2>
      {/* The vendor page's sentence about the tick, said the same way here because it is the same tick. */}
      <p className="mt-1 max-w-prose text-sm text-ink-secondary">
        Only one vendor can be preferred for an ingredient, so ticking Preferred takes it from whichever
        vendor holds it now.
      </p>

      {error && <div className="mt-4"><ErrorNotice error={error} /></div>}

      {supplies.length === 0 ? (
        <p className="mt-4 text-sm text-ink-secondary">No vendor sells this yet. Link one below.</p>
      ) : (
        <table className={`${RULED_TABLE} mt-4`}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Vendor</th>
              <th className={TH_FIXED}>Sells it as</th>
              <th className={TH_FIXED}>List price</th>
              <th className={TH_FIXED}>Lead time</th>
              <th className={TH_FIXED}>Preferred</th>
              <th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {supplies.map((s) =>
              editing === s.vendorId ? (
                <SupplyEditRow
                  key={s.vendorId}
                  supply={s}
                  ingredient={ingredient}
                  heldBy={heldElsewhere(s.vendorId)}
                  busy={busy}
                  onCancel={() => setEditing(null)}
                  onSave={async (input) => {
                    const ok = await run((t) => api.setVendorSupply(s.vendorId, input, t), "We couldn’t save that supply.");
                    if (ok) setEditing(null);
                  }}
                />
              ) : (
                <tr key={s.vendorId} className={TR}>
                  <td className={TD_PRIMARY}>
                    <Link href={`/vendors/${s.vendorId}`} className="link">
                      {s.vendorName}
                    </Link>
                  </td>
                  <td className={TD_FIXED} data-label="Sells it as">{s.packLabel ?? unitLabel(s.unit)}</td>
                  <td className={TD_FIXED_NUM} data-label="List price">
                    <span className="inline-flex items-center gap-2">
                      <span>{listPriceText(s)}</span>
                      <PriceTrend
                        current={s.lastPrice}
                        previous={s.previousPrice}
                        previousOn={s.previousPriceOn}
                        format={previousPriceText(s, ingredient.packSizes)}
                      />
                    </span>
                  </td>
                  {/* An em dash, never a nought: unknown and same-day are different facts. */}
                  <td className={TD_FIXED_NUM} data-label="Lead time">
                    {s.leadTimeDays === null
                      ? <span className="text-ink-muted">—</span>
                      : `${s.leadTimeDays} ${s.leadTimeDays === 1 ? "day" : "days"}`}
                  </td>
                  {/* Labelled only when it is the dash (VERIFY2-A, N3; the vendor page's VERIFY-A defect 8).
                      On a phone card the headings are hidden, so a bare "—" after "Lead time 2 days"
                      read as part of the lead time and nobody could say what it was (§5 rule 6). The
                      badge says "Preferred" itself, and a label in front of it would read "Preferred
                      Preferred". data-label prints on the card only; the wide table is unchanged. */}
                  <td className={TD_FIXED} data-label={s.preferred ? undefined : "Preferred"}>
                    {s.preferred ? (
                      <span className="rounded-control bg-accent-bg px-2 py-1 text-xs font-semibold text-accent-text">Preferred</span>
                    ) : "—"}
                  </td>
                  <td className={TD_ACTIONS_FIXED}>
                    <div className={ACTIONS_ROW}>
                      <Button variant="ghost" size="sm" onClick={() => setEditing(s.vendorId)}>Edit</Button>
                    </div>
                  </td>
                </tr>
              )
            )}
          </tbody>
        </table>
      )}

      <LinkVendorForm
        ingredient={ingredient}
        vendors={linkable}
        heldElsewhere={heldElsewhere}
        busy={busy}
        onLink={(vendorId, row) =>
          run((t) => api.addVendorSupplies(vendorId, [row], t), "We couldn’t link that vendor.")
        }
      />
    </section>
  );
}

/**
 * "Link a vendor": the vendor, then the four facts the vendor page asks for a new supply, on one line
 * from a laptop up and wrapping only where they do not fit.
 */
function LinkVendorForm({
  ingredient,
  vendors,
  heldElsewhere,
  busy,
  onLink,
}: {
  ingredient: IngredientView;
  vendors: VendorView[];
  heldElsewhere: (vendorId: string | null) => string | null;
  busy: boolean;
  onLink: (vendorId: string, row: SetVendorSupplyInput) => Promise<boolean>;
}) {
  const [vendorId, setVendorId] = useState("");
  const [pack, setPack] = useState(STOCK_UNIT);
  const [price, setPrice] = useState("");
  const [lead, setLead] = useState("");
  const [preferred, setPreferred] = useState(false);
  const [noVendor, setNoVendor] = useState(false);
  const replacesId = useId();
  const replacing = preferred ? heldElsewhere(vendorId || null) : null;
  const chosenPack = ingredient.packSizes.find((p) => p.id === pack);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!vendorId) {
      setNoVendor(true);
      return;
    }
    setNoVendor(false);
    const ok = await onLink(vendorId, supplyInput(ingredient.id, { pack, price, lead, preferred }));
    if (ok) {
      setVendorId("");
      setPack(STOCK_UNIT);
      setPrice("");
      setLead("");
      setPreferred(false);
    }
  }

  if (vendors.length === 0) {
    return (
      <p className="mt-5 border-t border-hairline pt-4 text-sm text-ink-secondary">
        Every active vendor already sells this.
      </p>
    );
  }

  return (
    <Form className="mt-5 border-t border-hairline pt-4" aria-label="Link a vendor" onSubmit={submit}>
      <h3 className="font-semibold text-ink">Link a vendor</h3>
      {/*
        The four boxes, then the tick and the button, in ONE wrapping line, so each thing goes to a new
        row only when it genuinely cannot sit beside what is before it (the design system's rule on
        rows, and VERIFY2-A, N4). The tick and the button are one item and never part: ticking
        Preferred lengthens the words beside the tick, and splitting them would leave the button alone.

        Before N4 the tick and the button always had a line of their own under the boxes. At 1024 that
        took three rows where two fit: Lead time alone on row 2 with 494px empty beside it, and the
        pair on row 3. Measured on the page 2026-09-19 (item widths, labels included): Vendor 224 at
        its basis, Sells it as 114, List price 144, Lead time 136, the pair 222, gaps 16. So:
          - 1280, an 886px form: 224+114+144+136+222 and four gaps is 904, which is more than 886, so
            the pair goes to row 2 on its own: two rows, as before.
          - 1024, a 630px form: Lead time does not fit on row 1 (666), and the pair fits beside it on
            row 2 (136+16+222 = 374): two rows, where there were three.
          - 390, a 308px form: Lead time and the pair do not fit together (374), so the pair has row 4
            to itself, as before.
        The edge case, recorded in docs/work/proof/T-296.md: where the pair fits beside Lead time only
        while the tick says plain "Preferred", ticking it for an ingredient another vendor holds
        ("Preferred (replaces …)", up to 224px of words) moves the pair to a row of its own. That is the
        same rule applied to what is on screen, not a jump to design out by reserving blank space.

        The pair sits at the foot of its row (self-end), level with the boxes rather than with their
        labels, so it needs no blank label to line up (design system item 23). The vendor box grows to
        take what its row has spare, so row 1 leaves no empty block at its end.
      */}
      <div className="mt-3 flex flex-wrap items-start gap-4">
        <div className="min-w-0 grow basis-56">
        <HintedField label="Vendor">
          {(id) => (
            <select
              id={id}
              value={vendorId}
              onChange={(e) => {
                setVendorId(e.target.value);
                setNoVendor(false);
              }}
              aria-invalid={noVendor || undefined}
              className={`${BOX} w-full ${noVendor ? "border-danger" : ""}`}
            >
              <option value="">Choose a vendor</option>
              {vendors.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
            </select>
          )}
        </HintedField>
        </div>
        <HintedField label="Sells it as">
          {(id) => <SellsAsSelect id={id} ingredient={ingredient} value={pack} onChange={setPack} />}
        </HintedField>
        <HintedField label="List price (₹)">
          {(id) => <PriceBox id={id} value={price} onChange={setPrice} unit={ingredient.unit} pack={chosenPack} />}
        </HintedField>
        <HintedField label="Lead time (days)" hint={LEAD_TIME_HINT}>
          {(id) => (
            <input
              id={id}
              type="number"
              inputMode="numeric"
              min="0"
              max="365"
              step="1"
              value={lead}
              onChange={(e) => setLead(e.target.value)}
              className={`${BOX} w-24 tabular-nums`}
            />
          )}
        </HintedField>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3 self-end text-sm text-ink-secondary">
          <label className="flex min-h-touch items-center gap-2">
            <input
              type="checkbox"
              checked={preferred}
              onChange={(e) => setPreferred(e.target.checked)}
              aria-describedby={replacing ? replacesId : undefined}
              className="h-5 w-5 shrink-0 accent-accent"
            />
            {replacing ? <ReplacesText id={replacesId} vendorName={replacing} /> : "Preferred"}
          </label>
          <Button type="submit" busy={busy}>Link vendor</Button>
        </div>
      </div>
      {noVendor && (
        <p className="mt-2 pl-field-inset text-sm text-danger" role="alert">Choose a vendor to link.</p>
      )}
    </Form>
  );
}

/**
 * One of the ingredient's vendors, opened for editing: the vendor page's own edit row, keyed by vendor
 * instead of by ingredient, sending the same six keys to the same endpoint.
 */
function SupplyEditRow({
  supply,
  ingredient,
  heldBy,
  busy,
  onSave,
  onCancel,
}: {
  supply: IngredientSupplyView;
  ingredient: IngredientView;
  heldBy: string | null;
  busy: boolean;
  onSave: (input: SetVendorSupplyInput) => void;
  onCancel: () => void;
}) {
  const [pack, setPack] = useState(supply.packSizeId ?? STOCK_UNIT);
  const [price, setPrice] = useState(boxValue(supply.packSizeId ? supply.pricePerPack : supply.lastPrice));
  const [lead, setLead] = useState(boxValue(supply.leadTimeDays));
  const [preferred, setPreferred] = useState(supply.preferred);
  const chosen = ingredient.packSizes.find((p) => p.id === pack);

  return (
    <tr className="border-t border-hairline bg-sunken align-top">
      <td className={TD_PRIMARY}>{supply.vendorName}</td>
      <td className={TD_FIXED}>
        <HintedField label="Sells it as">
          {(id) => <SellsAsSelect id={id} ingredient={ingredient} value={pack} onChange={setPack} />}
        </HintedField>
      </td>
      <td className={TD_FIXED_NUM}>
        <HintedField label="List price (₹)">
          {(id) => <PriceBox id={id} value={price} onChange={setPrice} unit={supply.unit} pack={chosen} />}
        </HintedField>
      </td>
      <td className={TD_FIXED_NUM}>
        <HintedField label="Lead time (days)" hint={LEAD_TIME_HINT}>
          {(id) => (
            <input
              id={id}
              type="number"
              min="0"
              max="365"
              step="1"
              value={lead}
              onChange={(e) => setLead(e.target.value)}
              className="min-h-touch min-w-20 rounded-control border border-hairline px-3"
            />
          )}
        </HintedField>
      </td>
      <td className={TD_FIXED}>
        <label className="flex min-h-touch items-center gap-2 text-sm text-ink-secondary">
          <input
            type="checkbox"
            checked={preferred}
            onChange={(e) => setPreferred(e.target.checked)}
            className="h-5 w-5 shrink-0 rounded-sm border-hairline-strong accent-accent"
          />
          {preferred && heldBy ? <ReplacesText vendorName={heldBy} /> : "Preferred"}
        </label>
      </td>
      <td className={TD_ACTIONS_FIXED}>
        <div className={ACTIONS_ROW}>
          <Button
            size="sm"
            disabled={busy}
            onClick={() => onSave(supplyInput(supply.ingredientId, { pack, price, lead, preferred }))}
          >
            Save
          </Button>
          <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
        </div>
      </td>
    </tr>
  );
}

