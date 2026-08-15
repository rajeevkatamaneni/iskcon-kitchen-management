"use client";

import { useEffect, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { api, toApiError, type ApiError, type NotificationChannel, type Profile } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { Loading } from "@/components/Loading";

/**
 * A user's own account (E1-S8): the channel they want reminders on, and their consent to be
 * contacted.
 *
 * <p>Contact details are shown but not editable here — changing a phone needs a fresh OTP and
 * changing an email collides with the sign-in identity, both a later increment. The preferred
 * channel and consent are the two things a user owns, so they are the two things this screen
 * lets them change. A platform operator has no temple and so no profile; this is for temple
 * roles only.
 */

const CHANNELS: { value: NotificationChannel; label: string; hint: string }[] = [
  { value: "WHATSAPP", label: "WhatsApp", hint: "Reminders on the number below. Best for most." },
  { value: "SMS", label: "SMS", hint: "A plain text message to your phone." },
  { value: "EMAIL", label: "Email", hint: "To the address below." },
];

export default function ProfilePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_STAFF", "VOLUNTEER"]}>
      <ProfileView />
    </RequireRole>
  );
}

function Chrome({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/profile" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Your account</h1>
            <p className="mt-1 text-ink-secondary">
              How your temple reaches you, and your consent to be contacted.
            </p>
          </header>
          {children}
        </div>
      </main>
    </div>
  );
}

function ProfileView() {
  const { getToken } = useAuth();
  const { data, error, loading } = useAuthedQuery(api.getProfile);

  // Read from the query, then own a local copy so a channel change or consent shows immediately
  // and each mutation returns the fresh Profile the backend computed (consent version, dates).
  const [profile, setProfile] = useState<Profile | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (data) setProfile(data);
  }, [data]);

  if (loading || (!profile && !error)) {
    return (
      <Chrome>
        <Loading label="Loading your account…" />
      </Chrome>
    );
  }
  if (error) {
    return (
      <Chrome>
        <ErrorNotice error={error} />
      </Chrome>
    );
  }
  if (!profile) return null;

  async function save(mutation: (token: string | undefined) => Promise<Profile>) {
    setSaving(true);
    setSaveError(null);
    try {
      const token = await getToken();
      setProfile(await mutation(token));
    } catch (e) {
      setSaveError(toApiError(e, "We couldn't save that change."));
    } finally {
      setSaving(false);
    }
  }

  function chooseChannel(channel: NotificationChannel) {
    if (!profile || channel === profile.preferredChannel) return;
    void save((token) => api.updatePreferredChannel(channel, token));
  }

  return (
    <Chrome>
      {saveError && (
        <div className="mb-6">
          <ErrorNotice error={saveError} />
        </div>
      )}

      <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="contact-heading">
        <h2 id="contact-heading" className="text-lg">
          Contact details
        </h2>
        <p className="mt-1 text-sm text-ink-secondary">
          Set when your account was created. Ask your temple administrator to change these.
        </p>
        <dl className="mt-4 grid grid-cols-[8rem_1fr] gap-y-3 text-sm">
          <dt className="text-ink-secondary">Name</dt>
          <dd>{profile.fullName || "—"}</dd>
          <dt className="text-ink-secondary">Email</dt>
          <dd>{profile.email || "—"}</dd>
          <dt className="text-ink-secondary">Phone</dt>
          <dd>{profile.phone || "—"}</dd>
        </dl>
      </section>

      <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="channel-heading">
        <h2 id="channel-heading" className="text-lg">
          Preferred channel
        </h2>
        <p className="mt-1 text-sm text-ink-secondary">
          Where reminders reach you by default. WhatsApp rides your phone number.
        </p>
        <fieldset className="mt-4 space-y-2" disabled={saving}>
          <legend className="sr-only">Choose a preferred channel</legend>
          {CHANNELS.map((channel) => (
            <label
              key={channel.value}
              className="flex items-start gap-3 rounded border border-hairline px-4 py-3"
            >
              <input
                type="radio"
                name="preferredChannel"
                value={channel.value}
                checked={profile.preferredChannel === channel.value}
                onChange={() => chooseChannel(channel.value)}
                className="mt-1"
              />
              <span>
                <span className="block">{channel.label}</span>
                <span className="block text-sm text-ink-secondary">{channel.hint}</span>
              </span>
            </label>
          ))}
        </fieldset>
      </section>

      <section className="rounded-lg bg-raised px-6 py-5" aria-labelledby="consent-heading">
        <h2 id="consent-heading" className="text-lg">
          Consent to be contacted
        </h2>
        <p className="mt-3 max-w-prose text-ink-secondary">{profile.consentText}</p>

        {profile.consentNeeded ? (
          <div className="mt-5 flex flex-wrap items-center gap-4">
            <button
              type="button"
              disabled={saving}
              onClick={() => save((token) => api.giveConsent(token))}
              className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
            >
              I agree
            </button>
            <span className="text-sm text-ink-muted">
              Until you agree, we won&apos;t send you reminders.
            </span>
          </div>
        ) : (
          <p className="mt-5 flex items-center gap-2 text-sm text-success">
            <span aria-hidden="true">✓</span>
            You agreed{profile.consentAt ? ` on ${formatDate(profile.consentAt)}` : ""}.
          </p>
        )}
      </section>
    </Chrome>
  );
}

function formatDate(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return iso;
  return parsed.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
}
