"use client";

import { useState } from "react";
import { Button } from "@/components/ds/Button";
import { ErrorNotice } from "@/components/ErrorNotice";
import { BusyPot } from "@/components/Loading";
import { TemplePicker } from "@/components/TemplePicker";
import { api, setActiveTempleId, toApiError, type ApiError, type TempleSummary } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * The half of registering that is ours rather than Firebase's: which temple, and what they need to
 * know about you.
 *
 * <p>Firebase proves an email or a phone and nothing else. A temple needs a name to recognise
 * someone by on a shift and a number to reach them on, so both are asked for however the person
 * signed in — with whatever Google already told us filled in.
 */
export function JoinTempleForm({
  onJoined,
  submitLabel = "Join this temple",
  pickerLabel,
}: {
  onJoined: () => void;
  submitLabel?: string;
  pickerLabel?: string;
}) {
  const { user, getToken, refresh } = useAuth();

  const [temple, setTemple] = useState<TempleSummary | null>(null);
  const [firstName, setFirstName] = useState(guessFirst(user?.displayName));
  const [lastName, setLastName] = useState(guessLast(user?.displayName));
  const [phone, setPhone] = useState(user?.phoneNumber ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const ready = temple && firstName.trim() && lastName.trim() && /^\+[1-9][0-9]{7,14}$/.test(phone.trim());

  async function join() {
    if (!temple) return;
    setBusy(true);
    setError(null);
    try {
      await api.joinTemple(
        temple.id,
        {
          firstName: firstName.trim(),
          lastName: lastName.trim(),
          phone: phone.trim(),
          email: user?.email ?? null,
        },
        await getToken()
      );
      // The temple they just joined is the one the next request should speak for.
      setActiveTempleId(temple.id);
      await refresh();
      onJoined();
    } catch (e) {
      setError(toApiError(e, "We couldn’t add you to that temple."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-5">
      {error && <ErrorNotice error={error} />}

      <TemplePicker token={undefined} value={temple} onChange={setTemple} label={pickerLabel} />

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="grid gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">First name</span>
          <input
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
            className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
          />
        </label>
        <label className="grid gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Last name</span>
          <input
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
            className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
          />
        </label>
      </div>

      <label className="grid gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Phone</span>
        <input
          type="tel"
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="+91 98765 43210"
          className="min-h-touch rounded border border-hairline bg-canvas px-3 text-ink"
        />
        <span className="pl-field-inset text-xs text-ink-muted">
          With the country code.
        </span>
      </label>

      <Button disabled={!ready || busy} onClick={join} busy={busy}>
        {busy ? (
          <span className="inline-flex items-center gap-2">
            <BusyPot />
            Joining…
          </span>
        ) : (
          submitLabel
        )}
      </Button>
    </div>
  );
}

/** Google gives one display name; a temple's list wants two. Split once, and let them correct it. */
function guessFirst(displayName: string | null | undefined): string {
  if (!displayName) return "";
  return displayName.trim().split(/\s+/)[0] ?? "";
}

function guessLast(displayName: string | null | undefined): string {
  if (!displayName) return "";
  const parts = displayName.trim().split(/\s+/);
  return parts.length > 1 ? parts.slice(1).join(" ") : "";
}
