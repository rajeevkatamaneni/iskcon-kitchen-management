"use client";

import { useEffect, useState } from "react";
import { api, toApiError, type ApiError, type WishlistItemView } from "@/lib/api";
import { Loading } from "@/components/Loading";

/** A temple's wish list — the things its kitchen needs — shown publicly or inside the app. */
export function WishListView({ slug }: { slug: string }) {
  const [items, setItems] = useState<WishlistItemView[] | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [done, setDone] = useState(false);

  function load() {
    api.publicWishlist(slug)
      .then(setItems)
      .catch((e) => setLoadError(toApiError(e, "We couldn't load this wish list.")));
  }
  useEffect(load, [slug]);

  async function sponsor(item: WishlistItemView) {
    setBusy(true);
    setActionError(null);
    try {
      // Minimal public flow: sponsor one unit anonymously. In production the provider's checkout
      // opens with the returned order; a fuller donor form mirrors the donation page.
      await api.sponsor(slug, item.id, 1, {
        anonymous: true, wants80g: false, consent: false,
      });
      setDone(true);
    } catch (e) {
      setActionError(toApiError(e, "We couldn't start that sponsorship."));
    } finally {
      setBusy(false);
    }
  }

  if (loadError) {
    return <main className="mx-auto max-w-md px-6 py-16 text-center"><h1>Not found</h1><p className="mt-2 text-ink-secondary">{loadError.message}</p></main>;
  }
  const list = items ?? [];

  return (
    <main className="mx-auto max-w-content px-6 py-10">
      <header className="mb-6 text-center">
        <h1>Wish list</h1>
        <p className="mt-1 text-ink-secondary">Fund a concrete need — you&rsquo;ll know exactly what your gift provides.</p>
      </header>

      {actionError && <div className="mb-6"><div role="alert" className="rounded border border-danger/20 bg-danger-bg p-4 text-danger">{actionError.message}</div></div>}
      {done && <div className="mb-6 rounded border border-hairline bg-success-bg px-4 py-3 text-sm text-success">Thank you 🙏 Your sponsorship is being processed.</div>}

      {items === null ? (
        <Loading />
      ) : list.length === 0 ? (
        <p className="text-center text-ink-secondary">Nothing on the wish list right now.</p>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {list.map((item) => {
            const remaining = item.quantityWanted - item.sponsoredQuantity;
            const pct = item.quantityWanted > 0 ? Math.round((item.sponsoredQuantity / item.quantityWanted) * 100) : 0;
            return (
              <article key={item.id} className="flex flex-col rounded-lg bg-raised px-5 py-4">
                <h2 className="text-lg">{item.title}</h2>
                {item.description && <p className="mt-1 text-sm text-ink-secondary">{item.description}</p>}
                <p className="mt-2 tabular-nums">₹{item.priceInr}{item.quantityWanted > 1 ? " each" : ""}</p>
                {item.quantityWanted > 1 && (
                  <>
                    <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-sunken"><div className="h-full bg-accent" style={{ width: `${pct}%` }} /></div>
                    <p className="mt-1 text-xs text-ink-muted tabular-nums">{item.sponsoredQuantity}/{item.quantityWanted} sponsored</p>
                  </>
                )}
                <div className="mt-4">
                  {item.status === "FULFILLED" || remaining <= 0 ? (
                    <span className="rounded-sm bg-success-bg px-3 py-1 text-sm text-success">Fulfilled 🙏</span>
                  ) : (
                    <button type="button" disabled={busy} onClick={() => sponsor(item)} className="min-h-touch rounded bg-accent px-4 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
                      Sponsor
                    </button>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </main>
  );
}
