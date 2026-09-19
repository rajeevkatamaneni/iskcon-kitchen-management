"use client";

import { useState } from "react";
import { Button } from "@/components/ds/Button";
import { Form } from "@/components/ds/Form";
import { ErrorNotice } from "@/components/ErrorNotice";
import { api, toApiError, type ApiError, type IngredientView, type MarketRateSource } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { rateUnit, readableRate, shortDate, unitLabelFor } from "@/lib/format";
import { BOX, numberOrNull, perStockUnit, perUnitPrice } from "@/components/ingredient/supply";

/**
 * Where the rate came from, in words. The source is stored (R-ING-3) and no other screen says it, so
 * this is the one place somebody can learn whether ₹60 was counted off a shelf or read off a bill.
 */
const SOURCE_WORDS: Record<MarketRateSource, string> = {
  STOCK_TAKE: "Set at a stock count",
  INVOICE: "Set from an invoice",
  MANUAL: "Typed in by hand",
};

/**
 * "Market rate · ₹60 / Kg · 12 Sept" (R-ING-3), and a way to change it for those who hold
 * `MANAGE_INVENTORY` — the permission `PUT /ingredients/{id}/market-rate` declares.
 *
 * <p><strong>The rate is kept per stock unit and said per Kg.</strong> An ingredient counted in grams
 * has its rate stored per gram (₹0.06) and printed per Kg (₹60), the vendor page's rule for list prices;
 * so the box is typed per Kg too and converted back before it is sent. Typing ₹60 into a box that
 * meant per gram would value a sack of rice at sixty thousand rupees.
 *
 * <p>The server refuses a blank or nought (the rate "can't be blank or 0"), and its words are shown. The
 * one check made here is the same, so nobody waits on a round trip to learn a box is empty.
 */
export function MarketRate({
  ingredient,
  canEdit,
  onChanged,
}: {
  ingredient: IngredientView;
  canEdit: boolean;
  onChanged: () => void;
}) {
  const { getToken } = useAuth();
  const said = rateUnit(ingredient.unit);
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState("");
  const [blank, setBlank] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  function open() {
    const rate = ingredient.marketRate;
    // Rounded to paise for the box: the stored per-gram figure times a thousand can carry float dust.
    setValue(rate === null ? "" : String(Math.round(perUnitPrice(rate, ingredient.unit) * 100) / 100));
    setBlank(false);
    setError(null);
    setEditing(true);
  }

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const typed = numberOrNull(value);
    if (typed === null || !(typed > 0)) {
      setBlank(true);
      return;
    }
    setBlank(false);
    setBusy(true);
    setError(null);
    try {
      await api.setMarketRate(ingredient.id, perStockUnit(typed, ingredient.unit), await getToken());
      setEditing(false);
      onChanged();
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that market rate."));
    } finally {
      setBusy(false);
    }
  }

  const rate = ingredient.marketRate;
  const label = `Market rate (₹ per ${unitLabelFor(1, said)})`;

  return (
    <section className="card px-6 py-5" aria-labelledby="market-rate-heading">
      {/* "Price" rather than "Market rate", because the line below already begins with those words
          (R-ING-3 gives it as "Market rate · ₹60 / Kg · 12 Sept"), and because this is the section the
          price-history chart joins after UAT (§2 of the procurement requirements puts the chart out of
          this build). It will sit under the market rate, in this card, at the card's full width. */}
      <h2 id="market-rate-heading" className="text-lg font-semibold text-ink">Price</h2>
      <p className="mt-1 max-w-prose text-sm text-ink-secondary">
        The market rate is what it would cost to buy today.
      </p>

      {/* The line and its button side by side; the button drops under it only on a narrow phone. */}
      <div className="mt-4 flex flex-wrap items-center gap-x-6 gap-y-3">
        <div className="min-w-0">
          <p className="tabular-nums text-ink" data-testid="market-rate-line">
            {rate === null
              ? "Market rate · not set"
              : `Market rate · ${readableRate(rate, ingredient.unit)}${
                  ingredient.marketRateOn ? ` · ${shortDate(ingredient.marketRateOn)}` : ""
                }`}
          </p>
          {rate !== null && ingredient.marketRateSource && (
            <p className="mt-1 text-sm text-ink-secondary">{SOURCE_WORDS[ingredient.marketRateSource]}</p>
          )}
        </div>
        {canEdit && !editing && (
          <Button variant="secondary" onClick={open}>
            {rate === null ? "Set market rate" : "Change market rate"}
          </Button>
        )}
      </div>

      {canEdit && editing && (
        <Form className="mt-4" aria-label="Change market rate" onSubmit={save}>
          <div className="flex flex-wrap items-end gap-4">
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">{label}</span>
              <input
                type="number"
                inputMode="decimal"
                min="0"
                step="any"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                aria-invalid={blank || undefined}
                autoFocus
                className={`${BOX} w-36 tabular-nums ${blank ? "border-danger" : ""}`}
              />
            </label>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" busy={busy}>Save</Button>
              <Button variant="secondary" onClick={() => setEditing(false)}>Cancel</Button>
            </div>
          </div>
          {blank && <p className="mt-2 pl-field-inset text-sm text-danger">Market rate must be more than 0</p>}
        </Form>
      )}

      {error && <div className="mt-4"><ErrorNotice error={error} /></div>}
    </section>
  );
}
