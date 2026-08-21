"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api, toApiError, type ApiError } from "@/lib/api";

/**
 * Stopping the messages, without signing in (E8-S1).
 *
 * <p>Public, and it has to be: somebody who wants a temple to stop writing to them is the least
 * likely person to go and find their password. The token in the link is what stands in for a
 * session — it is signed, so it cannot be forged, and it authorises one thing for one person.
 *
 * <p>Nothing happens on load. Mail scanners and link previewers follow every URL in an email, and a
 * page that unsubscribed on arrival would quietly remove people who never clicked anything. So the
 * page describes what it would do and waits for a press.
 *
 * <p>The one sentence that has to be here: turning this off does not touch shift reminders or
 * receipts. Without it people assume the worst and stay subscribed to everything out of fear of
 * losing the thing they actually rely on.
 */
export default function UnsubscribePage() {
  return (
    <Suspense fallback={<Frame>Loading…</Frame>}>
      <Unsubscribe />
    </Suspense>
  );
}

function Unsubscribe() {
  const token = useSearchParams().get("token") ?? "";
  const [label, setLabel] = useState<string | null>(null);
  const [valid, setValid] = useState<boolean | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const described = await api.describeUnsubscribe(token);
        if (!cancelled) {
          setValid(described.valid);
          setLabel(described.label ?? null);
        }
      } catch {
        if (!cancelled) setValid(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [token]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      const result = await api.unsubscribe(token);
      setDone(result.done);
      if (result.label) setLabel(result.label);
    } catch (e) {
      setError(toApiError(e, "We couldn’t change that just now."));
    } finally {
      setBusy(false);
    }
  }

  if (valid === null) {
    return <Frame>Checking that link…</Frame>;
  }

  if (!valid) {
    return (
      <Frame title="That link doesn’t work">
        <p className="text-ink-secondary">
          It may have been copied incompletely. Change what you receive on your account page.
        </p>
      </Frame>
    );
  }

  if (done) {
    return (
      <Frame title="Done">
        <p className="text-ink-secondary">
          You will no longer receive <strong className="text-ink">{label}</strong>.
        </p>
        <p className="mt-4 text-sm text-ink-secondary">
          Shift reminders and giving confirmations always reach you. To turn this back on, open
          your account page.
        </p>
      </Frame>
    );
  }

  return (
    <Frame title="Stop receiving these?">
      <p className="text-ink-secondary">
        This will stop <strong className="text-ink">{label}</strong> from your temple.
      </p>
      <p className="mt-3 text-sm text-ink-secondary">
        Shift reminders and giving confirmations keep reaching you. Those are never turned off.
      </p>

      {error && <p className="mt-4 text-sm text-danger">{error.message}</p>}

      <button
        type="button"
        onClick={confirm}
        disabled={busy}
        className="mt-6 min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
      >
        {busy ? "Just a moment…" : "Yes, stop these"}
      </button>
    </Frame>
  );
}

function Frame({ title, children }: { title?: string; children: React.ReactNode }) {
  return (
    <main className="mx-auto flex min-h-screen max-w-xl flex-col justify-center px-6 py-16">
      <div className="rounded-lg bg-raised px-8 py-10">
        {title && <h1 className="mb-4 text-2xl font-semibold text-ink">{title}</h1>}
        {children}
      </div>
    </main>
  );
}
