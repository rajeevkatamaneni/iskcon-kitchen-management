"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { openCheckout, type CheckoutOutcome } from "@/lib/checkout";
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
type DonorPath = "named" | "80g";

/**
 * A signed-in devotee's way of giving: a token, and no questions about who they are.
 *
 * <p>No longer nullable, as of 2026-08-29. There is nobody else on this page: giving requires an
 * account, and `RequireRole` on the route sees to it before this component renders.
 */
type Account = { getToken: () => Promise<string | undefined>; name?: string | null };

/**
 * The payment window closed without a payment. Not an error, and not phrased as one — a devotee is
 * allowed to think better of it, and nothing has happened to them.
 */
const DISMISSED = "No payment was taken. Your gift is still here whenever you are ready.";

/**
 * The temple has no gateway configured, so the server fell back to one that cannot charge anyone.
 * Better to say so than to open a window that takes nothing and calls it a donation.
 */
const UNAVAILABLE =
  "This temple cannot take online payments just yet. Please speak to the temple office — they will be glad to help.";

/**
 * Giving, from inside the application.
 *
 * <p>This had a second caller until 2026-08-29: a public page at `/t/{slug}/donate` that a stranger
 * could open from a shared link, with its own banner, its own name and email fields, and its own
 * consent tick. It is gone, and with it the `standalone` and `slug` props — the temple is now read
 * from the token like everything else, and there is only one way to reach this component.
 */
export function DonatePage() {
  const { appUser, getToken } = useAuth();
  const [page, setPage] = useState<DonationPageInfo | null>(null);
  const [items, setItems] = useState<WishlistItemView[]>([]);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [tab, setTab] = useState<Tab>("money");

  // The devotee giving is already known to the temple, so nothing is asked for that the temple
  // already holds.
  const account: Account = { getToken, name: appUser?.fullName };

  // Loaded once, on arrival. `getToken` is deliberately not a dependency and is reached through a
  // ref instead: it is a new function on every render of the auth context, so depending on it turns
  // this into a fetch on every render — a loop that is invisible in the browser, where the context
  // memoises, and hangs the test suite outright, where it does not. Nothing here changes while
  // somebody is looking at it anyway.
  const getTokenRef = useRef(getToken);
  getTokenRef.current = getToken;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const token = await getTokenRef.current();
      api
        .givingPage(token)
        .then((p) => !cancelled && setPage(p))
        .catch((e) => !cancelled && setLoadError(toApiError(e, "We couldn’t load this page.")));
      api
        .givingWishlist(token)
        .then((w) => !cancelled && setItems(w))
        .catch(() => undefined);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (loadError) {
    return (
      <main className="mx-auto max-w-content px-6 py-16">
        <h1 className="text-2xl font-semibold text-ink">We couldn’t load this</h1>
        <p className="mt-2 text-ink-secondary">{loadError.message}</p>
      </main>
    );
  }
  if (!page) {
    return <Loading />;
  }

  return (
    <div className="min-h-screen bg-canvas">
      {/* The headline is one line at every width, so the size is read from the space there is
          rather than the window — inside the app the menu takes 16rem of it, and a viewport unit
          would not know that. The floor matters as much as the ceiling: the menu does not yet give
          that space back on a narrow screen, and a size with no lower bound answered by shrinking
          the sentence to nothing. */}
      <main className="mx-auto grid max-w-content gap-8 px-6 py-12 [container-type:inline-size]">
        <section className="grid gap-4">
          <h1 className="whitespace-nowrap text-[clamp(1.125rem,6.2cqi,2.25rem)] font-semibold leading-tight text-ink">
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
              ["money", "Donate money"],
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

        {tab === "money" ? (
          <MoneyTab page={page} account={account} />
        ) : (
          <EquipmentTab templeName={page.templeName} items={items} account={account} />
        )}
      </main>
    </div>
  );
}

// ---- Money ---------------------------------------------------------------

function MoneyTab({
  page,
  account,
}: {
  page: DonationPageInfo;
  account: Account;
}) {
  const presets = page.presets?.length ? page.presets : [500, 1100, 2500, 5000];
  const [monthly, setMonthly] = useState(false);
  const [amount, setAmount] = useState<number>(presets[1] ?? presets[0]);
  const [other, setOther] = useState("");
  const [path, setPath] = useState<DonorPath>("named");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
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
    setNotice(null);
    try {
      // The temple already holds this devotee's name and email, so neither is asked for nor sent —
      // the server reads the donor from the token. Address and PAN it does not hold, so an 80G
      // receipt still asks for those. Monthly is a mandate of its own, not a gift that happens to
      // repeat, so it goes to the recurring plan rather than the one-time pipeline.
      const token = await account.getToken();
      const eightyG = {
        wants80g: path === "80g",
        address: path === "80g" ? String(f.get("address") ?? "") : undefined,
        pan: path === "80g" ? String(f.get("pan") ?? "") : undefined,
      };
      if (monthly) {
        // A standing mandate is authorised on the provider's own page, not in a window over ours:
        // the donor is agreeing to future charges, and that agreement is the provider's to take.
        const plan = await api.startRecurringPlan(given, eightyG, token);
        if (!plan.shortUrl) {
          setNotice(UNAVAILABLE);
          return;
        }
        window.location.assign(plan.shortUrl);
        return;
      }
      const outcome: CheckoutOutcome = await openCheckout(
        await api.giveOnce(given, eightyG, token),
        {
          templeName: page.templeName,
          description: "Donation to the kitchen",
          name: account.name,
        }
      );

      if (outcome === "paid") {
        setDone(true);
      } else {
        setNotice(outcome === "dismissed" ? DISMISSED : UNAVAILABLE);
      }
    } catch (e) {
      setError(toApiError(e, "We couldn’t start your donation."));
    } finally {
      setBusy(false);
    }
  }

  // Only a one-time gift lands here: a monthly mandate leaves the page for the provider's own.
  if (done) {
    return (
      <section className="rounded-lg bg-raised px-8 py-10">
        <h2 className="text-xl font-semibold text-ink">Thank you</h2>
        <p className="mt-2 text-ink-secondary">
          Your payment of ₹{given.toLocaleString("en-IN")} went through, and your gift is on its way
          to the kitchen. Your receipt follows once the bank confirms it — usually within a minute.
        </p>
      </section>
    );
  }

  return (
    <div className="grid items-start gap-6 lg:grid-cols-[1.6fr_1fr]">
      <form onSubmit={submit} className="grid gap-6 rounded-lg bg-raised px-8 py-7">
        <p className="text-ink-secondary">
          The kitchen buys what that week’s menus are short of.
          {page.costPerPlateInr != null && (
            <> ₹{page.costPerPlateInr.toLocaleString("en-IN")} covers one plate of prasadam.</>
          )}
        </p>

        {/* Monthly giving is a mandate against a person, so it is offered to a devotee with an
            account and not to a stranger we would have nowhere to keep. */}
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
            <span className="pl-field-inset font-medium text-ink">Or another amount</span>
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
              <span className="pl-field-inset font-medium text-ink">Address</span>
              <input name="address" className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink" />
            </label>
            <label className="grid gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">PAN</span>
              <input name="pan" placeholder="ABCDE1234F" className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink" />
            </label>
          </div>
        )}

        {error && <p className="text-sm text-danger">{error.message}</p>}
        {notice && <p className="text-sm text-ink-secondary" role="status">{notice}</p>}

        <button
          type="submit"
          disabled={busy || given <= 0}
          className="min-h-touch rounded-lg bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy ? "Just a moment…" : `Give ₹${given.toLocaleString("en-IN")}${monthly ? " a month" : ""}`}
        </button>

        {monthly && (
          <p className="text-center text-xs text-ink-muted">
            A monthly gift can be stopped from any receipt email.
          </p>
        )}
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

function EquipmentTab({
  templeName,
  items,
  account,
}: {
  templeName: string;
  items: WishlistItemView[];
  account: Account;
}) {
  const open = useMemo(() => items.filter((i) => i.status !== "ARCHIVED"), [items]);

  if (open.length === 0) {
    return (
      <section className="rounded-lg bg-raised px-8 py-10">
        <h2 className="text-lg font-medium text-ink">Nothing on the list just now</h2>
        <p className="mt-2 text-ink-secondary">
          Equipment the kitchen needs appears here with what it costs.
        </p>
      </section>
    );
  }

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {open.map((item) => (
        <EquipmentCard key={item.id} templeName={templeName} item={item} account={account} />
      ))}
    </div>
  );
}

function EquipmentCard({
  templeName,
  item,
  account,
}: {
  templeName: string;
  item: WishlistItemView;
  account: Account;
}) {
  const [busy, setBusy] = useState(false);
  const [given, setGiven] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const cost = Number(item.priceInr) * Math.max(1, item.quantityWanted);
  const paid = Number(item.paidInr ?? 0);
  const outstanding = Math.max(0, cost - paid);
  const covered = outstanding <= 0;
  const filled = cost > 0 ? Math.min(100, Math.round((paid / cost) * 100)) : 0;

  async function give(amount: number) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const checkout = await api.giveTowardsItem(item.id, amount, undefined, await account.getToken());
      const outcome = await openCheckout(checkout, {
        templeName,
        description: item.title,
        name: account?.name,
      });
      if (outcome === "paid") {
        setGiven(true);
      } else {
        setNotice(outcome === "dismissed" ? DISMISSED : UNAVAILABLE);
      }
    } catch (e) {
      setError(toApiError(e, "We couldn’t take that just now."));
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
          This one is covered. Money given funds whatever comes next.
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
      {notice && <p className="text-sm text-ink-secondary" role="status">{notice}</p>}
    </section>
  );
}
