"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useId, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { HintedField, InfoHint } from "@/components/ds/InfoHint";
import { PriceTrend } from "@/components/PriceTrend";
import { VendorStatusDialog } from "@/components/VendorStatusDialog";
import { api, toApiError, type ApiError, type VendorStatusChange } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  FOOD_UNITS, contractWarning, convertQuantity, inputModeForUnit, moment, ratePackWord, readableRate,
  stepForUnit, unitLabel, unitLabelFor,
} from "@/lib/format";
import { listPriceText, previousPriceText } from "@/components/ingredient/supply";
import { ALL_LANGUAGES, languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";
import {
  RULED_TABLE, TABLE, ENTRY_GRID, THEAD, TR, ACTIONS_ROW, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED, TD_FIXED_NUM,
  TH_ACTIONS_FIXED, TD_ACTIONS_FIXED, TH_TEXT, TD_TEXT, TH_NUM, TD_NUM, WRAP,
} from "@/components/ds/table";
import type { IngredientView, PackSizeView, SetVendorSupplyInput, VendorSupplyView } from "@/lib/api";
import { normalizePhone } from "@/lib/phone";

export default function VendorDetailPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <VendorDetailView />
    </RequireRole>
  );
}

function VendorDetailView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { getToken } = useAuth();

  const fetchVendor = useCallback((token: string | undefined) => api.getVendor(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchVendor);
  const { data: ingredientsData, reload: reloadIngredients } = useAuthedQuery(api.listIngredients);
  const ingredients = ingredientsData ?? [];
  /*
    Who holds the preference for each ingredient now (R-VEN-2), so a tick on Preferred can say whom
    it replaces before it is saved: "Preferred (replaces Anand Stores)". That vendor is not on this
    page, so the page asks. If this read fails, nothing is named and the sentence above the tables
    still says what a tick does; the tick itself works either way, because the server moves the
    preference, not this screen.
  */
  const { data: preferredData, reload: reloadPreferred } = useAuthedQuery(api.listPreferredVendors);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);
  const [changingStatus, setChangingStatus] = useState(false);
  /*
    Which supply row is open for editing, by its ingredient id — the same shape `/ingredients` uses
    for the same interaction, and keyed the same way its rows are.

    Before T-131 a supply row had one control, Remove, and the only way to correct anything about it
    was to remove it and add it again. That is not a longer route to the same place: adding it again
    starts from an empty form, so the price the temple last paid and the preference the shopping list
    reads both go in the bin to change a lead time. Worse, the Add form's picker deliberately offers
    only ingredients this vendor does NOT already supply, so for an existing supply the lead-time box
    could not be reached at all — the field T-090 shipped was unfillable on every row that already
    existed, which is every row a real temple has.
  */
  const [editing, setEditing] = useState<string | null>(null);

  /*
    Other ingredients (R-VEN-1). What has been typed into each row, by ingredient id, held here
    rather than in the rows so it survives the search and the category filter hiding a row and
    showing it again: somebody typing a vendor's list works through it by category, and a price
    typed under Dals must still be there after a look at Spices.
  */
  const [entries, setEntries] = useState<Record<string, Entry>>({});
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("");
  const [nothingTicked, setNothingTicked] = useState(false);
  /** How many were just added, for the confirmation; null when there is nothing to confirm. */
  const [added, setAdded] = useState<number | null>(null);

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      // A save can move a preference, here or away from another vendor; reread who holds what.
      reloadPreferred();
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const ok = await run(
      (token) =>
        api.updateVendor(
          id,
          {
            name: String(f.get("name") ?? "").trim(),
            // emptyToNull, like every other optional field: emptying this box is how a vendor the
            // temple now only ever walks into loses a number it should no longer carry, and "" is
            // not a number — it would be refused by the E.164 pattern (T-025).
            // Separators removed first, as on the add screen (T-157).
            phone: emptyToNull(normalizePhone(String(f.get("phone") ?? ""))),
            contactPerson: emptyToNull(String(f.get("contactPerson") ?? "")),
            email: emptyToNull(String(f.get("email") ?? "")),
            address: emptyToNull(String(f.get("address") ?? "")),
            gstin: emptyToNull(String(f.get("gstin") ?? "")),
            preferredLanguage: String(f.get("preferredLanguage") ?? "en"),
            notes: emptyToNull(String(f.get("notes") ?? "")),
            contractEndDate: emptyToNull(String(f.get("contractEndDate") ?? "")),
          },
          token
        ),
      "We couldn’t save those changes."
    );
    if (ok) {
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    }
  }

  /**
   * "Other ingredients" → Save (R-VEN-1): every ticked row, in one call, then the rows are gone from
   * the bottom table and in the top one.
   *
   * <p>Ticked rows the current search or category filter is hiding are saved too. The count beside
   * the button is of every ticked row, so what is saved is what it says, and somebody who ticked rice,
   * then searched for dal and ticked that, gets both.
   *
   * <p>One call, not one per row, because the server saves the list in one transaction (T-252): one
   * bad row refuses the lot, so nothing is ever half-added.
   */
  async function saveTicked(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAdded(null);
    const ticked = available.filter((i) => entries[i.id]?.ticked);
    if (ticked.length === 0) {
      setNothingTicked(true);
      return;
    }
    setNothingTicked(false);
    const rows = ticked.map((i) => supplyInput(i.id, entries[i.id]));
    const ok = await run((token) => api.addVendorSupplies(id, rows, token), "We couldn’t add those ingredients.");
    if (ok) {
      setEntries((was) => {
        const next = { ...was };
        for (const i of ticked) delete next[i.id];
        return next;
      });
      setAdded(ticked.length);
    }
  }

  function setEntry(ingredientId: string, change: Partial<Entry>) {
    setNothingTicked(false);
    setEntries((was) => {
      const current = was[ingredientId] ?? BLANK_ENTRY;
      // Typing into a row ticks it. A price typed into a row somebody forgot to tick would
      // otherwise be dropped on Save without a word, which is the one outcome worse than an extra
      // tick to take off. Only the tick box itself can untick.
      const ticked = "ticked" in change ? Boolean(change.ticked) : true;
      return { ...was, [ingredientId]: { ...current, ...change, ticked } };
    });
  }

  const vendor = data?.vendor;
  const supplies = data?.supplies ?? [];
  const statusHistory = data?.statusHistory ?? [];
  const suppliedIds = new Set(supplies.map((s) => s.ingredientId));
  const available = ingredients.filter((i) => !suppliedIds.has(i.id));
  const byId = new Map(ingredients.map((i) => [i.id, i]));
  /** The other vendor a tick on Preferred would take the preference from, by ingredient; null if none. */
  const heldElsewhere = (ingredientId: string): string | null => {
    const holder = (preferredData ?? []).find((p) => p.ingredientId === ingredientId);
    return holder && holder.vendorId !== id ? holder.vendorName : null;
  };
  const categories = Array.from(new Set(available.map((i) => i.category).filter(Boolean))).sort((a, b) =>
    a.localeCompare(b)
  );
  const needle = search.trim().toLowerCase();
  const shown = available.filter(
    (i) => (category === "" || i.category === category) && (needle === "" || i.name.toLowerCase().includes(needle))
  );
  const tickedCount = available.filter((i) => entries[i.id]?.ticked).length;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/vendors" />
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
        <div className="mx-auto max-w-content">
          <Link href="/vendors" className="text-sm text-accent-text hover:underline">← All vendors</Link>

          {/* Only the first load replaces the page. A reload after a save keeps it on screen, so a
              long Other ingredients list does not blank out and lose its scroll position. */}
          {loading && !data ? (
            <Loading label="Loading vendor…" />
          ) : error ? (
            <div className="mt-6"><ErrorNotice error={error} /></div>
          ) : !vendor ? null : (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{vendor.name}</h1>
                  <p className="mt-1 text-ink-secondary">
                    {vendor.active ? "Active vendor" : "Inactive vendor"} · {languageLabel(vendor.preferredLanguage)}
                  </p>
                </div>
                <Button
                  type="button"
                  variant={vendor.active ? "ghost" : "secondary"}
                  onClick={() => setChangingStatus(true)}
                >
                  {vendor.active ? "Make inactive" : "Bring back"}
                </Button>
              </header>

              {/* A warning and nothing else. The vendor below is still active, still selectable, and
                  still whatever the shopping list decided they were — the date changes none of it. */}
              {vendor.contractEndingSoon && vendor.contractEndDate && (
                <div className="mb-6">
                  <InlineNotice tone="warning" title={contractWarning(vendor.contractEndDate)}>
                    They are still active and can still be ordered from. Renew the agreement, or make
                    them inactive and say why.
                  </InlineNotice>
                </div>
              )}

              {actionError && <div className="mb-6"><ErrorNotice error={actionError} /></div>}

              <section className="card mb-8 px-6 py-5">
                <h2 className="text-lg">Details</h2>
                <Form className="mt-4 grid gap-4 sm:grid-cols-2" aria-label="Edit vendor" onSubmit={save}>
                  <Field name="name" label="Name" defaultValue={vendor.name} required />
                  {/* Not required, and `?? ""` rather than the bare value: a vendor may have no
                      number at all now (T-025), and an uncontrolled input cannot be handed null. */}
                  <Field
                    name="phone"
                    label="Phone (with country code)"
                    defaultValue={vendor.phone ?? ""}
                    hint="Only needed to send orders on WhatsApp. Leave it blank for a shop you walk into."
                  />
                  <Field name="contactPerson" label="Contact person" defaultValue={vendor.contactPerson ?? ""} />
                  <Field name="email" label="Email" type="email" defaultValue={vendor.email ?? ""} />
                  <Field name="gstin" label="GSTIN" defaultValue={vendor.gstin ?? ""} />
                  <Field
                    name="contractEndDate"
                    label="Contract ends"
                    type="date"
                    defaultValue={vendor.contractEndDate ?? ""}
                    hint="Only a reminder. Nothing switches off on this date."
                  />
                  <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                    <span className="pl-field-inset font-medium text-ink">Preferred language</span>
                    <select name="preferredLanguage" defaultValue={vendor.preferredLanguage} className="min-h-touch rounded-control border border-hairline px-3">
                      {ALL_LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
                    </select>
                  </label>
                  <div className="sm:col-span-2"><Field name="address" label="Address" defaultValue={vendor.address ?? ""} /></div>
                  <div className="sm:col-span-2"><Field name="notes" label="Notes" defaultValue={vendor.notes ?? ""} /></div>
                  <div className="sm:col-span-2 flex items-center gap-3">
                    <button type="submit" disabled={busy} className="btn btn-primary min-h-touch px-5 transition-colors duration-state disabled:opacity-60">
                      Save changes
                    </button>
                    {saved && <span className="text-sm text-success">Saved.</span>}
                  </div>
                </Form>
              </section>

              <section className="card px-6 py-5">
                <h2 className="text-lg">Supplies</h2>
                {/* "Edit a row to change any of the three." went (R-VEN-1): there are four now, and
                    the Edit button on every row says it without help. */}
                <p className="mt-1 text-sm text-ink-secondary">
                  A preferred supply is what the shopping list suggests, and its lead time is what
                  the planner counts back from to work out the last day something can be ordered.
                </p>
                {/* Said once, over both tables, because both offer the tick and neither can show
                    what it will affect: the other vendor is not on this screen. It is a plain
                    statement of what happens rather than a warning, because moving a preference is a
                    normal thing to do and the app does it without complaint. Since R-VEN-2 (T-258)
                    the row itself also names the vendor, once Preferred is ticked. */}
                <p className="mt-1 text-sm text-ink-secondary">
                  Only one vendor can be preferred for an ingredient, so ticking Preferred here
                  takes it from whichever vendor holds it now.
                </p>

                {supplies.length === 0 ? (
                  <p className="mt-4 text-sm text-ink-secondary">
                    Nothing from this vendor yet. Tick what they sell under Other ingredients.
                  </p>
                ) : (
                  <table className={`${RULED_TABLE} mt-4`}>
                    <thead className={THEAD}>
                      <tr>
                        <th className={TH_PRIMARY}>Ingredient</th>
                        <th className={TH_FIXED}>Sells it as</th>
                        {/* "Last price" until R-VEN-1: it is the vendor's list price, set when they
                            are taken on and moved by each invoice, not the last thing paid. */}
                        <th className={TH_FIXED}>List price</th>
                        <th className={TH_FIXED}>Lead time</th>
                        <th className={TH_FIXED}>Preferred</th>
                        {/* "Remove" until T-131, when the column stopped holding only the one
                            control. Named for what the column is rather than for what happens to
                            be in it, as every other table on the site names it. */}
                        <th className={TH_ACTIONS_FIXED}><span className="sr-only">Actions</span></th>
                      </tr>
                    </thead>
                    <tbody>
                      {supplies.map((s) =>
                        editing === s.ingredientId ? (
                          <SupplyEditRow
                            key={s.ingredientId}
                            supply={s}
                            ingredient={byId.get(s.ingredientId)}
                            heldBy={heldElsewhere(s.ingredientId)}
                            busy={busy}
                            onPackAdded={reloadIngredients}
                            onCancel={() => setEditing(null)}
                            onSave={async (input) => {
                              const ok = await run(
                                (t) => api.setVendorSupply(id, input, t),
                                "We couldn’t save that supply."
                              );
                              if (ok) setEditing(null);
                            }}
                          />
                        ) : (
                        <tr key={s.ingredientId} className={TR}>
                          <td className={TD_PRIMARY}>{s.ingredientName}</td>
                          <td className={TD_FIXED} data-label="Sells it as">{s.packLabel ?? unitLabel(s.unit)}</td>
                          <td className={TD_FIXED_NUM} data-label="List price">
                            <span className="inline-flex items-center gap-2">
                              <span>{listPriceText(s)}</span>
                              <PriceTrend
                                current={s.lastPrice}
                                previous={s.previousPrice}
                                previousOn={s.previousPriceOn}
                                format={previousPriceText(s, byId.get(s.ingredientId)?.packSizes)}
                              />
                            </span>
                          </td>
                          {/* An em dash, never a nought. Nobody having said how long this vendor
                              takes and this vendor delivering the same day are different facts, and
                              a "0 days" here would read as the second. */}
                          <td className={TD_FIXED_NUM} data-label="Lead time">
                            {s.leadTimeDays === null
                              ? <span className="text-ink-muted">—</span>
                              : `${s.leadTimeDays} ${s.leadTimeDays === 1 ? "day" : "days"}`}
                          </td>
                          {/* Labelled only when it is the dash (VERIFY-A, defect 8). On a phone card
                              the heading is hidden, so a bare "—" beside the lead time's "—" read
                              "Lead time — —" and nobody could say which dash was which (§5 rule 6).
                              The badge says "Preferred" itself, so a label in front of it would read
                              "Preferred Preferred". The label prints on the card only; the wide
                              table shows no data-label. */}
                          <td className={TD_FIXED} data-label={s.preferred ? undefined : "Preferred"}>{s.preferred ? <span className="rounded-control bg-accent-bg px-2 py-1 text-xs text-accent-text font-semibold">Preferred</span> : "—"}</td>
                          <td className={TD_ACTIONS_FIXED}>
                            <div className={ACTIONS_ROW}>
                              <Button variant="ghost" size="sm" onClick={() => setEditing(s.ingredientId)}>Edit</Button>
                              <Button variant="danger" size="sm" disabled={busy} onClick={() => run((t) => api.removeVendorSupply(id, s.ingredientId, t), "We couldn’t remove that supply.")}>
                                Remove
                              </Button>
                            </div>
                          </td>
                        </tr>
                        )
                      )}
                    </tbody>
                  </table>
                )}
              </section>

              {/*
                Other ingredients (R-VEN-1): every ingredient this vendor does not supply yet, each a
                row of boxes, so a vendor's whole list can be typed in at one sitting when they are
                taken on. It replaced the one-at-a-time "Add supply" form (the conductor's call,
                2026-09-19: one way to do one thing), which asked for the same four facts about one
                ingredient per press.

                An entry grid, as the mocks build every table of boxes (ENTRY_GRID): spaced by the
                table fitter on a wide screen, and a card per row below 1024px with each box under
                its own small label. The rows are a `Form` so a lead time over 365 is named beside
                its box, in words, like every other form here.
              */}
              <section className="card mt-8 px-6 py-5">
                <h2 className="text-lg">Other ingredients</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Tick what this vendor sells, then Save. A price can wait.
                </p>

                <Form id="add-supplies" aria-label="Other ingredients" className="mt-4" onSubmit={saveTicked}>
                  {/* The filters and the button on one row: they fit side by side from a tablet up,
                      and wrap onto a second line only on a phone. */}
                  <div className="flex flex-wrap items-end gap-4">
                    <label className="flex min-w-0 flex-1 basis-56 flex-col gap-1 text-sm text-ink-secondary">
                      <span className="pl-field-inset font-medium text-ink">Search</span>
                      <input
                        type="search"
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        // Enter in a search box searches. Inside this form it would otherwise save.
                        onKeyDown={(e) => { if (e.key === "Enter") e.preventDefault(); }}
                        placeholder="Ingredient name"
                        className="min-h-touch w-full rounded-control border border-hairline px-3"
                      />
                    </label>
                    <label className="flex flex-col gap-1 text-sm text-ink-secondary">
                      <span className="pl-field-inset font-medium text-ink">Category</span>
                      <select
                        value={category}
                        onChange={(e) => setCategory(e.target.value)}
                        className="min-h-touch rounded-control border border-hairline px-3"
                      >
                        <option value="">All categories</option>
                        {categories.map((c) => <option key={c} value={c}>{c}</option>)}
                      </select>
                    </label>
                    <div className="flex min-h-touch items-center gap-3">
                      <Button type="submit" busy={busy}>Save</Button>
                      <span className="text-sm text-ink-secondary" aria-live="polite">
                        {tickedCount === 0 ? "None ticked" : `${tickedCount} ticked`}
                      </span>
                    </div>
                  </div>

                  {nothingTicked && (
                    <p className="mt-2 pl-field-inset text-sm text-danger" role="alert">
                      Tick at least one ingredient to add.
                    </p>
                  )}
                  {added !== null && (
                    <div className="mt-4">
                      <InlineNotice tone="success" autoDismiss title={added === 1 ? "1 ingredient added to Supplies." : `${added} ingredients added to Supplies.`} />
                    </div>
                  )}

                  {available.length === 0 ? (
                    <p className="mt-4 text-sm text-ink-secondary">This vendor already supplies every ingredient.</p>
                  ) : shown.length === 0 ? (
                    <p className="mt-4 text-sm text-ink-secondary">No ingredients match. Try another name or category.</p>
                  ) : (
                    <>
                    {/* The Lead time hint on a phone (T-292; conductor's ruling, 2026-09-19). Wide,
                        it is the "i" in the column heading. Below 1024px the rows are cards and the
                        heading row is hidden, so that "i" is not rendered there (InfoHint): it was a
                        keyboard stop nobody could see (VERIFY-A, defect 7). Said once, here, above
                        the cards, rather than an "i" on every card: one Tab, not one per ingredient
                        on a list of two hundred. The same words and the same component, so the two
                        cannot drift. Nearer the cards than the filters (24px above it, the cards'
                        own 16px below), because it is about the cards: measured at 390 with the
                        table's usual 16px margin kept, it sat 16px under the filters and 33px over
                        the first card, and read as part of the filter row. */}
                    <p className="mt-6 flex items-center gap-1.5 text-sm font-medium text-ink lg:hidden">
                      Lead time (days)
                      <InfoHint text={LEAD_TIME_HINT} label="Lead time (days)" />
                    </p>
                    <table className={`${TABLE} ${ENTRY_GRID} mt-4 text-sm max-lg:mt-0 max-lg:[&_tbody_td]:border-0`}>
                      <thead className={THEAD}>
                        <tr>
                          {/* The one column allowed to wrap, when the table is short of room: a long
                              name can take two lines; a box cannot be cut. */}
                          <th className={`${TH_TEXT} ${WRAP}`}>Ingredient</th>
                          <th className={TH_TEXT}>Sells it as</th>
                          <th className={TH_NUM}>List price (₹)</th>
                          <th className={TH_NUM}>
                            <span className="inline-flex items-center gap-1.5">
                              Lead time (days)
                              <InfoHint text={LEAD_TIME_HINT} label="Lead time (days)" />
                            </span>
                          </th>
                          <th className={TH_TEXT}>Preferred</th>
                        </tr>
                      </thead>
                      <tbody>
                        {shown.map((i) => (
                          <OtherIngredientRow
                            key={i.id}
                            ingredient={i}
                            entry={entries[i.id] ?? BLANK_ENTRY}
                            heldBy={heldElsewhere(i.id)}
                            onChange={(change) => setEntry(i.id, change)}
                            onPackAdded={reloadIngredients}
                          />
                        ))}
                      </tbody>
                    </table>
                    </>
                  )}
                </Form>
              </section>

              <section className="card mt-8 px-6 py-5">
                <h2 className="text-lg">Active and inactive</h2>
                <p className="mt-1 text-sm text-ink-secondary">
                  Why this vendor has been dropped, and brought back. Kept as it was written — nothing
                  here is ever edited or removed.
                </p>

                {statusHistory.length === 0 ? (
                  <p className="mt-4 text-sm text-ink-secondary">
                    This vendor has never been made inactive.
                  </p>
                ) : (
                  <ol className="mt-4 space-y-3">
                    {statusHistory.map((c: VendorStatusChange) => (
                      <li key={c.id} className="border-t border-hairline pt-3 first:border-t-0 first:pt-0">
                        <div className="flex flex-wrap items-center gap-2">
                          {/* Neutral both ways: a line of history, not a result (T-227). */}
                          <Badge>
                            {c.toActive ? "Brought back" : "Made inactive"}
                          </Badge>
                          <span className="text-sm text-ink-secondary">
                            {c.actorName ?? "Someone since removed"} · {moment(c.createdAt)}
                          </span>
                        </div>
                        <p className="mt-1 text-sm">
                          {c.reason ?? <span className="text-ink-muted">No reason given</span>}
                        </p>
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            </>
          )}
        </div>
      </main>

      {changingStatus && vendor && (
        <VendorStatusDialog
          vendor={vendor}
          onCancel={() => setChangingStatus(false)}
          onDone={() => {
            setChangingStatus(false);
            reload();
          }}
        />
      )}
    </div>
  );
}

/**
 * One text field of the vendor's details.
 *
 * <p>Built on {@link HintedField} rather than keeping its own wrapping `<label>`: the hint is now
 * the "i" beside the label, and an "i" is a button, which a `<label>` would claim as its own
 * control the moment it was put inside one. The render-function child is what forces the id onto
 * the input instead.
 */
function Field({
  name, label, defaultValue, type = "text", required = false, hint,
}: { name: string; label: string; defaultValue?: string; type?: string; required?: boolean; hint?: string }) {
  return (
    <HintedField label={label} hint={hint}>
      {(id) => (
        <input id={id} name={name} type={type} defaultValue={defaultValue} required={required} className="min-h-touch rounded-control border border-hairline px-3" />
      )}
    </HintedField>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}

/**
 * What a number box on this screen means, in one place: **blank is null and zero is zero.**
 *
 * <p>Named rather than written inline at each of the four call sites, because the two ways of
 * getting it wrong are opposite and both are one character from correct. `Number("")` is `0`, so
 * the obvious coercion turns "nobody has recorded how long this vendor takes" into "this vendor
 * delivers the same day" — and every ordering screen then counts back nought days and tells a cook
 * there is still time to order rice that can no longer be got. Guarding with `Number(t) || null`
 * fixes that and breaks the other direction, quietly discarding the real `0` a cash-and-carry shop
 * has. The blank is tested as a string, before anything is coerced at all.
 */
function numberOrNull(raw: string): number | null {
  const t = raw.trim();
  return t === "" ? null : Number(t);
}

/** A supply's own value as a number box holds it — and `0` is "0", never "". */
function boxValue(n: number | null): string {
  return n === null ? "" : String(n);
}


/** The same words wherever a lead time is typed on this screen, because it is the same question. */
const LEAD_TIME_HINT =
  "How long this vendor takes to deliver this item once you ask. Leave it blank if you don’t know — we’ll assume two days until somebody records it. Put 0 for a shop you walk into and carry it back from.";

/** The "Sells it as" value for the stock unit itself: no pack. */
const STOCK_UNIT = "";
/** The last option in "Sells it as", which opens the pack size fields rather than choosing one. */
const ADD_PACK = "__add-pack";

/** One "Other ingredients" row as typed: every box is a string, so blank stays blank (see numberOrNull). */
interface Entry {
  ticked: boolean;
  /** A pack size id, or {@link STOCK_UNIT}. */
  pack: string;
  /** Per pack when a pack is chosen, per stock unit when not. */
  price: string;
  lead: string;
  preferred: boolean;
}

const BLANK_ENTRY: Entry = { ticked: false, pack: STOCK_UNIT, price: "", lead: "", preferred: false };

/**
 * What the server is sent for one supply, from the boxes as typed.
 *
 * <p><strong>A price sold in a pack goes as `pricePerPack` only, with `lastPrice` null.</strong> The
 * server works the per-unit list price out from the pack and refuses a per-unit price sent beside a
 * pack (KMS-400163), so a price is never typed twice and cannot disagree with itself (R-VEN-1).
 * Without a pack, the price is the per-unit list price and both pack fields go as null, which is how
 * a supply that used to come in bags is moved back to loose: the server writes the whole row.
 *
 * <p>All six keys are always present, for the reason the edit row gives: a key left out is a column
 * the server writes as empty.
 */
function supplyInput(
  ingredientId: string,
  e: Pick<Entry, "pack" | "price" | "lead" | "preferred">
): SetVendorSupplyInput {
  const price = numberOrNull(e.price);
  const packed = e.pack !== STOCK_UNIT;
  return {
    ingredientId,
    lastPrice: packed ? null : price,
    leadTimeDays: numberOrNull(e.lead),
    preferred: e.preferred,
    packSizeId: packed ? e.pack : null,
    pricePerPack: packed ? price : null,
  };
}

// How a price is said — "₹300 / Kg", "₹1,500 / bag · ₹60 / Kg" — is the shared `readableRate` in
// lib/format.ts, reached here through `listPriceText` and `perUnitPrice` in
// components/ingredient/supply.tsx (T-293). This page used to carry its own copies, and the merge
// screen had drifted from them ("₹0.30/gm" for a price this page said as "₹300 / Kg").

/** The units a new pack of this ingredient can be measured in: its own family only (R-ING-1). */
function sameFamilyUnits(unit: string): string[] {
  return FOOD_UNITS.filter((u) => convertQuantity(1, u, unit) !== null);
}

/**
 * "Preferred (replaces Anand Stores)" (R-VEN-2): said beside a ticked Preferred box when another
 * vendor holds the preference now, before anything is saved.
 *
 * <p><strong>Neutral, not amber.</strong> It is information: moving a preference is a normal thing
 * to do, and the colour rule keeps amber for something that needs care (DESIGN_SYSTEM.md, "colour
 * means something").
 *
 * <p><strong>It wraps, it is never cut.</strong> The cells it sits in are `nowrap`, and a vendor's
 * name can be long ("Sri Lakshmi Venkateswara Wholesale Provisions"); without `whitespace-normal`
 * and a width to wrap at, it would push the table wider than a 390px phone. The width is a measure
 * for the words, not a truncation: nothing here has an ellipsis.
 */
function ReplacesText({ vendorName, id }: { vendorName: string; id?: string }) {
  return (
    <span id={id} className="min-w-0 max-w-56 whitespace-normal text-sm text-ink-secondary">
      Preferred (replaces {vendorName})
    </span>
  );
}

/** The one box look on this page's entry rows, as the mocks draw it: 44px, the control corner. */
const BOX = "min-h-touch rounded-control border border-hairline bg-canvas px-3";

/**
 * "Sells it as": the stock unit, each of the ingredient's pack sizes, and last, "Add a pack size…"
 * (the conductor's call, 2026-09-19), which opens the pack size fields under the row instead of
 * choosing anything.
 */
function SellsAsSelect({
  ingredient,
  unit,
  value,
  onChange,
  onAddPack,
  label,
  id,
}: {
  ingredient: IngredientView | undefined;
  unit: string;
  value: string;
  onChange: (pack: string) => void;
  onAddPack: () => void;
  label?: string;
  id?: string;
}) {
  const packs = ingredient?.packSizes ?? [];
  return (
    <select
      id={id}
      aria-label={label}
      value={value}
      onChange={(e) => (e.target.value === ADD_PACK ? onAddPack() : onChange(e.target.value))}
      className={BOX}
    >
      <option value={STOCK_UNIT}>{unitLabel(unit)}</option>
      {packs.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
      <option value={ADD_PACK}>Add a pack size…</option>
    </select>
  );
}

/**
 * The List price box, the unit it is typed per beside it ("/ bag", "/ Kg"), and — when it is typed
 * per pack — what that comes to per stock unit, worked out as they type: "· ₹60 / Kg". The per-unit
 * figure is never a second box (R-VEN-1).
 */
function PriceBox({
  value,
  onChange,
  unit,
  pack,
  label,
  id,
}: {
  value: string;
  onChange: (v: string) => void;
  unit: string;
  pack: PackSizeView | undefined;
  label?: string;
  id?: string;
}) {
  const price = numberOrNull(value);
  const derived = pack && price !== null && pack.baseQuantity > 0 ? readableRate(price / pack.baseQuantity, unit) : null;
  // The worked-out figure goes on a line of its own under the box rather than beside it: beside
  // it, this one column would need about 230px, and the entry grid has five columns to fit in the
  // width a laptop leaves after the sidebar.
  return (
    <span className="flex flex-col gap-1">
      <span className="flex items-center gap-2">
      <input
        id={id}
        aria-label={label}
        type="number"
        inputMode="decimal"
        min="0"
        step="any"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className={`${BOX} min-w-24 tabular-nums`}
      />
      <span className="text-ink-secondary">/ {pack ? ratePackWord(pack.label) : unitLabelFor(1, unit)}</span>
      </span>
      {derived && <span className="pl-field-inset text-xs tabular-nums text-ink-secondary">= {derived}</span>}
    </span>
  );
}

/**
 * The pack size fields, opened from "Add a pack size…": an optional name, a size, and a unit of the
 * ingredient's own family. Saved on the ingredient (R-ING-1) through the same endpoint the
 * ingredient page uses, so a pack made here is the ingredient's for every vendor, not this one's.
 *
 * <p>A row of its own under the ingredient's row, spanning the table, rather than squeezed into the
 * Sells it as cell: three fields and two buttons would widen that column for every row while it was
 * open. The server's refusals (a duplicate size, a ninth pack, another family) are shown here, beside
 * the fields that caused them, rather than at the top of a long page.
 */
function PackSizeRow({
  ingredient,
  colSpan,
  onSaved,
  onCancel,
}: {
  ingredient: IngredientView;
  colSpan: number;
  onSaved: (packSizeId: string) => void;
  onCancel: () => void;
}) {
  const { getToken } = useAuth();
  const units = sameFamilyUnits(ingredient.unit);
  const [name, setName] = useState("");
  const [size, setSize] = useState("");
  const [unit, setUnit] = useState(ingredient.unit);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [blank, setBlank] = useState(false);

  /*
    A refusal belongs to the entry that caused it. Once the entry changes, the old refusal is about
    something no longer on screen, and left in place it sat under the next message as though both
    were true (VERIFY-A, defect 2: "Size must be more than 0" above "This ingredient already has a
    pack of that size", for a 0 that is no size at all). So any change to the three fields clears it,
    and so does every press of Add pack size, before anything else is checked.
  */
  function edited() {
    setError(null);
  }

  async function add() {
    setError(null);
    const quantity = numberOrNull(size);
    if (quantity === null || quantity <= 0) {
      setBlank(true);
      return;
    }
    setBlank(false);
    setBusy(true);
    try {
      const made = await api.addPackSize(
        ingredient.id,
        { name: emptyToNull(name), quantity, unit },
        await getToken()
      );
      onSaved(made.id);
    } catch (e) {
      setError(toApiError(e, "We couldn’t add that pack size."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr className="bg-sunken">
      <td colSpan={colSpan} className="px-5 py-4">
        <div role="group" aria-label={`New pack size for ${ingredient.name}`} className="flex flex-wrap items-end gap-4">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Pack name (optional)</span>
            <input value={name} onChange={(e) => { setName(e.target.value); edited(); }} placeholder="Bag" className={`${BOX} w-40`} />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Size</span>
            <input
              type="number"
              // The same pack size the ingredient screen asks for, asked here (T-424). The rate
              // box above is money and keeps `step="any"`.
              inputMode={inputModeForUnit(unit)}
              min="0"
              step={stepForUnit(unit)}
              value={size}
              onChange={(e) => { setSize(e.target.value); edited(); }}
              aria-invalid={blank || undefined}
              className={`${BOX} w-28 tabular-nums ${blank ? "border-danger" : ""}`}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Unit</span>
            <select value={unit} onChange={(e) => { setUnit(e.target.value); edited(); }} className={BOX}>
              {units.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}
            </select>
          </label>
          <div className="flex items-center gap-3">
            <Button variant="ghost" busy={busy} onClick={add}>Add pack size</Button>
            <Button variant="ghost" onClick={onCancel}>Cancel</Button>
          </div>
        </div>
        {blank && <p className="mt-2 pl-field-inset text-sm text-danger">Size must be more than 0</p>}
        {error && <div className="mt-3"><ErrorNotice error={error} /></div>}
      </td>
    </tr>
  );
}

/**
 * One row of Other ingredients: the tick and the name, then the four facts, all typed in place.
 *
 * <p>The tick and the name share the first cell, the name being the tick's label: a click on the
 * name ticks it, a screen reader hears "Rice, checkbox", and on a phone card the first line is the
 * thing being chosen. The other boxes are named for their ingredient ("List price (₹) for Rice"),
 * because two hundred boxes all called "List price" tell a screen reader nothing about which.
 */
function OtherIngredientRow({
  ingredient,
  entry,
  heldBy,
  onChange,
  onPackAdded,
}: {
  ingredient: IngredientView;
  entry: Entry;
  /** The vendor preferred for this ingredient now, if another one is (R-VEN-2). */
  heldBy: string | null;
  onChange: (change: Partial<Entry>) => void;
  onPackAdded: () => void;
}) {
  const [addingPack, setAddingPack] = useState(false);
  const replacesId = useId();
  const replacing = entry.preferred ? heldBy : null;
  const pack = ingredient.packSizes?.find((p) => p.id === entry.pack);
  const n = ingredient.name;
  return (
    <>
      <tr className={TR}>
        <td className={`${TD_TEXT} ${WRAP}`}>
          <label className="flex min-h-touch items-center gap-3 font-medium text-ink">
            <input
              type="checkbox"
              checked={entry.ticked}
              onChange={(e) => onChange({ ticked: e.target.checked })}
              className="h-5 w-5 shrink-0 accent-accent"
            />
            {n}
          </label>
        </td>
        <td className={TD_TEXT} data-label="Sells it as">
          <SellsAsSelect
            ingredient={ingredient}
            unit={ingredient.unit}
            value={entry.pack}
            onChange={(p) => onChange({ pack: p })}
            onAddPack={() => setAddingPack(true)}
            label={`Sells it as, for ${n}`}
          />
        </td>
        <td className={TD_NUM} data-label="List price (₹)">
          <PriceBox
            value={entry.price}
            onChange={(v) => onChange({ price: v })}
            unit={ingredient.unit}
            pack={pack}
            label={`List price (₹) for ${n}`}
          />
        </td>
        <td className={TD_NUM} data-label="Lead time (days)">
          <input
            aria-label={`Lead time (days) for ${n}`}
            type="number"
            inputMode="numeric"
            min="0"
            max="365"
            step="1"
            value={entry.lead}
            onChange={(e) => onChange({ lead: e.target.value })}
            className={`${BOX} min-w-20 tabular-nums`}
          />
        </td>
        <td className={TD_TEXT} data-label="Preferred">
          <span className="flex min-h-touch items-center gap-2">
            <input
              type="checkbox"
              aria-label={`Preferred for ${n}`}
              aria-describedby={replacing ? replacesId : undefined}
              checked={entry.preferred}
              onChange={(e) => onChange({ preferred: e.target.checked })}
              className="h-5 w-5 shrink-0 accent-accent"
            />
            {replacing && <ReplacesText id={replacesId} vendorName={replacing} />}
          </span>
        </td>
      </tr>
      {addingPack && (
        <PackSizeRow
          ingredient={ingredient}
          colSpan={5}
          onCancel={() => setAddingPack(false)}
          onSaved={(packId) => {
            setAddingPack(false);
            onChange({ pack: packId });
            onPackAdded();
          }}
        />
      )}
    </>
  );
}

/**
 * One supply row, opened for editing.
 *
 * <p><strong>The pattern is `/ingredients`', deliberately and not by coincidence.</strong> That
 * screen gives each row an Edit and a Delete, and Edit swaps the row for the same row as inputs
 * with a Save and a Cancel at the end of it. This is the same interaction on the same kind of thing,
 * so it is the same shape: a `<tr>` of exactly the header's cells, the row's own values seeded into
 * the controls, and nothing committed until Save. It stayed this way when "Other ingredients" arrived
 * (the conductor's call, 2026-09-19), with the same four facts that table asks for.
 *
 * <p><strong>The ingredient is not editable, and that is the one departure.</strong> On
 * `/ingredients` the name is a field, because the name is a property of the thing. Here the
 * ingredient is what the row *is* — the server addresses a supply by `(vendor, ingredient)` — so
 * changing it in this box would not correct this supply, it would create a different one and leave
 * this one behind. Somebody who wants that removes the row and adds the other.
 *
 * <p><strong>Every value is sent on every Save, including the ones nobody touched.</strong> Not
 * defensiveness: the server writes the whole row (see `setVendorSupply` in `lib/api.ts`), so a save
 * that left one out would erase it. Seeding each control from the row is what makes that safe, and it
 * is why the price box opens holding the price rather than empty — per pack when the supply is sold
 * in a pack, since that is the figure the vendor quotes and the one the box now means.
 *
 * <p><strong>Preferred is offered here</strong> rather than left out, because it is one of the facts
 * that Remove-and-add was throwing away and the point of the row is that none of them should need
 * that. The unique index allows one preferred vendor per ingredient across the temple, and it is not
 * this screen that keeps that true: `VendorService.setSupply` clears any other vendor's preference for
 * the ingredient inside the same transaction before writing this one, so the tick *moves* the
 * preference and cannot collide with it. What the screen owes the person is therefore not a refusal
 * but a sentence saying so, and that sits above the tables where it covers this row and the Other
 * ingredients rows both. Since R-VEN-2 (T-258) the row also names the vendor it takes the preference
 * from, the moment the box is ticked and before Save: the label reads "Preferred (replaces Anand
 * Stores)". The name comes from `listPreferredVendors`, read by the page, since that vendor is not
 * otherwise on this screen.
 */
function SupplyEditRow({
  supply,
  ingredient,
  heldBy,
  busy,
  onSave,
  onCancel,
  onPackAdded,
}: {
  supply: VendorSupplyView;
  ingredient: IngredientView | undefined;
  /** The vendor preferred for this ingredient now, if another one is (R-VEN-2). */
  heldBy: string | null;
  busy: boolean;
  onSave: (input: SetVendorSupplyInput) => void;
  onCancel: () => void;
  onPackAdded: () => void;
}) {
  const [pack, setPack] = useState(supply.packSizeId ?? STOCK_UNIT);
  const [price, setPrice] = useState(boxValue(supply.packSizeId ? supply.pricePerPack : supply.lastPrice));
  const [leadTimeDays, setLeadTimeDays] = useState(boxValue(supply.leadTimeDays));
  const [preferred, setPreferred] = useState(supply.preferred);
  const [addingPack, setAddingPack] = useState(false);
  const chosen = ingredient?.packSizes?.find((p) => p.id === pack);

  return (
    <>
      <tr className="border-t border-hairline bg-sunken align-top">
        <td className={TD_PRIMARY}>{supply.ingredientName}</td>
        <td className={TD_FIXED}>
          <HintedField label="Sells it as">
            {(fieldId) => (
              <SellsAsSelect
                id={fieldId}
                ingredient={ingredient}
                unit={supply.unit}
                value={pack}
                onChange={setPack}
                onAddPack={() => setAddingPack(true)}
              />
            )}
          </HintedField>
        </td>
        <td className={TD_FIXED_NUM}>
          <HintedField label="List price (₹)">
            {(fieldId) => (
              <PriceBox id={fieldId} value={price} onChange={setPrice} unit={supply.unit} pack={chosen} />
            )}
          </HintedField>
        </td>
        {/* The hint is the same words the Other ingredients table uses, because it is answering the
            same question. Emptying the box is a real and useful edit — a lead time recorded from a
            guess should be removable back to "nobody has said" — so the hint has to be here too,
            where the clearing actually happens. */}
        <td className={TD_FIXED_NUM}>
          <HintedField label="Lead time (days)" hint={LEAD_TIME_HINT}>
            {(fieldId) => (
              <input
                id={fieldId}
                type="number"
                min="0"
                max="365"
                step="1"
                value={leadTimeDays}
                onChange={(e) => setLeadTimeDays(e.target.value)}
                className="min-h-touch min-w-20 rounded-control border border-hairline px-3"
              />
            )}
          </HintedField>
        </td>
        <td className={TD_FIXED}>
          <label className="flex min-h-touch items-center gap-2 text-sm text-ink-secondary">
            {/* No `aria-label`: the wrapping `<label>` already names it, and a redundant one only
                invites the two to drift apart. */}
            <input
              type="checkbox"
              checked={preferred}
              onChange={(e) => setPreferred(e.target.checked)}
              className="h-5 w-5 shrink-0 rounded-sm border-hairline-strong accent-accent"
            />
            {/* The label itself says whom the tick replaces, so it is read out with the box. It
                wraps rather than widening the column: this row is the widest on the page. */}
            {preferred && heldBy ? <ReplacesText vendorName={heldBy} /> : "Preferred"}
          </label>
        </td>
        <td className={TD_ACTIONS_FIXED}>
          <div className={ACTIONS_ROW}>
            <Button
              size="sm"
              disabled={busy}
              onClick={() =>
                onSave(supplyInput(supply.ingredientId, { pack, price, lead: leadTimeDays, preferred }))
              }
            >
              Save
            </Button>
            <Button variant="ghost" size="sm" onClick={onCancel}>Cancel</Button>
          </div>
        </td>
      </tr>
      {addingPack && ingredient && (
        <PackSizeRow
          ingredient={ingredient}
          colSpan={6}
          onCancel={() => setAddingPack(false)}
          onSaved={(packId) => {
            setAddingPack(false);
            setPack(packId);
            onPackAdded();
          }}
        />
      )}
    </>
  );
}
