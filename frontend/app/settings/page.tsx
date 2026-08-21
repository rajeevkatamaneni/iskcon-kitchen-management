"use client";

import { useEffect, useState } from "react";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import { ALL_LANGUAGES } from "@/lib/languages";
import {
  api,
  toApiError,
  type ApiError,
  type PaymentProviderOption,
  type PaymentSettingsView,
  type WebhookSubscriptionGroup,
  type WhatsAppSettingsView,
} from "@/lib/api";

/**
 * A temple's own settings: today, how it collects donations.
 *
 * <p>The two status lines come before any field on purpose. They answer questions that fail
 * independently — the keys reaching the provider, and the provider reaching us — and only the first
 * is something an administrator can prove by pressing a button. Put them at the foot and someone
 * presses Test, sees green, and spends a week wondering why no donation is ever confirmed.
 */
export default function SettingsRoute() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <div className="flex min-h-screen">
        <Sidebar activeHref="/settings" />
        <div className="min-w-0 flex-1">
          <SettingsView />
        </div>
      </div>
    </RequireRole>
  );
}

function SettingsView() {
  const { getToken } = useAuth();
  const [settings, setSettings] = useState<PaymentSettingsView | null>(null);
  const [providers, setProviders] = useState<PaymentProviderOption[]>([]);
  const [events, setEvents] = useState<WebhookSubscriptionGroup[]>([]);
  const [whatsapp, setWhatsapp] = useState<WhatsAppSettingsView | null>(null);
  const [contactEmail, setContactEmail] = useState<string | null>(null);
  const [locale, setLocale] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const token = await getToken();
        const [current, options, eventTypes, messaging, contact, temple] = await Promise.all([
          api.paymentSettings(token),
          api.paymentProviders(token),
          api.paymentEvents(token),
          api.whatsappSettings(token),
          api.templeContactEmail(token),
          api.templeSettings(token),
        ]);
        if (!cancelled) {
          setSettings(current);
          setProviders(options);
          setEvents(eventTypes);
          setWhatsapp(messaging);
          setContactEmail(contact.contactEmail);
          setLocale(temple.locale);
        }
      } catch (e) {
        if (!cancelled) setLoadError(toApiError(e, "We couldn't load your settings."));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [getToken]);

  if (loadError) {
    return (
      <main className="mx-auto max-w-4xl px-10 py-12">
        <h1 className="text-3xl font-semibold text-ink">Settings</h1>
        <p className="mt-2 text-danger">{loadError.message}</p>
        <p className="text-sm text-ink-secondary">{loadError.action}</p>
      </main>
    );
  }
  if (!settings) {
    return <Loading />;
  }

  return (
    <main className="mx-auto max-w-4xl px-10 py-12">
      <h1 className="text-3xl font-semibold text-ink">Settings</h1>
      <p className="mt-1 max-w-[56ch] text-ink-secondary">
        The language this temple works in, how it collects donations, and how it reaches its people.
        Only a temple administrator can see or change any of it.
      </p>

      <LanguageSection initial={locale} getToken={getToken} />

      <PaymentGatewaySection
        settings={settings}
        providers={providers}
        events={events}
        onChanged={setSettings}
        getToken={getToken}
      />

      {whatsapp && (
        <MessagingSection settings={whatsapp} onChanged={setWhatsapp} getToken={getToken} />
      )}

      <EmailSection initial={contactEmail} getToken={getToken} />
    </main>
  );
}

// ---- Payment gateway -------------------------------------------------------

function PaymentGatewaySection({
  settings,
  providers,
  events,
  onChanged,
  getToken,
}: {
  settings: PaymentSettingsView;
  providers: PaymentProviderOption[];
  events: WebhookSubscriptionGroup[];
  onChanged: (next: PaymentSettingsView) => void;
  getToken: () => Promise<string | undefined>;
}) {
  const [provider, setProvider] = useState(settings.provider ?? providers[0]?.value ?? "");
  const [keyId, setKeyId] = useState(settings.keyId ?? "");
  const [keySecret, setKeySecret] = useState("");
  const [replacing, setReplacing] = useState(!settings.configured);
  const [busy, setBusy] = useState<"save" | "test" | "reveal" | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);
  const [webhookSecret, setWebhookSecret] = useState<string | null>(null);

  async function save() {
    setBusy("save");
    setError(null);
    setSaved(false);
    try {
      const next = await api.savePaymentSettings(
        { provider, keyId, keySecret: keySecret.trim() || undefined },
        await getToken()
      );
      onChanged(next);
      setKeySecret("");
      setReplacing(false);
      setSaved(true);
    } catch (e) {
      setError(toApiError(e, "We couldn't save that."));
    } finally {
      setBusy(null);
    }
  }

  async function test() {
    setBusy("test");
    setError(null);
    setSaved(false);
    try {
      onChanged(await api.testPaymentSettings(await getToken()));
    } catch (e) {
      setError(toApiError(e, "We couldn't reach your provider."));
    } finally {
      setBusy(null);
    }
  }

  async function reveal() {
    setBusy("reveal");
    setError(null);
    try {
      const { webhookSecret: secret } = await api.revealWebhookSecret(await getToken());
      setWebhookSecret(secret);
    } catch (e) {
      setError(toApiError(e, "We couldn't fetch that."));
    } finally {
      setBusy(null);
    }
  }

  return (
    // Named, because the WhatsApp section below has a Test connection button of its own, and a
    // screen reader — or a test — needs to know which one it is on.
    <section className="mt-10 rounded-xl bg-raised px-7 py-7" aria-label="Payment gateway">
      <h2 className="text-lg font-semibold text-ink">Payment gateway</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The account devotees&rsquo; donations are paid into. This is money coming <em>in</em> —
        paying your vendors is under Payments.
      </p>

      <div className="mt-5 grid gap-3 rounded-lg bg-canvas px-5 py-4">
        <Check
          ok={Boolean(settings.verifiedAt)}
          okLabel="Working"
          waitLabel="Not yet"
          title={
            settings.verifiedAt
              ? `Your keys reach ${label(providers, settings.provider)}`
              : "Your keys have not been checked yet"
          }
          detail={
            settings.verifiedAt
              ? `Last checked ${when(settings.verifiedAt)}.`
              : "Save your key ID and secret, then press Test connection."
          }
        />
        <Check
          ok={Boolean(settings.webhookSeenAt)}
          okLabel="Working"
          waitLabel="Not yet"
          title={
            settings.webhookSeenAt
              ? `${label(providers, settings.provider)} has called us back`
              : `${label(providers, settings.provider)} has not called us back yet`
          }
          detail={
            settings.webhookSeenAt
              ? `Last heard ${when(settings.webhookSeenAt)}.`
              : "Until a payment notification arrives, donations will be taken but never confirmed. Add the webhook below to your provider's dashboard."
          }
        />
      </div>

      <label className="mt-6 block text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Who handles your payments</span>
        <select
          value={provider}
          onChange={(e) => setProvider(e.target.value)}
          className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
        >
          {providers.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label}
            </option>
          ))}
        </select>
      </label>
      <p className="mt-1.5 text-xs text-ink-muted">
        Only providers the app has been built to talk to. Ask us if yours is missing.
      </p>

      <div className="mt-5 grid gap-5 sm:grid-cols-2">
        <label className="block text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Key ID</span>
          <input
            value={keyId}
            onChange={(e) => setKeyId(e.target.value)}
            autoComplete="off"
            className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
          />
          <span className="mt-1.5 block text-xs text-ink-muted">
            From your provider&rsquo;s dashboard, under API keys.
          </span>
        </label>

        <div className="text-sm text-ink-secondary">
          Key secret
          {settings.configured && !replacing ? (
            <>
              <div className="mt-1.5 flex gap-2">
                <div className="flex min-h-touch flex-1 items-center rounded border border-hairline bg-sunken px-3 tracking-[0.15em] text-ink-muted">
                  ••••••••••••••••
                </div>
                <button
                  type="button"
                  onClick={() => setReplacing(true)}
                  className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-3 text-sm text-accent-text"
                >
                  Replace
                </button>
              </div>
              <span className="mt-1.5 block text-xs text-ink-muted">
                Saved {when(settings.keySecretSavedAt)}. Kept encrypted, and never shown again — not
                even to you.
              </span>
            </>
          ) : (
            <>
              <input
                type="password"
                value={keySecret}
                onChange={(e) => setKeySecret(e.target.value)}
                autoComplete="new-password"
                className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
              />
              <span className="mt-1.5 block text-xs text-ink-muted">
                Stored encrypted, away from this temple&rsquo;s records. It is never shown again.
              </span>
            </>
          )}
        </div>
      </div>

      {/*
        Where the provider lets us register the webhook ourselves, we have, and there is nothing to
        instruct. Razorpay does not: its webhook API is a partner API a temple's own merchant keys
        cannot call, so a Razorpay temple gets the steps — numbered, because this is the one part of
        setting up payments that happens outside this application, and half-doing it is silent. The
        temple takes money and records none of it.
      */}
      {settings.configured && settings.webhookRegisteredAt && (
        <>
          <h3 className="mt-8 text-base font-semibold text-ink">
            {label(providers, settings.provider)} has been told where to reach us
          </h3>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            We set the webhook up for you when you saved these keys, on {when(settings.webhookRegisteredAt)}.
            There is nothing for you to do in your provider&rsquo;s dashboard.
          </p>
        </>
      )}

      {settings.configured && settings.webhookUrl && !settings.webhookRegisteredAt && (
        <>
          <h3 className="mt-8 text-base font-semibold text-ink">
            Tell {label(providers, settings.provider)} where to reach us
          </h3>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            {label(providers, settings.provider)} only lets an account holder do this, so these three
            steps are yours. Until they are done a donation is taken and never marked as received.
          </p>

          <ol className="mt-5 grid gap-6">
            <Step
              n={1}
              title={`Open Webhooks in your ${label(providers, settings.provider)} dashboard`}
              detail="Account & Settings → Webhooks → Add New Webhook. Webhooks are kept separately for test and live mode, so whichever mode these keys belong to is the mode to add it in."
            />

            <Step n={2} title="Paste the address and the secret">
              <p className="mt-2 text-sm text-ink-secondary">Webhook URL</p>
              <CopyRow value={settings.webhookUrl} />

              <p className="mt-3 text-sm text-ink-secondary">Webhook secret</p>
              {webhookSecret ? (
                <CopyRow value={webhookSecret} />
              ) : (
                <div className="mt-1.5 flex gap-2">
                  <div className="flex min-h-touch min-w-0 flex-1 items-center overflow-hidden rounded bg-sunken px-3 tracking-[0.15em] text-ink-muted">
                    ••••••••••••••••••••
                  </div>
                  <button
                    type="button"
                    onClick={reveal}
                    disabled={busy !== null}
                    className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-3 text-sm text-accent-text disabled:opacity-60"
                  >
                    {busy === "reveal" ? "…" : "Reveal"}
                  </button>
                </div>
              )}
              <p className="mt-1.5 text-xs text-ink-muted">
                It must be this secret exactly — we check every notification against it and refuse
                any that does not match. Revealing it is recorded in the audit log.
              </p>
            </Step>

            <Step
              n={3}
              title="Tick these events"
              detail="Anything else is noise we ignore; anything missing is a gift that never gets recorded."
            >
              {events.map((group) => (
                <div key={group.purpose} className="mt-3">
                  <p className="text-sm text-ink-secondary">
                    {group.purpose}
                    {!group.essential && (
                      <span className="text-ink-muted">
                        {" "}
                        — only if you offer it. Your provider lists these once the feature is
                        switched on for your account; if you cannot see them, skip this group.
                      </span>
                    )}
                  </p>
                  <div className="mt-1.5 flex flex-wrap gap-2">
                    {group.events.map((event) => (
                      <span
                        key={event}
                        className="rounded bg-sunken px-2 py-1 font-mono text-xs text-ink-secondary"
                      >
                        {event}
                      </span>
                    ))}
                  </div>
                </div>
              ))}
            </Step>
          </ol>

          <p className="mt-5 max-w-[60ch] text-sm text-ink-secondary">
            The second light above turns green the moment the first correctly signed notification
            arrives. If your provider offers a &ldquo;send test webhook&rdquo; button, that is the
            quickest way to prove all three steps without spending a payment.
          </p>
        </>
      )}

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && (
        <p className="mt-6 text-sm text-success">Saved, and your provider accepted the keys.</p>
      )}

      {/*
        Test connection re-checks the keys this temple has already stored, so there is nothing for it
        to do until Save has stored some — and Save checks them with the provider on the way past, so
        a first-time setup never needs this button at all. That was true before and the button simply
        sat there greyed out, which reads as something broken rather than something not yet needed.
        It says why now.
      */}
      <div className="mt-7 flex flex-wrap items-center gap-3 border-t border-hairline pt-6">
        <button
          type="button"
          onClick={test}
          disabled={busy !== null || !settings.configured}
          className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-5 text-sm text-accent-text disabled:opacity-60"
        >
          {busy === "test" ? "Checking…" : "Test connection"}
        </button>
        {!settings.configured && (
          <span className="text-sm text-ink-muted">
            Press Save first — it checks your keys with {label(providers, provider)}. This button is
            for re-checking them later.
          </span>
        )}
        <span className="flex-1" />
        <button
          type="button"
          onClick={save}
          disabled={busy !== null || !keyId.trim() || (replacing && !keySecret.trim())}
          className="min-h-touch rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy === "save" ? "Saving…" : "Save"}
        </button>
      </div>
    </section>
  );
}

/** One numbered step, so it is obvious how many there are and which one you are on. */
function Step({
  n,
  title,
  detail,
  children,
}: {
  n: number;
  title: string;
  detail?: string;
  children?: React.ReactNode;
}) {
  return (
    <li className="flex min-w-0 gap-4">
      <span
        aria-hidden
        className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full bg-accent-bg text-sm font-medium text-accent-text"
      >
        {n}
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-medium text-ink">{title}</p>
        {detail && <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">{detail}</p>}
        {children}
      </div>
    </li>
  );
}

function Check({
  ok,
  okLabel,
  waitLabel,
  title,
  detail,
}: {
  ok: boolean;
  okLabel: string;
  waitLabel: string;
  title: string;
  detail: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <span
        className={[
          "mt-0.5 rounded-full px-2.5 py-0.5 text-xs",
          ok ? "bg-success-bg text-success" : "bg-warning-bg text-warning",
        ].join(" ")}
      >
        {ok ? okLabel : waitLabel}
      </span>
      <span className="grid">
        <span className="text-sm font-medium text-ink">{title}</span>
        <span className="max-w-[70ch] text-xs text-ink-secondary">{detail}</span>
      </span>
    </div>
  );
}

/**
 * A value the administrator has to paste somewhere else, with the button that copies it.
 *
 * <p>`min-w-0` on the code block is what makes the whole panel behave, and it is not optional. A
 * flex item defaults to `min-width: auto`, which means it refuses to shrink below its content's
 * intrinsic width — and with `whitespace-nowrap` that width is the entire webhook URL. Without it
 * the row cannot shrink, `overflow-x-auto` never engages, and since the steps are laid out in a
 * grid the un-shrinkable item widens the whole track: the paragraphs beside it then wrap at that
 * wider measure and the panel's own content spills out of it. The symptom looks like a text
 * problem and is a flexbox one.
 */
function CopyRow({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="mt-1.5 flex gap-2">
      <code className="min-h-touch min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded bg-sunken px-3 py-2.5 font-mono text-xs text-ink">
        {value}
      </code>
      <button
        type="button"
        onClick={() => {
          navigator.clipboard?.writeText(value);
          setCopied(true);
        }}
        className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-3 text-sm text-accent-text"
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

// ---- Messaging -------------------------------------------------------------

/**
 * WhatsApp, in the same shape as the payment gateway above it, because to an administrator it is the
 * same kind of task: connect an account the temple owns, prove it works, be told what to paste where.
 *
 * <p>The two status lines are separate for the same reason as the gateway's. Whether our messages
 * reach Meta and whether Meta's receipts reach us fail independently, and only the first is settled
 * by pressing a button.
 */
function MessagingSection({
  settings,
  onChanged,
  getToken,
}: {
  settings: WhatsAppSettingsView;
  onChanged: (next: WhatsAppSettingsView) => void;
  getToken: () => Promise<string | undefined>;
}) {
  const [phoneNumberId, setPhoneNumberId] = useState(settings.phoneNumberId ?? "");
  const [wabaId, setWabaId] = useState(settings.wabaId ?? "");
  const [accessToken, setAccessToken] = useState("");
  const [appSecret, setAppSecret] = useState("");
  const [replacing, setReplacing] = useState(!settings.connected);
  const [busy, setBusy] = useState<"save" | "test" | "reveal" | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);
  const [verifyToken, setVerifyToken] = useState<string | null>(null);

  async function save() {
    setBusy("save");
    setError(null);
    setSaved(false);
    try {
      const next = await api.saveWhatsAppSettings(
        {
          phoneNumberId: phoneNumberId.trim(),
          wabaId: wabaId.trim(),
          accessToken: accessToken.trim() || undefined,
          appSecret: appSecret.trim() || undefined,
        },
        await getToken()
      );
      onChanged(next);
      setAccessToken("");
      setAppSecret("");
      setReplacing(false);
      setSaved(true);
    } catch (e) {
      setError(toApiError(e, "We couldn't connect that WhatsApp account."));
    } finally {
      setBusy(null);
    }
  }

  async function test() {
    setBusy("test");
    setError(null);
    setSaved(false);
    try {
      onChanged(await api.testWhatsAppSettings(await getToken()));
    } catch (e) {
      setError(toApiError(e, "We couldn't reach Meta."));
    } finally {
      setBusy(null);
    }
  }

  async function reveal() {
    setBusy("reveal");
    setError(null);
    try {
      const { verifyToken: token } = await api.revealWhatsAppVerifyToken(await getToken());
      setVerifyToken(token);
    } catch (e) {
      setError(toApiError(e, "We couldn't fetch that."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="WhatsApp">
      <h2 className="text-lg font-semibold text-ink">WhatsApp</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        How the temple reaches volunteers and vendors, sending as your own number — falling back to
        SMS when a message cannot be delivered.
      </p>

      <div className="mt-5 grid gap-3 rounded-lg bg-canvas px-5 py-4">
        <Check
          ok={Boolean(settings.verifiedAt)}
          okLabel="Working"
          waitLabel="Not yet"
          title={
            settings.verifiedAt
              ? `We can send as ${settings.displayNumber ?? "your number"}`
              : "Your WhatsApp account is not connected yet"
          }
          detail={
            settings.verifiedAt
              ? `Last checked ${when(settings.verifiedAt)}.`
              : "Enter the four values below and press Connect."
          }
        />
        <Check
          ok={Boolean(settings.webhookSeenAt)}
          okLabel="Working"
          waitLabel="Not yet"
          title={
            settings.webhookSeenAt
              ? "Meta has called us back"
              : "Meta has not called us back yet"
          }
          detail={
            settings.webhookSeenAt
              ? `Last heard ${when(settings.webhookSeenAt)}.`
              : "Until a delivery receipt arrives, messages go out but we cannot tell you whether they landed."
          }
        />
      </div>

      <div className="mt-6 grid gap-5 sm:grid-cols-2">
        <label className="block text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Phone number ID</span>
          <input
            value={phoneNumberId}
            onChange={(e) => setPhoneNumberId(e.target.value)}
            className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
          />
          <span className="mt-1.5 block text-xs text-ink-muted">
            In Meta&rsquo;s dashboard under WhatsApp → API Setup. Not your phone number — the id beneath it.
          </span>
        </label>

        <label className="block text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">WhatsApp Business Account ID</span>
          <input
            value={wabaId}
            onChange={(e) => setWabaId(e.target.value)}
            className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
          />
          <span className="mt-1.5 block text-xs text-ink-muted">
            On the same screen. This is what owns your approved message templates.
          </span>
        </label>

        <div className="sm:col-span-2 grid gap-5 sm:grid-cols-2">
          {settings.connected && !replacing ? (
            <div className="sm:col-span-2">
              <p className="text-sm text-ink-secondary">Access token and app secret</p>
              <div className="mt-1.5 flex gap-2">
                <div className="flex min-h-touch flex-1 items-center rounded bg-sunken px-3 tracking-[0.15em] text-ink-muted">
                  ••••••••••••••••••••
                </div>
                <button
                  type="button"
                  onClick={() => setReplacing(true)}
                  className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-3 text-sm text-accent-text"
                >
                  Replace
                </button>
              </div>
              <span className="mt-1.5 block text-xs text-ink-muted">
                Stored encrypted, away from this temple&rsquo;s records. Neither is ever shown again.
              </span>
            </div>
          ) : (
            <>
              <label className="block text-sm text-ink-secondary">
                <span className="pl-field-inset font-medium text-ink">Permanent access token</span>
                <input
                  type="password"
                  value={accessToken}
                  onChange={(e) => setAccessToken(e.target.value)}
                  autoComplete="new-password"
                  className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
                />
                <span className="mt-1.5 block text-xs text-ink-muted">
                  A System User token, not the temporary one on the setup page — that expires in a day.
                </span>
              </label>

              <label className="block text-sm text-ink-secondary">
                <span className="pl-field-inset font-medium text-ink">App secret</span>
                <input
                  type="password"
                  value={appSecret}
                  onChange={(e) => setAppSecret(e.target.value)}
                  autoComplete="new-password"
                  className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
                />
                <span className="mt-1.5 block text-xs text-ink-muted">
                  App settings → Basic. We check every delivery receipt against it.
                </span>
              </label>
            </>
          )}
        </div>
      </div>

      {settings.connected && settings.webhookUrl && (
        <>
          <h3 className="mt-8 text-base font-semibold text-ink">Tell Meta where to reach us</h3>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            Only an account holder can do this, so these steps are yours. Without them your messages
            still send — you simply never find out whether they arrived.
          </p>

          <ol className="mt-5 grid gap-6">
            <Step
              n={1}
              title="Open your app's WhatsApp configuration"
              detail="Meta dashboard → your app → WhatsApp → Configuration → Edit, beside Callback URL."
            />

            <Step n={2} title="Paste the address and the verify token">
              <p className="mt-2 text-sm text-ink-secondary">Callback URL</p>
              <CopyRow value={settings.webhookUrl} />

              <p className="mt-3 text-sm text-ink-secondary">Verify token</p>
              {verifyToken ? (
                <CopyRow value={verifyToken} />
              ) : (
                <div className="mt-1.5 flex gap-2">
                  <div className="flex min-h-touch flex-1 items-center rounded bg-sunken px-3 tracking-[0.15em] text-ink-muted">
                    ••••••••••••••••••••
                  </div>
                  <button
                    type="button"
                    onClick={reveal}
                    disabled={busy !== null}
                    className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-3 text-sm text-accent-text disabled:opacity-60"
                  >
                    {busy === "reveal" ? "…" : "Reveal"}
                  </button>
                </div>
              )}
              <p className="mt-1.5 text-xs text-ink-muted">
                Meta calls the address once to check you hold this token. Revealing it is recorded in
                the audit log.
              </p>
            </Step>

            <Step
              n={3}
              title="Subscribe to the messages field"
              detail="On the same screen, under Webhook fields, tick messages. That is the one that carries delivery receipts."
            />
          </ol>

          <p className="mt-5 max-w-[60ch] text-sm text-ink-secondary">
            {settings.templatesSubmittedAt
              ? `We sent your message templates to Meta for approval on ${when(settings.templatesSubmittedAt)}. Approval is Meta's and usually takes minutes; until a template is approved, messages using it fall back to SMS.`
              : "Your message templates will be submitted to Meta for approval when you connect. You do not need to write any of them."}
          </p>
        </>
      )}

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && (
        <p className="mt-6 text-sm text-success">Connected, and Meta accepted the credentials.</p>
      )}

      <div className="mt-7 flex flex-wrap items-center gap-3 border-t border-hairline pt-6">
        <button
          type="button"
          onClick={test}
          disabled={busy !== null || !settings.connected}
          className="min-h-touch rounded-lg border border-hairline-strong bg-canvas px-5 text-sm text-accent-text disabled:opacity-60"
        >
          {busy === "test" ? "Checking…" : "Test connection"}
        </button>
        {!settings.connected && (
          <span className="text-sm text-ink-muted">
            Press Connect first — it checks your credentials with Meta. This button is for
            re-checking them later.
          </span>
        )}
        <span className="flex-1" />
        <button
          type="button"
          onClick={save}
          disabled={
            busy !== null ||
            !phoneNumberId.trim() ||
            !wabaId.trim() ||
            (replacing && (!accessToken.trim() || !appSecret.trim()))
          }
          className="min-h-touch rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy === "save" ? "Connecting…" : settings.connected ? "Save" : "Connect"}
        </button>
      </div>
    </section>
  );
}

/**
 * Email, and the one thing about it that is the temple's to decide.
 *
 * <p>Sending is always from the platform's address, because SPF and DKIM are records on the domain a
 * message claims to come from and a temple cannot pass them for a domain it does not own — mail sent
 * as the temple would land in spam. So what a temple sets is not who sends, but where a reply goes.
 */
function EmailSection({
  initial,
  getToken,
}: {
  initial: string | null;
  getToken: () => Promise<string | undefined>;
}) {
  const [email, setEmail] = useState(initial ?? "");
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.saveTempleContactEmail(email.trim(), await getToken());
      setSaved(true);
    } catch (e) {
      setError(toApiError(e, "We couldn't save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="Email">
      <h2 className="text-lg font-semibold text-ink">Email</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        Where a devotee&rsquo;s reply comes back to. Messages are sent for you, so there is nothing
        to set up and no account to connect.
      </p>

      <div className="mt-5 rounded-lg bg-canvas px-5 py-4">
        <p className="text-xs uppercase tracking-[0.08em] text-ink-muted">What a devotee will see</p>
        <p className="mt-2 font-mono text-sm text-ink">
          From: {"{your temple}"} via ISKCON Kitchen &lt;noreply@…&gt;
        </p>
        <p className="mt-1 font-mono text-sm text-ink">Reply-To: {email.trim() || "not set"}</p>
        <p className="mt-2 max-w-[60ch] text-xs text-ink-muted">
          Your temple&rsquo;s name is on every message. The address it is sent from has to be ours —
          it is the one whose records stop the message being treated as spam — but a reply goes
          wherever you put below.
        </p>
      </div>

      <label className="mt-6 block max-w-md text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Your temple&rsquo;s email address</span>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="kitchen@yourtemple.org"
          className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
        />
        <span className="mt-1.5 block text-xs text-ink-muted">
          Leave it empty and messages still send — a reply simply reaches us instead of you.
        </span>
      </label>

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && <p className="mt-6 text-sm text-success">Saved.</p>}

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <button
          type="button"
          onClick={save}
          disabled={busy}
          className="min-h-touch rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy ? "Saving…" : "Save"}
        </button>
      </div>
    </section>
  );
}

// ---- Language --------------------------------------------------------------

/**
 * The language the temple works in.
 *
 * <p>Its one job today is the job card: the sheet goes to the kitchen, so it prints in the temple's
 * own language unless the person at the printer chooses otherwise (build brief §3). The setting has
 * existed on the temple record since the first migration and has never been writable, so every
 * temple has quietly been English — which mattered to nobody until something started reading it.
 */
function LanguageSection({
  initial,
  getToken,
}: {
  initial: string | null;
  getToken: () => Promise<string | undefined>;
}) {
  // Stored region-qualified ("kn-IN"); chosen as a bare language, which is what a person picks.
  const [language, setLanguage] = useState((initial ?? "en-IN").split("-")[0]);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.setTempleLanguage(language, await getToken());
      setSaved(true);
    } catch (e) {
      setError(toApiError(e, "We couldn't save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="Language">
      <h2 className="text-lg font-semibold text-ink">Language</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The language your kitchen reads. Job cards print in it by default, so a cook gets their
        worksheet in the language they actually work in — whoever prints it can still choose English
        for that copy, or print it twice.
      </p>

      <label className="mt-6 block max-w-md text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Your temple&rsquo;s language</span>
        <select
          value={language}
          onChange={(e) => setLanguage(e.target.value)}
          className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
        >
          {ALL_LANGUAGES.map((l) => (
            <option key={l.code} value={l.code}>
              {l.label}
            </option>
          ))}
        </select>
        <span className="mt-1.5 block text-xs text-ink-muted">
          This changes what is printed, not what this screen is written in.
        </span>
      </label>

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && <p className="mt-6 text-sm text-success">Saved.</p>}

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <button
          type="button"
          onClick={save}
          disabled={busy}
          className="min-h-touch rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy ? "Saving…" : "Save"}
        </button>
      </div>
    </section>
  );
}

// ---- helpers ---------------------------------------------------------------

function label(providers: PaymentProviderOption[], provider: string | null) {
  if (!provider) {
    return "your provider";
  }
  // "Razorpay — India" reads badly mid-sentence; the name alone is what a sentence wants.
  return providers.find((p) => p.value === provider)?.label.split("—")[0].trim() ?? provider;
}

function when(iso: string | null) {
  if (!iso) {
    return "—";
  }
  return new Date(iso).toLocaleString("en-IN", {
    day: "numeric",
    month: "short",
    hour: "numeric",
    minute: "2-digit",
  });
}
