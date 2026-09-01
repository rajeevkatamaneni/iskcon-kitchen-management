"use client";

import { useEffect, useRef, useState } from "react";
import { Field } from "@/components/Field";
import { FieldRow } from "@/components/ds/FieldRow";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { Loading } from "@/components/Loading";
import { ThemeSwatches } from "@/components/ThemeSwatches";
import { useAuth } from "@/lib/auth-context";
import { ALL_LANGUAGES } from "@/lib/languages";
import { moment } from "@/lib/format";
import { applyPalette, crossfadeTheme, THEME_FAMILY_LABELS, type ThemeFamily } from "@/lib/theme";
import { choosableThemePacks, themePackById, type ThemePack } from "@/lib/theme-packs";
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
  const [themeId, setThemeId] = useState<string | null>(null);
  // Two numbers rather than one object, and that is not a style choice. This effect's only
  // dependency is `getToken`, which is not guaranteed to be the same function twice — so any state
  // it sets with a *fresh* object identity is a state that always changes, which re-renders, which
  // re-runs the effect, forever. Primitives compare equal and the loop cannot start. Same trap as
  // the ?created banner (2026-08-26); this is the same fix.
  const [stockExpiryDays, setStockExpiryDays] = useState<number | null>(null);
  const [contractEndDays, setContractEndDays] = useState<number | null>(null);
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
          setThemeId(temple.themeId);
          setStockExpiryDays(temple.stockExpiryWarningDays);
          setContractEndDays(temple.contractEndWarningDays);
        }
      } catch (e) {
        if (!cancelled) setLoadError(toApiError(e, "We couldn’t load your settings."));
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
        Only a temple administrator can see or change any of this.
      </p>

      <AppearanceSection initial={themeId} onSaved={setThemeId} getToken={getToken} />

      <LanguageSection initial={locale} getToken={getToken} />

      {stockExpiryDays !== null && contractEndDays !== null && (
        <WarningsSection
          stockExpiryDays={stockExpiryDays}
          contractEndDays={contractEndDays}
          onSaved={(stock, contract) => {
            setStockExpiryDays(stock);
            setContractEndDays(contract);
          }}
          getToken={getToken}
        />
      )}

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
      setError(toApiError(e, "We couldn’t save that."));
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
      setError(toApiError(e, "We couldn’t reach your provider."));
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
      setError(toApiError(e, "We couldn’t fetch that."));
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
        The account donations are paid into. Paying vendors is under Payments.
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
              : "Donations are taken but never confirmed. Add the webhook below."
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
        Ask us if yours is missing.
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
            From your provider’s dashboard, under API keys.
          </span>
        </label>

        <div className="text-sm text-ink-secondary">
          Key secret
          {settings.configured && !replacing ? (
            <>
              <div className="mt-1.5 flex gap-2">
                <div className="flex min-h-touch flex-1 items-center rounded border border-hairline bg-sunken px-3 tracking-masked text-ink-muted">
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
                Saved {when(settings.keySecretSavedAt)}. Kept encrypted, and never shown again.
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
                Stored encrypted, away from this temple’s records. It is never shown again.
              </span>
            </>
          )}
        </div>
      </div>

      {/*
        Where the provider lets us register the webhook ourselves, we have, and there is nothing to
        instruct. Razorpay does not: its webhook API is a partner API a temple’s own merchant keys
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
            There is nothing for you to do in your provider’s dashboard.
          </p>
        </>
      )}

      {settings.configured && settings.webhookUrl && !settings.webhookRegisteredAt && (
        <>
          <h3 className="mt-8 text-base font-semibold text-ink">
            Tell {label(providers, settings.provider)} where to reach us
          </h3>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            {label(providers, settings.provider)} only lets an account holder do this. Until it is
            done, a donation is never marked as received.
          </p>

          <ol className="mt-5 grid gap-6">
            <Step
              n={1}
              title={`Open Webhooks in your ${label(providers, settings.provider)} dashboard`}
              detail="Account & Settings → Webhooks → Add New Webhook. Add it in the mode these keys belong to."
            />

            <Step n={2} title="Paste the address and the secret">
              <p className="mt-2 text-sm text-ink-secondary">Webhook URL</p>
              <CopyRow value={settings.webhookUrl} />

              <p className="mt-3 text-sm text-ink-secondary">Webhook secret</p>
              {webhookSecret ? (
                <CopyRow value={webhookSecret} />
              ) : (
                <div className="mt-1.5 flex gap-2">
                  <div className="flex min-h-touch min-w-0 flex-1 items-center overflow-hidden rounded bg-sunken px-3 tracking-masked text-ink-muted">
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
                It must be this secret exactly. Revealing it is recorded in the audit log.
              </p>
            </Step>

            <Step
              n={3}
              title="Tick these events"
              detail="Anything missing is a gift that never gets recorded."
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
            The second light turns green when the first signed notification arrives. A
            &ldquo;send test webhook&rdquo; button proves all three steps without spending a payment.
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
            Press Save first. It checks your keys with {label(providers, provider)}, and this
            button re-checks them later.
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
      setError(toApiError(e, "We couldn’t connect that WhatsApp account."));
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
      setError(toApiError(e, "We couldn’t reach Meta."));
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
      setError(toApiError(e, "We couldn’t fetch that."));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="WhatsApp">
      <h2 className="text-lg font-semibold text-ink">WhatsApp</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The temple sends as its own number, falling back to SMS.
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
            Under WhatsApp → API Setup. Not the phone number, the id beneath it.
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
                <div className="flex min-h-touch flex-1 items-center rounded bg-sunken px-3 tracking-masked text-ink-muted">
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
                Stored encrypted, away from this temple’s records. Neither is ever shown again.
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
                  A System User token. The temporary one expires in a day.
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
            Only an account holder can do this. Without it you never learn whether a message
            arrived.
          </p>

          <ol className="mt-5 grid gap-6">
            <Step
              n={1}
              title="Open your app’s WhatsApp configuration"
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
                  <div className="flex min-h-touch flex-1 items-center rounded bg-sunken px-3 tracking-masked text-ink-muted">
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
              detail="On the same screen, under Webhook fields, tick messages. That one carries delivery receipts."
            />
          </ol>

          <p className="mt-5 max-w-[60ch] text-sm text-ink-secondary">
            {settings.templatesSubmittedAt
              ? `Your message templates went to Meta on ${when(settings.templatesSubmittedAt)}. Until one is approved, messages using it fall back to SMS.`
              : "Templates go to Meta for approval when you connect. You write none of them."}
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
            Press Connect first. It checks your credentials, and this button re-checks them later.
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
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="Email">
      <h2 className="text-lg font-semibold text-ink">Email</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        Where a devotee’s reply comes back to. There is nothing to set up.
      </p>

      <div className="mt-5 rounded-lg bg-canvas px-5 py-4">
        <p className="text-xs uppercase tracking-eyebrow text-ink-muted">What a devotee will see</p>
        <p className="mt-2 font-mono text-sm text-ink">
          From: {"{your temple}"} via ISKCON Kitchen &lt;noreply@…&gt;
        </p>
        <p className="mt-1 font-mono text-sm text-ink">Reply-To: {email.trim() || "not set"}</p>
        <p className="mt-2 max-w-[60ch] text-xs text-ink-muted">
          Your temple’s name is on every message. It must be sent from our address, or it is
          treated as spam. A reply goes wherever you put below.
        </p>
      </div>

      <label className="mt-6 block max-w-md text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Your temple’s email address</span>
        <input
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="kitchen@yourtemple.org"
          className="mt-1.5 min-h-touch w-full rounded border border-hairline bg-canvas px-3 text-ink"
        />
        <span className="mt-1.5 block text-xs text-ink-muted">
          Leave it empty and a reply reaches us instead of you.
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

// ---- Appearance ------------------------------------------------------------

/**
 * The colours the whole temple wears.
 *
 * <p>Two things about this screen are deliberate and neither is obvious.
 *
 * <p><b>Choosing previews immediately, saving commits.</b> Picking a pack repaints the entire
 * application at once — this screen, the menu beside it, everything. A swatch cannot answer the
 * question somebody actually has, which is "what will my kitchen's screens look like", and a
 * decision this visible should not be made from a thumbnail. Leaving without saving puts the old
 * palette back, so a look costs nothing.
 *
 * <p><b>It says who else this reaches.</b> Every other setting on this page affects the temple's
 * dealings with the outside world. This one changes what forty people see when they sign in
 * tomorrow morning, and an administrator ought to know that before pressing Save rather than
 * afterwards.
 */
function AppearanceSection({
  initial,
  onSaved,
  getToken,
}: {
  initial: string | null;
  onSaved: (themeId: string) => void;
  getToken: () => Promise<string | undefined>;
}) {
  // What the temple is wearing. `themePackById` answers for all three ways this can be nothing in
  // particular — never chosen, chosen something since withdrawn, or a platform operator — so this
  // is always a real pack. Held separately from `chosen` so leaving without saving knows what to
  // put back.
  const committed = themePackById(initial);
  const saved = committed.id;
  const [chosen, setChosen] = useState(saved);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const preview = themePackById(chosen);
  // Retired packs are not offered, except the one this temple is on — otherwise the picker would
  // show nothing selected and tell the admin, in effect, that their colours do not exist.
  const packs = choosableThemePacks(saved);

  // What to put back on the way out. A ref rather than a dependency, and the distinction is not
  // academic: written as one effect that paints on entry and restores on cleanup, saving repaints
  // the screen in the *old* palette. Saving changes `saved`, which re-runs the effect, which fires
  // the previous run's cleanup — and that cleanup closed over the pack the temple used to wear.
  // The admin presses Save and watches their new colours vanish.
  const committedRef = useRef(committed);
  committedRef.current = committed;

  // Painting is the easy half, and it crosses rather than cuts — somebody comparing packs is
  // looking at the change itself, not only at where it ends up.
  useEffect(() => {
    crossfadeTheme(() =>
      applyPalette(document.documentElement, preview.palette, preview.surfaces ?? null)
    );
  }, [preview]);

  // Leaving is the half that matters. Without it, a look around the catalogue would follow the
  // admin to every other screen in the application until they next reloaded. It reads the ref at
  // the moment of unmount, so it restores what the temple has actually saved by then — which is
  // the previous pack if they were only looking, and the new one if they committed.
  useEffect(
    () => () => {
      crossfadeTheme(() =>
        applyPalette(
          document.documentElement,
          committedRef.current.palette,
          committedRef.current.surfaces ?? null
        )
      );
    },
    []
  );

  async function save() {
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await api.setTempleTheme(chosen, await getToken());
      onSaved(chosen);
      setDone(true);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  const families: ThemeFamily[] = ["VIBRANT", "BALANCED", "MUTED"];
  const unsaved = chosen !== saved;

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="Appearance">
      {/* Save lives up here, beside the heading, and not at the foot below fifteen cards. It was at
          the foot, and what a person reached for instead was the word "Save" printed on a button
          inside the sample card — which was decoration and did nothing (Rajeev, 2026-08-30). The
          sample is gone, and the one control that commits anything is now the first one you meet. */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-ink">Appearance</h2>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            Pick a theme and you will see a preview. If you like it, save it. The theme you choose
            is applied to everyone at your temple.
          </p>
        </div>
        <button
          type="button"
          onClick={save}
          disabled={busy || !unsaved}
          className="min-h-touch shrink-0 rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy ? "Saving…" : "Save"}
        </button>
      </div>

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {done && !error && (
        <p role="status" className="mt-6 animate-notice-in text-sm text-success">
          Saved. Everyone at your temple sees this the next time they open the application.
        </p>
      )}
      {unsaved && !error && (
        <p className="mt-6 animate-notice-in text-sm text-ink-secondary">
          You are looking at a preview of {preview.name}. Save to keep it.
        </p>
      )}

      {packs.length === 0 ? (
        <p className="mt-6 text-sm text-ink-muted">No themes are available to choose from yet.</p>
      ) : (
        families.map((family) => {
          const inFamily = packs.filter((p) => p.family === family);
          if (inFamily.length === 0) {
            return null;
          }
          return (
            <fieldset key={family} className="mt-7">
              <legend className="pl-field-inset text-xs font-medium uppercase tracking-eyebrow text-ink-muted">
                {THEME_FAMILY_LABELS[family]}
              </legend>
              <div className="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {inFamily.map((pack) => (
                  <ThemeChoice
                    key={pack.id}
                    pack={pack}
                    checked={pack.id === chosen}
                    isCurrent={pack.id === saved}
                    onChoose={() => {
                      setChosen(pack.id);
                      setDone(false);
                    }}
                  />
                ))}
              </div>
            </fieldset>
          );
        })
      )}
    </section>
  );
}

/**
 * One pack in the picker: a radio you can see, a name, a sentence, and the colours.
 *
 * <p>The radio is visible rather than `sr-only`. It was hidden behind the card, on the reasoning
 * that the whole card was the target and a stray dot was clutter — which is true right up until
 * somebody cannot tell which one is selected without reading the border colour, in a screen whose
 * entire subject is that border colours change.
 */
function ThemeChoice({
  pack,
  checked,
  isCurrent,
  onChoose,
}: {
  pack: ThemePack;
  checked: boolean;
  isCurrent: boolean;
  onChoose: () => void;
}) {
  return (
    <label
      className={`block cursor-pointer rounded-lg border p-3 transition-colors duration-state ${
        checked ? "border-accent bg-accent-bg" : "border-hairline bg-canvas hover:border-hairline-strong"
      }`}
    >
      <span className="flex items-baseline gap-2">
        <input
          type="radio"
          name="theme-pack"
          value={pack.id}
          checked={checked}
          onChange={onChoose}
          className="h-4 w-4 shrink-0 self-center accent-accent"
        />
        <span className="text-sm font-medium text-ink">{pack.name}</span>
        {isCurrent && <span className="text-xs text-ink-muted">in use</span>}
      </span>
      <span className="mt-1 block text-xs text-ink-secondary">{pack.description}</span>
      <ThemeSwatches palette={pack.palette} />
    </label>
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
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="Language">
      <h2 className="text-lg font-semibold text-ink">Language</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The language your kitchen reads. Job cards print in it by default.
      </p>

      <label className="mt-6 block max-w-md text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Your temple’s language</span>
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

/** The bounds the request record and the database both carry. Kept here so the box says so too. */
const MIN_WARNING_DAYS = 1;
const MAX_WARNING_DAYS = 365;
const OUT_OF_RANGE = `A warning is between ${MIN_WARNING_DAYS} and ${MAX_WARNING_DAYS} days.`;

/**
 * The two horizons, together.
 *
 * <p>They are one section and one Save because they were one number until now — seven days, shared
 * between a sack of flour and a supplier agreement. The contract one has outgrown it, and the point
 * of moving both here rather than only the one that changed is that a temple sets its notice in one
 * place and can see the two beside each other.
 *
 * <p>Neither number does anything beyond deciding which rows carry a warning badge. No vendor is
 * dropped and no batch is written off by a date.
 */
function WarningsSection({
  stockExpiryDays,
  contractEndDays,
  onSaved,
  getToken,
}: {
  stockExpiryDays: number;
  contractEndDays: number;
  onSaved: (stockExpiryDays: number, contractEndDays: number) => void;
  getToken: () => Promise<string | undefined>;
}) {
  const [stock, setStock] = useState(String(stockExpiryDays));
  const [contract, setContract] = useState(String(contractEndDays));
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const stockDays = asDays(stock);
  const contractDays = asDays(contract);
  const stockError = stockDays === null ? OUT_OF_RANGE : undefined;
  const contractError = contractDays === null ? OUT_OF_RANGE : undefined;

  async function save() {
    if (stockDays === null || contractDays === null) return;
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.setWarningHorizons(
        { stockExpiryWarningDays: stockDays, contractEndWarningDays: contractDays },
        await getToken()
      );
      onSaved(stockDays, contractDays);
      setSaved(true);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7" aria-label="Warnings">
      <h2 className="text-lg font-semibold text-ink">Warnings</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        How much notice you want before a date runs out on you.
      </p>

      <FieldRow className="mt-6">
        <Field
          id="stock-expiry-warning-days"
          label="Notice before stock expires"
          hint="Batches closer than this are badged on Inventory."
          error={stockError}
        >
          {(props) => (
            <div className="flex items-center gap-2">
              <span className="block w-28">
                <input
                  {...props}
                  type="number"
                  inputMode="numeric"
                  min={MIN_WARNING_DAYS}
                  max={MAX_WARNING_DAYS}
                  value={stock}
                  onChange={(e) => {
                    setStock(e.target.value);
                    setSaved(false);
                  }}
                />
              </span>
              <span className="text-sm text-ink-secondary">days</span>
            </div>
          )}
        </Field>

        <Field
          id="contract-end-warning-days"
          label="Notice before a vendor contract ends"
          hint="Enough time to renegotiate, or to find somebody else."
          error={contractError}
        >
          {(props) => (
            <div className="flex items-center gap-2">
              <span className="block w-28">
                <input
                  {...props}
                  type="number"
                  inputMode="numeric"
                  min={MIN_WARNING_DAYS}
                  max={MAX_WARNING_DAYS}
                  value={contract}
                  onChange={(e) => {
                    setContract(e.target.value);
                    setSaved(false);
                  }}
                />
              </span>
              <span className="text-sm text-ink-secondary">days</span>
            </div>
          )}
        </Field>
      </FieldRow>

      <p className="mt-4 max-w-[60ch] text-sm text-ink-secondary">
        Both only put a badge on a screen. Nothing is dropped or written off.
      </p>

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
          disabled={busy || stockError !== undefined || contractError !== undefined}
          className="min-h-touch rounded-lg bg-accent px-6 text-sm text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
        >
          {busy ? "Saving…" : "Save"}
        </button>
      </div>
    </section>
  );
}

/** A whole number of days inside the bounds, or null — which is the only thing that is an error. */
function asDays(value: string): number | null {
  if (!/^\d+$/.test(value.trim())) return null;
  const days = Number(value.trim());
  return days >= MIN_WARNING_DAYS && days <= MAX_WARNING_DAYS ? days : null;
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
  return iso ? moment(iso) : "—";
}
