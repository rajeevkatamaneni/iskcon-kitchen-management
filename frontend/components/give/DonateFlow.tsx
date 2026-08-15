"use client";

import { useEffect, useState } from "react";
import { api, toApiError, type ApiError, type DonationPageInfo } from "@/lib/api";
import { Loading } from "@/components/Loading";

type DonorPath = "anonymous" | "named" | "80g";

/**
 * Giving to a temple's kitchen.
 *
 * <p>The same flow whether it is reached from a link somebody shared — where it stands alone — or
 * from a devotee's own menu, where it sits inside the app with the menu still beside them. A
 * volunteer who clicks Give should not be thrown out of the app to do it.
 */
export function DonateFlow({ slug }: { slug: string }) {
  const [page, setPage] = useState<DonationPageInfo | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [amount, setAmount] = useState<number | "">("");
  const [path, setPath] = useState<DonorPath>("named");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.donationPage(slug)
      .then((p) => { if (!cancelled) setPage(p); })
      .catch((e) => { if (!cancelled) setLoadError(toApiError(e, "We couldn't load this donation page.")); });
    return () => { cancelled = true; };
  }, [slug]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    setBusy(true);
    setActionError(null);
    try {
      await api.donate(slug, Number(amount), {
        anonymous: path === "anonymous",
        name: path === "anonymous" ? undefined : String(f.get("name") ?? ""),
        phone: path === "anonymous" ? undefined : String(f.get("phone") ?? "") || undefined,
        email: path === "anonymous" ? undefined : String(f.get("email") ?? "") || undefined,
        address: path === "80g" ? String(f.get("address") ?? "") : undefined,
        pan: path === "80g" ? String(f.get("pan") ?? "") : undefined,
        wants80g: path === "80g",
        consent: path === "anonymous" ? false : f.get("consent") === "on",
      });
      // In production the provider's hosted checkout opens here with the returned order and key.
      setDone(true);
    } catch (e) {
      setActionError(toApiError(e, "We couldn't start your donation."));
    } finally {
      setBusy(false);
    }
  }

  if (loadError) {
    return <Centered><h1>Temple not found</h1><p className="mt-2 text-ink-secondary">{loadError.message}</p></Centered>;
  }
  if (!page) {
    return <Centered><Loading /></Centered>;
  }
  if (done) {
    return (
      <Centered>
        <h1>Hare Krishna 🙏</h1>
        <p className="mt-2 text-ink-secondary">Thank you. Your donation to {page.templeName} is being processed; you&rsquo;ll receive a confirmation shortly.</p>
      </Centered>
    );
  }

  const presets = page.presets ?? [51, 501, 1001];

  return (
    <main className="mx-auto max-w-md px-6 py-10">
      <header className="mb-6 text-center">
        <p className="text-xs uppercase tracking-wide text-ink-muted">Donate to</p>
        <h1 className="mt-1">{page.templeName}</h1>
      </header>

      {actionError && <div className="mb-6"><ErrorBox error={actionError} /></div>}

      <form className="space-y-6" aria-label="Donate" onSubmit={submit}>
        <div>
          <p className="mb-2 text-sm font-medium">Amount</p>
          <div className="flex flex-wrap gap-2">
            {presets.map((p) => (
              <button key={p} type="button" onClick={() => setAmount(p)}
                className={`min-h-touch rounded-md px-4 tabular-nums ${amount === p ? "bg-accent text-ink-inverse" : "bg-raised"}`}>
                ₹{p}
              </button>
            ))}
          </div>
          <input type="number" min="1" step="any" value={amount} onChange={(e) => setAmount(e.target.value === "" ? "" : Number(e.target.value))}
            required placeholder="Other amount" aria-label="Amount in rupees"
            className="mt-3 min-h-touch w-full rounded border border-hairline bg-canvas px-3 tabular-nums" />
        </div>

        <fieldset>
          <legend className="mb-2 text-sm font-medium">Your details</legend>
          <div className="space-y-1 text-sm">
            <label className="flex items-center gap-2"><input type="radio" name="path" checked={path === "named"} onChange={() => setPath("named")} /> Give with my name</label>
            {page.is80gApproved && (
              <label className="flex items-center gap-2"><input type="radio" name="path" checked={path === "80g"} onChange={() => setPath("80g")} /> Give with an 80G tax certificate</label>
            )}
            <label className="flex items-center gap-2"><input type="radio" name="path" checked={path === "anonymous"} onChange={() => setPath("anonymous")} /> Give anonymously</label>
          </div>
          {path === "anonymous" && (
            <p className="mt-2 rounded bg-warning-bg px-3 py-2 text-xs text-warning">An anonymous gift keeps no personal details and can&rsquo;t receive an 80G certificate.</p>
          )}
        </fieldset>

        {path !== "anonymous" && (
          <div className="space-y-3">
            <Field name="name" label="Name" required />
            <Field name="phone" label="Phone" />
            <Field name="email" label="Email" type="email" />
            {path === "80g" && <>
              <Field name="address" label="Address" required />
              <Field name="pan" label="PAN" required placeholder="ABCDE1234F" />
              <p className="text-xs text-ink-muted">PAN is required by law for an 80G certificate and is stored encrypted.</p>
            </>}
            <label className="flex items-start gap-2 text-xs text-ink-secondary">
              <input type="checkbox" name="consent" className="mt-0.5" /> I agree that my details may be used to process this donation and issue a receipt.
            </label>
          </div>
        )}

        <button type="submit" disabled={busy || amount === ""} className="min-h-touch w-full rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60">
          Donate ₹{amount || "…"}
        </button>
      </form>
    </main>
  );
}

function Centered({ children }: { children: React.ReactNode }) {
  return <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6 text-center">{children}</main>;
}

function Field({ name, label, type = "text", required = false, placeholder }: { name: string; label: string; type?: string; required?: boolean; placeholder?: string }) {
  return (
    <label className="flex flex-col gap-1 text-sm text-ink-secondary">
      {label}
      <input name={name} type={type} required={required} placeholder={placeholder} className="min-h-touch rounded border border-hairline bg-canvas px-3" />
    </label>
  );
}

function ErrorBox({ error }: { error: ApiError }) {
  return (
    <div role="alert" className="rounded border border-danger/20 bg-danger-bg p-4 text-danger">
      <p className="font-medium">{error.message}</p>
      <p className="mt-1 text-sm">{error.action}</p>
    </div>
  );
}
