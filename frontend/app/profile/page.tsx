"use client";

import { useEffect, useRef, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import {
  api,
  toApiError,
  type ApiError,
  type CommunicationPreferencesView,
  type NotificationChannel,
  type Profile,
} from "@/lib/api";
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

const CHANNELS: { value: NotificationChannel; label: string }[] = [
  { value: "WHATSAPP", label: "WhatsApp" },
  { value: "SMS", label: "SMS" },
  { value: "EMAIL", label: "Email" },
];

export default function ProfilePage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF", "VOLUNTEER"]}>
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
        <p className="mt-1 text-sm text-ink-secondary">Where reminders reach you by default.</p>
        <fieldset className="mt-4 space-y-2" disabled={saving}>
          <legend className="sr-only">Choose a preferred channel</legend>
          {CHANNELS.map((channel) => (
            <label
              key={channel.value}
              className="flex items-center gap-3 rounded border border-hairline px-4 py-3"
            >
              <input
                type="radio"
                name="preferredChannel"
                value={channel.value}
                checked={profile.preferredChannel === channel.value}
                onChange={() => chooseChannel(channel.value)}
                className=""
              />
              <span>{channel.label}</span>
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

      <CommunicationPreferences />
    </Chrome>
  );
}

/**
 * Which kinds of message a devotee wants (E8-S1).
 *
 * <p>The category nobody can turn off is listed here too, without a switch. A preferences screen
 * that hides what you cannot change is a screen that makes people wonder what else it is not
 * telling them — and the sentence beside it is what stops somebody muting the temple entirely out
 * of fear of losing the reminder for the shift they promised to work.
 */
function CommunicationPreferences() {
  const { getToken } = useAuth();
  const [prefs, setPrefs] = useState<CommunicationPreferencesView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Ref-guarded, and it has to be. `getToken` is a fresh closure on every render of the auth
  // context, so naming it as a dependency means the effect re-runs on every render — which sets
  // state, which renders, which re-runs the effect. That loop does not merely spin: it exhausts
  // the heap, which is how it announced itself the first time (vitest, out of memory).
  const loaded = useRef(false);

  useEffect(() => {
    if (loaded.current) return;
    loaded.current = true;
    let cancelled = false;
    (async () => {
      try {
        const current = await api.communicationPreferences(await getToken());
        if (!cancelled) setPrefs(current);
      } catch (e) {
        if (!cancelled) setError(toApiError(e, "We couldn't load your message settings."));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [getToken]);

  async function change(input: { allOptional?: boolean; category?: never | string; wanted?: boolean }) {
    setBusy(true);
    setError(null);
    try {
      setPrefs(await api.setCommunicationPreference(input as never, await getToken()));
    } catch (e) {
      setError(toApiError(e, "We couldn't save that."));
    } finally {
      setBusy(false);
    }
  }

  if (!prefs) return null;

  const optional = prefs.categories.filter((c) => c.optional);
  const always = prefs.categories.filter((c) => !c.optional);

  return (
    <section className="mt-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="comms-heading">
      <h2 id="comms-heading" className="text-lg">
        Communications
      </h2>
      <p className="mt-1 max-w-prose text-sm text-ink-secondary">
        Choose what your temple may write to you about. Turning something off here never affects your
        shift reminders or the confirmations of what you have given.
      </p>

      {error && <p className="mt-3 text-sm text-danger">{error.message}</p>}

      <fieldset className="mt-4 grid gap-2" disabled={busy}>
        <legend className="sr-only">Kinds of message you can decline</legend>
        {optional.map((category) => (
          <label
            key={category.value}
            className="flex items-start gap-3 rounded border border-hairline px-4 py-3"
          >
            <input
              type="checkbox"
              checked={category.subscribed}
              onChange={() => change({ category: category.value, wanted: !category.subscribed })}
              className="mt-1"
            />
            <span>
              <span className="text-ink">{category.label}</span>
              <span className="block text-sm text-ink-secondary">{category.description}</span>
            </span>
          </label>
        ))}
      </fieldset>

      {always.map((category) => (
        <div
          key={category.value}
          className="mt-2 flex items-start gap-3 rounded border border-hairline bg-sunken px-4 py-3"
        >
          <span aria-hidden="true" className="mt-0.5 text-success">
            ✓
          </span>
          <span>
            <span className="text-ink">{category.label}</span>
            <span className="block text-sm text-ink-secondary">{category.description}</span>
          </span>
        </div>
      ))}

      <label className="mt-4 flex items-start gap-3 text-sm">
        <input
          type="checkbox"
          checked={prefs.optedOutOfAll}
          disabled={busy}
          onChange={() => change({ allOptional: prefs.optedOutOfAll })}
          className="mt-1"
        />
        <span>
          <span className="text-ink">Stop all optional messages</span>
          <span className="block text-ink-secondary">
            Everything above, off at once. Turning this back off restores exactly the choices you had
            made, not all of them.
          </span>
        </span>
      </label>
    </section>
  );
}

function formatDate(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return iso;
  return parsed.toLocaleDateString(undefined, { day: "numeric", month: "long", year: "numeric" });
}
