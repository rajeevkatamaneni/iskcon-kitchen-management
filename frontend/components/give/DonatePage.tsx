"use client";

import { useEffect, useMemo, useState } from "react";
import { Loading } from "@/components/Loading";
import {
  api,
  toApiError,
  type ApiError,
  type DonationPageInfo,
  type WishlistItemView,
} from "@/lib/api";

/**
 * Giving to a temple's kitchen, as the design system draws it: one page, one question at a time.
 *
 * <p>Money, or a thing the kitchen wants. Both end in the same donation pipeline, and the equipment
 * side is deliberately not all-or-nothing — a temple buys a grinder whole, so what a devotee puts
 * towards one is money, any amount of it, and the bar fills as the money arrives.
 *
 * <p>Every figure on this page is the temple's own: plates from today's plan, the cost of a plate
 * from last month's spend, the shares from what was actually bought. Where the temple has not done
 * enough for a figure to mean anything, the sentence is left out rather than filled in.
 */

type Tab = "money" | "equipment";
type DonorPath = "anonymous" | "named" | "80g";

export function DonatePage({ slug }: { slug: string }) {
  const [page, setPage] = useState<DonationPageInfo | null>(null);
  const [items, setItems] = useState<WishlistItemView[]>([]);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [tab, setTab] = useState<Tab>("money");

  useEffect(() => {
    let cancelled = false;
    api
      .donationPage(slug)
      .then((p) => !cancelled && setPage(p))
      .catch((e) => !cancelled && setLoadError(toApiError(e, "We couldn't load this page.")));
    api
      .publicWishlist(slug)
      .then((w) => !cancelled && setItems(w))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [slug]);

  if (loadError) {
    return (
      <main className="mx-auto max-w-content px-6 py-16">
        <h1 className="text-2xl font-semibold text-ink">Temple not found</h1>
        <p className="mt-2 text-ink-secondary">{loadError.message}</p>
      </main>
    );
  }
  if (!page) {
    return <Loading />;
  }

  return (
    <div className="min-h-screen bg-canvas">
      <header className="border-b border-hairline px-6 py-5">
        <div className="mx-auto flex max-w-content items-center gap-4">
          <img src="/brand/iskcon-icon.svg" alt="" aria-hidden className="h-10 w-10 object-contain" />
          <span className="grid">
            <span className="font-medium text-ink">{page.templeName} kitchen</span>
            {page.platesToday != null && (
              <span className="text-sm text-ink-muted">
                Serving {page.platesToday.toLocaleString("en-IN")} plates of prasadam today
              </span>
            )}
          </span>
        </div>
      </header>

      <main className="mx-auto grid max-w-content gap-8 px-6 py-12">
        <section className="grid gap-4">
          <h1 className="max-w-[18ch] text-4xl font-semibold leading-tight text-ink">
            No one leaves this temple hungry.
          </h1>
          <p className="max-w-prose text-lg text-ink-secondary">
            Every plate served here is cooked with love and care by our wonderful staff and devotees.
            You can give money, or donate money towards equipment the kitchen needs.
          </p>
        </section>

        <div role="tablist" aria-label="Ways to give" className="flex w-fit gap-1 rounded-lg bg-sunken p-1">
          {(
            [
              ["money", "Donate Money"],
              ["equipment", "Equipment the kitchen wants"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              role="tab"
              type="button"
              aria-selected={tab === value}
              onClick={() => setTab(value)}
              className={[
                "min-h-touch rounded px-5 text-sm transition-colors duration-state",
                tab === value ? "bg-canvas font-medium text-ink shadow-sm" : "text-ink-secondary",
              ].join(" ")}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === "money" ? <MoneyTab slug={slug} page={page} /> : <EquipmentTab slug={slug} items={items} />}
      </main>
    </div>
  );
}

// ---- Money ---------------------------------------------------------------

function MoneyTab({ slug, page }: { slug: string; page: DonationPageInfo }) {
  const presets = page.presets?.length ? page.presets : [500, 1100, 2500, 5000];
  const [monthly, setMonthly] = useState(false);
  const [amount, setAmount] = useState<number>(presets[1] ?? presets[0]);
  const [other, setOther] = useState("");
  const [path, setPath] = useState<DonorPath>("named");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [done, setDone] = useState(false);

  const given = other.trim() ? Number(other) || 0 : amount;
  const plates = page.costPerPlateInr && given > 0
    ? Math.floor(given / page.costPerPlateInr)
    : null;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const donor = {
        anonymous: path === "anonymous",
        name: path === "anonymous" ? undefined : String(f.get("name") ?? ""),
        email: path === "anonymous" ? undefined : String(f.get("email") ?? "") || undefined,
        phone: path === "anonymous" ? undefined : String(f.get("phone") ?? "") || undefined,
        address: path === "80g" ? String(f.get("address") ?? "") : undefined,
        pan: path === "80g" ? String(f.get("pan") ?? "") : undefined,
        wants80g: path === "80g",
        consent: path === "anonymous" ? false : f.get("consent") === "on",
      };
      // Monthly giving rides the same pipeline; the plan is created from the completed donation.
      await api.donate(slug, given, { ...donor, monthly });
      setDone(true);
    } catch (e) {
      setError(toApiError(e, "We couldn't start your donation."));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <section className="rounded-lg bg-raised px-8 py-10">
        <h2 className="text-xl font-semibold text-ink">Thank you</h2>
        <p className="mt-2 text-ink-secondary">
          {monthly
            ? "Your monthly giving is set up. The kitchen will know it can count on it."
            : "Your gift is on its way to the kitchen."}
        </p>
      </section>
    );
  }

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[1.6fr_1fr]">
      <form onSubmit={submit} className="grid gap-6 rounded-lg bg-raised px-8 py-7">
        <p className="text-ink-secondary">
          The kitchen buys what that week&rsquo;s menus are short of.
          {page.costPerPlateInr != null && (
            <> ₹{page.costPerPlateInr.toLocaleString("en-IN")} covers one plate of prasadam.</>
          )}
        </p>

        <fieldset className="grid gap-2">
          <legend className="mb-1 text-sm font-medium text-ink">How often</legend>
          {[
            [false, "One time"],
            [true, "Every month"],
          ].map(([value, label]) => (
            <label key={String(label)} className="flex min-h-touch items-center gap-3 text-ink">
              <input
                type="radio"
                name="howOften"
                checked={monthly === value}
                onChange={() => setMonthly(Boolean(value))}
                className="h-4 w-4 accent-accent"
              />
              {label}
            </label>
          ))}
        </fieldset>

        <fieldset className="grid gap-2">
          <legend className="mb-1 text-sm font-medium text-ink">Amount</legend>
          <div className="flex flex-wrap gap-2">
            {presets.map((preset) => (
              <button
                key={preset}
                type="button"
                onClick={() => {
                  setAmount(preset);
                  setOther("");
                }}
                aria-pressed={!other && amount === preset}
                className={[
                  "min-h-touch rounded-lg border px-5 text-sm tabular-nums transition-colors duration-state",
                  !other && amount === preset
                    ? "border-accent bg-accent text-ink-inverse"
                    : "border-hairline-strong bg-canvas text-accent-text hover:bg-sunken",
                ].join(" ")}
              >
                ₹{preset.toLocaleString("en-IN")}
              </button>
            ))}
          </div>
          <label className="mt-2 grid gap-1 text-sm text-ink-secondary">
            Or another amount
            <span className="relative flex">
              <input
                inputMode="numeric"
                value={other}
                onChange={(e) => setOther(e.target.value)}
                placeholder="0"
                className="min-h-touch w-full rounded border border-hairline bg-canvas px-3 pr-8 text-ink tabular-nums"
              />
              <span aria-hidden className="absolute inset-y-0 right-3 grid place-items-center text-ink-muted">₹</span>
            </span>
          </label>
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-2">
          <label className="grid gap-1 text-sm text-ink-secondary">
            Your name
            <input
              name="name"
              autoComplete="name"
              className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
            />
          </label>
          <label className="grid gap-1 text-sm text-ink-secondary">
            Email
            <input
              name="email"
              type="email"
              autoComplete="email"
              className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
            />
          </label>
        </div>

        {page.is80gApproved && (
          <label className="flex items-center gap-3 text-sm text-ink-secondary">
            <input
              type="checkbox"
              checked={path === "80g"}
              onChange={(e) => setPath(e.target.checked ? "80g" : "named")}
              className="h-4 w-4 accent-accent"
            />
            I would like an 80G receipt
          </label>
        )}

        {path === "80g" && (
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="grid gap-1 text-sm text-ink-secondary">
              Address
              <input name="address" className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink" />
            </label>
            <label className="grid gap-1 text-sm text-ink-secondary">
              PAN
              <input name="pan" className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink" />
            </label>
          </div>
        )}

        {error && <p className="text-sm text-danger">{error.message}</p>}

        <button
          type="submit"
          disabled={busy || given <= 0}
          className="min-h-touch rounded-lg bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy ? "Just a moment…" : `Give ₹${given.toLocaleString("en-IN")}${monthly ? " a month" : ""}`}
        </button>
      </form>

      <aside className="grid gap-4">
        <section className="rounded-lg bg-raised px-6 py-5">
          <h2 className="text-sm text-ink-secondary">This gift</h2>
          <p className="mt-1 text-3xl font-semibold tabular-nums text-ink">
            ₹{given.toLocaleString("en-IN")}
          </p>
          {plates != null && plates > 0 && (
            <p className="mt-1 text-sm text-ink-muted">
              About {plates.toLocaleString("en-IN")} plates of prasadam
            </p>
          )}
        </section>

        {(page.spendShares ?? []).length > 0 && (
          <section className="grid gap-3 rounded-lg bg-raised px-6 py-5">
            <div className="grid gap-1">
              <h2 className="text-lg font-medium text-ink">Where it goes</h2>
              <p className="text-xs text-ink-muted">Share of kitchen spending, last month</p>
            </div>
            {(page.spendShares ?? []).map((share) => (
              <div key={share.label} className="grid gap-1">
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="text-ink">{share.label}</span>
                  <span className="tabular-nums text-ink-secondary">{share.percent}%</span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-sunken">
                  <div className="h-full rounded-full bg-hairline-strong" style={{ width: `${share.percent}%` }} />
                </div>
              </div>
            ))}
          </section>
        )}
      </aside>
    </div>
  );
}

// ---- Equipment -----------------------------------------------------------

function EquipmentTab({ slug, items }: { slug: string; items: WishlistItemView[] }) {
  const open = useMemo(() => items.filter((i) => i.status !== "ARCHIVED"), [items]);

  if (open.length === 0) {
    return (
      <section className="rounded-lg bg-raised px-8 py-10">
        <h2 className="text-lg font-medium text-ink">Nothing on the list just now</h2>
        <p className="mt-2 text-ink-secondary">
          When the kitchen needs a piece of equipment, it appears here with what it costs.
        </p>
      </section>
    );
  }

  return (
    <div className="grid gap-6">
      <p className="max-w-prose text-ink-secondary">
        These are bought whole by the temple, so there is no half a grinder to donate. Put any amount
        towards one, or cover it outright.
      </p>
      <div className="grid gap-4 lg:grid-cols-2">
        {open.map((item) => (
          <EquipmentCard key={item.id} slug={slug} item={item} />
        ))}
      </div>
    </div>
  );
}

function EquipmentCard({ slug, item }: { slug: string; item: WishlistItemView }) {
  const [busy, setBusy] = useState(false);
  const [given, setGiven] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const cost = Number(item.priceInr) * Math.max(1, item.quantityWanted);
  const paid = Number(item.paidInr ?? 0);
  const outstanding = Math.max(0, cost - paid);
  const covered = outstanding <= 0;
  const filled = cost > 0 ? Math.min(100, Math.round((paid / cost) * 100)) : 0;

  async function give(amount: number) {
    setBusy(true);
    setError(null);
    try {
      await api.contributeToWishlistItem(slug, item.id, amount, {
        anonymous: true,
        wants80g: false,
        consent: false,
      });
      setGiven(true);
    } catch (e) {
      setError(toApiError(e, "We couldn't take that just now."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="grid gap-3 rounded-lg bg-raised px-6 py-5">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-lg font-medium text-ink">{item.title}</h3>
        <span
          className={[
            "rounded-full px-3 py-0.5 text-xs",
            covered ? "bg-success-bg text-success" : "bg-warning-bg text-warning",
          ].join(" ")}
        >
          {covered ? "Fully covered" : `₹${outstanding.toLocaleString("en-IN")} to go`}
        </span>
      </div>

      <p className="text-sm text-ink-muted">
        {item.description ? `${item.description} · ` : ""}₹{cost.toLocaleString("en-IN")} to buy
      </p>

      {/* Green is money in hand — the temple can buy against it. The rest is the gap. */}
      <div className="h-2 overflow-hidden rounded-full bg-sunken">
        <div className="h-full rounded-full bg-success" style={{ width: `${filled}%` }} />
      </div>
      <p className="text-sm">
        <span className="tabular-nums text-ink">₹{paid.toLocaleString("en-IN")} paid</span>
        <span className="ml-3 tabular-nums text-ink-muted">
          ₹{outstanding.toLocaleString("en-IN")} still needed
        </span>
      </p>

      {given ? (
        <p className="text-sm text-success">Thank you — the kitchen has been told.</p>
      ) : covered ? (
        <p className="text-sm text-ink-secondary">
          This one is covered. The money section funds whatever the kitchen needs next.
        </p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {[500, 2500, 5000]
            .filter((preset) => preset < outstanding)
            .map((preset) => (
              <button
                key={preset}
                type="button"
                disabled={busy}
                onClick={() => give(preset)}
                className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-4 text-sm text-accent-text transition-colors duration-state hover:bg-sunken disabled:opacity-60"
              >
                Give ₹{preset.toLocaleString("en-IN")}
              </button>
            ))}
          <button
            type="button"
            disabled={busy}
            onClick={() => give(outstanding)}
            className="min-h-touch rounded-lg bg-accent px-4 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
          >
            Cover the rest — ₹{outstanding.toLocaleString("en-IN")}
          </button>
        </div>
      )}

      {error && <p className="text-sm text-danger">{error.message}</p>}
    </section>
  );
}
