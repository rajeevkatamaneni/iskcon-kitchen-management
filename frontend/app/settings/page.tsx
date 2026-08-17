"use client";

import { useEffect, useState } from "react";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { Loading } from "@/components/Loading";
import { useAuth } from "@/lib/auth-context";
import {
  api,
  toApiError,
  type ApiError,
  type PaymentProviderOption,
  type PaymentSettingsView,
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
  const [loadError, setLoadError] = useState<ApiError | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const token = await getToken();
        const [current, options] = await Promise.all([
          api.paymentSettings(token),
          api.paymentProviders(token),
        ]);
        if (!cancelled) {
          setSettings(current);
          setProviders(options);
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
        How this temple collects donations and reaches its people. Only a temple administrator can
        see or change any of it.
      </p>

      <PaymentGatewaySection
        settings={settings}
        providers={providers}
        onChanged={setSettings}
        getToken={getToken}
      />

      <MessagingSection />
    </main>
  );
}

// ---- Payment gateway -------------------------------------------------------

function PaymentGatewaySection({
  settings,
  providers,
  onChanged,
  getToken,
}: {
  settings: PaymentSettingsView;
  providers: PaymentProviderOption[];
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
    <section className="mt-10 rounded-xl bg-raised px-7 py-7">
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
        Who handles your payments
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
          Key ID
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

      {settings.configured && settings.webhookUrl && (
        <>
          <h3 className="mt-8 text-base font-semibold text-ink">
            Tell {label(providers, settings.provider)} where to reach us
          </h3>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            Paste these two into your provider&rsquo;s dashboard under Webhooks. Without them a
            donation is taken but never marked as received.
          </p>

          <p className="mt-4 text-sm text-ink-secondary">Webhook URL</p>
          <CopyRow value={settings.webhookUrl} />

          <p className="mt-3 text-sm text-ink-secondary">Webhook secret</p>
          {webhookSecret ? (
            <CopyRow value={webhookSecret} />
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
            Revealing this is recorded in the audit log.
          </p>

          <p className="mt-4 text-sm text-ink-secondary">Events to subscribe to</p>
          <div className="mt-1.5 flex flex-wrap gap-2">
            {["payment.captured", "payment.failed"].map((event) => (
              <span
                key={event}
                className="rounded bg-sunken px-2 py-1 font-mono text-xs text-ink-secondary"
              >
                {event}
              </span>
            ))}
          </div>
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

function CopyRow({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="mt-1.5 flex gap-2">
      <code className="min-h-touch flex-1 overflow-x-auto whitespace-nowrap rounded bg-sunken px-3 py-2.5 font-mono text-xs text-ink">
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

function MessagingSection() {
  return (
    <section className="mt-6 rounded-xl bg-raised px-7 py-7 opacity-60">
      <h2 className="text-lg font-semibold text-ink">Messaging</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        How the temple reaches volunteers and vendors — WhatsApp, falling back to SMS when a message
        cannot be delivered.
      </p>
      <p className="mt-4 rounded-lg bg-accent-bg px-4 py-3 text-sm text-accent-text">
        Not available yet. Connecting your temple&rsquo;s own WhatsApp Business account needs a
        decision we haven&rsquo;t taken — shown here so you can see where it will live.
      </p>
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
