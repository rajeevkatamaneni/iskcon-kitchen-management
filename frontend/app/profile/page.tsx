import { Sidebar } from "@/components/Sidebar";
import { TEMPLE_NAV } from "@/lib/nav";
import type { NotificationChannel } from "@/lib/api";

/**
 * A user's own account (E1-S8): the channel they want reminders on, and their consent to be
 * contacted.
 *
 * <p>Contact details are shown but not editable here — changing a phone needs a fresh OTP and
 * changing an email collides with the sign-in identity, both a later increment. Like the other
 * screens this is the shape and the wording for now, filled from `api.getProfile` and driven by
 * `api.updatePreferredChannel` / `api.giveConsent` when the app is wired to live data.
 */

const CHANNELS: { value: NotificationChannel; label: string; hint: string }[] = [
  { value: "WHATSAPP", label: "WhatsApp", hint: "Reminders on the number below. Best for most." },
  { value: "SMS", label: "SMS", hint: "A plain text message to your phone." },
  { value: "EMAIL", label: "Email", hint: "To the address below." },
];

// Mirrors CommunicationConsent.TEXT on the backend; served as profile.consentText once wired.
const CONSENT_TEXT =
  "I agree that my temple may send me reminders and service messages — such as volunteer shift " +
  "reminders and order updates — by WhatsApp, SMS, or email, using the contact details on my " +
  "account. I can change my preferred channel or withdraw this consent at any time from my profile.";

export default function ProfilePage() {
  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Your account" items={TEMPLE_NAV} activeHref="/profile" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Your account</h1>
            <p className="mt-1 text-ink-secondary">
              How your temple reaches you, and your consent to be contacted.
            </p>
          </header>

          <section className="mb-6 rounded-lg bg-raised px-6 py-5" aria-labelledby="contact-heading">
            <h2 id="contact-heading" className="text-lg">
              Contact details
            </h2>
            <p className="mt-1 text-sm text-ink-secondary">
              Set when your account was created. Ask your temple administrator to change these.
            </p>
            <dl className="mt-4 grid grid-cols-[8rem_1fr] gap-y-3 text-sm">
              <dt className="text-ink-secondary">Name</dt>
              <dd>—</dd>
              <dt className="text-ink-secondary">Email</dt>
              <dd>—</dd>
              <dt className="text-ink-secondary">Phone</dt>
              <dd>—</dd>
            </dl>
          </section>

          <section
            className="mb-6 rounded-lg bg-raised px-6 py-5"
            aria-labelledby="channel-heading"
          >
            <h2 id="channel-heading" className="text-lg">
              Preferred channel
            </h2>
            <p className="mt-1 text-sm text-ink-secondary">
              Where reminders reach you by default. WhatsApp rides your phone number.
            </p>
            <fieldset className="mt-4 space-y-2">
              <legend className="sr-only">Choose a preferred channel</legend>
              {CHANNELS.map((channel, index) => (
                <label
                  key={channel.value}
                  className="flex items-start gap-3 rounded border border-hairline px-4 py-3"
                >
                  <input
                    type="radio"
                    name="preferredChannel"
                    value={channel.value}
                    defaultChecked={index === 0}
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
            <p className="mt-3 max-w-prose text-ink-secondary">{CONSENT_TEXT}</p>
            <div className="mt-5 flex flex-wrap items-center gap-4">
              <button
                type="button"
                className="min-h-touch rounded bg-accent px-5 text-ink-inverse transition-colors duration-state hover:bg-accent-hover"
              >
                I agree
              </button>
              <span className="text-sm text-ink-muted">
                You can withdraw or change this at any time. Until you agree, we won&apos;t send you
                reminders.
              </span>
            </div>
          </section>
        </div>
      </main>
    </div>
  );
}
