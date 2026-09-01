"use client";

import { useEffect, useRef, useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Dropping a vendor, or bringing one back, with the reason written down.
 *
 * <p>Deactivating used to be a single click on a row. It is not that kind of act: months later
 * somebody stands in front of the same list wondering whether this supplier can be used again, and
 * the only honest answer is the one whoever dropped them wrote at the time. So the click opens this
 * instead, and the commit button stays refused until there are words in the box — the server refuses
 * a blank one too (KMS-4011), and this is only the earlier, kinder half of the same rule.
 *
 * <p>Coming back is the other way round. The reason is offered and never demanded: restoring a
 * supplier explains itself, and asking somebody to justify a decision that undoes a harm is how a
 * field ends up with "n/a" in it.
 */
export function VendorStatusDialog({
  vendor,
  onCancel,
  onDone,
}: {
  vendor: { id: string; name: string; active: boolean };
  onCancel: () => void;
  /** The change committed; the caller reloads. */
  onDone: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [reason, setReason] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);

  // The one thing on the screen now, so the cursor belongs in it rather than wherever the click was.
  useEffect(() => field.current?.focus(), []);

  // Escape cancels, as it does anywhere a panel covers what somebody was reading.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  const dropping = vendor.active;
  const written = reason.trim();

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const token = await getToken();
      if (dropping) {
        await api.deactivateVendor(vendor.id, written, token);
      } else {
        await api.reactivateVendor(vendor.id, written === "" ? null : written, token);
      }
      onDone();
    } catch (e) {
      setError(
        toApiError(
          e,
          dropping ? "We couldn’t make that vendor inactive." : "We couldn’t bring that vendor back."
        )
      );
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="vendor-status-title"
    >
      <form
        onSubmit={submit}
        aria-label={dropping ? "Make this vendor inactive" : "Bring this vendor back"}
        className="w-full max-w-prose rounded-lg border border-hairline bg-canvas px-8 py-7"
      >
        <h2 id="vendor-status-title" className="text-lg">
          {dropping ? `Make ${vendor.name} inactive?` : `Bring ${vendor.name} back?`}
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          {dropping
            ? "They come off the pickers and off the shopping list’s suggestions. Every order and invoice already on their name stays exactly as it is."
            : "They go back on the pickers and can be suggested again. What was written when they were dropped stays on their page."}
        </p>

        <label className="mt-5 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">
            {dropping ? "Why are they being dropped?" : "Anything to add? (optional)"}
          </span>
          <textarea
            ref={field}
            name="reason"
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            required={dropping}
            className="rounded border border-hairline bg-canvas px-3 py-2"
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            {dropping
              ? "Whoever considers bringing them back reads this. It is kept with your name and today’s date, and never overwritten."
              : "Kept with your name and today’s date, alongside the reason they were dropped."}
          </span>
        </label>

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant={dropping ? "danger" : "primary"}
            busy={busy}
            disabled={dropping && written === ""}
          >
            {dropping ? "Make inactive" : "Bring back"}
          </Button>
        </div>
      </form>
    </div>
  );
}
