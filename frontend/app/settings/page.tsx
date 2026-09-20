"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Field } from "@/components/Field";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Form } from "@/components/ds/Form";
import { FieldRow } from "@/components/ds/FieldRow";
import { HintedField } from "@/components/ds/InfoHint";
import { RequireRole } from "@/components/RequireRole";
import { Sidebar } from "@/components/Sidebar";
import { Loading } from "@/components/Loading";
import { LanguageSection } from "@/components/LanguageSection";
import { ThemeMiniature } from "@/components/ThemeMiniature";
import { useAuth } from "@/lib/auth-context";
import { moment, templeDay } from "@/lib/format";
import {
  applyPalette,
  crossfadeTheme,
  THEME_FAMILY_FINISH,
  THEME_FAMILY_LABELS,
  type ThemeFamily,
} from "@/lib/theme";
import { choosableThemePacks, themePackById, type ThemePack } from "@/lib/theme-packs";
import {
  api,
  toApiError,
  type ApiError,
  type PaymentProviderOption,
  type PaymentSettingsView,
  type WebhookSubscriptionGroup,
  type WhatsAppSettingsView,
  type WhatsAppTemplateIssueKind,
  type WhatsAppTemplatesPending,
} from "@/lib/api";
import { normalizePhone } from "@/lib/phone";

/**
 * A temple's own settings: today, how it collects donations.
 *
 * <p>The two status lines come before any field on purpose. They answer questions that fail
 * independently — the keys reaching the provider, and the provider reaching us — and only the first
 * is something an administrator can prove by pressing a button. Put them at the foot and someone
 * presses Test, sees green, and spends a week wondering why no donation is ever confirmed.
 *
 * <p><b>Every section opens read-only, except Appearance (T-169).</b> Rajeev, 2026-09-13: *"The
 * default state of the screen shuld be read only to avoid accidental mistakes."* Edit opens one
 * section, and its button becomes Save, with Cancel beside it. Appearance stays a live picker, as he
 * ruled: *"No, leave the color picker as is."* The Language section is `LanguageSection`, a
 * component of its own, and follows the same rule (T-185), so Appearance is the one exception. How
 * it works is at {@link useEditMode}.
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
  const [equipmentServiceDays, setEquipmentServiceDays] = useState<number | null>(null);
  const [broadcastLimit, setBroadcastLimit] = useState<number | null>(null);
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
          setEquipmentServiceDays(temple.equipmentServiceWarningDays);
          setBroadcastLimit(temple.volunteerBroadcastDailyLimit);
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
      <main className="mx-auto max-w-4xl px-4 py-8 sm:px-10 sm:py-12">
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
    <main className="mx-auto max-w-4xl px-4 py-8 sm:px-10 sm:py-12">
      <h1 className="text-3xl font-semibold text-ink">Settings</h1>
      <p className="mt-1 max-w-[56ch] text-ink-secondary">
        Only a temple administrator can see or change any of this.
      </p>

      <AppearanceSection initial={themeId} onSaved={setThemeId} getToken={getToken} />

      <LanguageSection initial={locale} getToken={getToken} />

      <MenuSection />

      {stockExpiryDays !== null && contractEndDays !== null && equipmentServiceDays !== null && (
        <WarningsSection
          stockExpiryDays={stockExpiryDays}
          contractEndDays={contractEndDays}
          equipmentServiceDays={equipmentServiceDays}
          onSaved={(stock, contract, service) => {
            setStockExpiryDays(stock);
            setContractEndDays(contract);
            setEquipmentServiceDays(service);
          }}
          getToken={getToken}
        />
      )}

      {broadcastLimit !== null && (
        <VolunteerMessagesSection
          initial={broadcastLimit}
          onSaved={setBroadcastLimit}
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

// ---- Menu ------------------------------------------------------------------

/**
 * The way to Settings → Menu, which has no row in the left-hand menu of its own (D-M6).
 *
 * <p>A section rather than a line of text, so it reads as one of this screen's settings alongside
 * Appearance and Language — which is what it is: how the application presents itself to everybody at
 * this temple. It is the only section here with nothing to edit in place, because arranging a menu
 * needs the whole width of a screen rather than a field, so its footer holds a link where the others
 * hold Edit. Quiet, like Edit, since it commits nothing.
 *
 * <p>The sentence says what the screen decides and, just as importantly, what it does not: a person
 * still sees exactly the destinations their role allows, whatever the temple does with the order.
 */
function MenuSection() {
  return (
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Menu">
      <h2 className="text-lg font-semibold text-ink">Menu</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The order and the grouping of the left-hand menu, for everyone at this temple. What each
        person can open stays exactly what their role allows.
      </p>

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <ButtonLink href="/settings/menu" variant="secondary" className="min-h-touch px-6 text-sm">
          Arrange the menu
        </ButtonLink>
      </div>
    </section>
  );
}

// ---- Editing a section -----------------------------------------------------

/**
 * Read-only until Edit, on every section of this screen except Appearance (T-169).
 *
 * <p>Rajeev, 2026-09-13: *"The default state of the screen shuld be read only to avoid accidental
 * mistakes. The way to get it to edit is using the 'Edit' button. When clicked the fields become
 * editable and the button reads 'Save'."* Appearance is his one exception, *"No, leave the color
 * picker as is."*, because picking a pack there is already only a preview until its own Save.
 *
 * <p><b>Each section keeps its own edit mode, so two can be open at once.</b> That is deliberate.
 * Every Save sends only its own section, so an open section can never be committed by another
 * section's button, and that is the accident the rule exists to prevent. Allowing one open section
 * at a time would need an answer for pressing Edit on a second while the first holds typing:
 * throwing the typing away without a word is worse than the accident, and greying the other Edit
 * buttons out is the unexplained disabled button this screen has just stopped using.
 *
 * <p><b>Cancel puts back what the section showed at the moment Edit was pressed.</b> A snapshot
 * rather than a re-read of the props, so it means the same thing in every section, whichever way each
 * one happens to hold its values.
 *
 * <p><b>Closing remounts the section's `Form`</b>, through `formKey`. A red sentence goes when its
 * box is typed in or the form is submitted again, and neither can happen once the boxes are
 * read-only, so without the remount the sentence from a refused Save would outlive the Cancel that
 * abandoned it.
 */
function useEditMode<T>(current: T, restore: (values: T) => void) {
  const [editing, setEditing] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const before = useRef(current);

  function close() {
    setEditing(false);
    setFormKey((key) => key + 1);
  }

  return {
    editing,
    formKey,
    open() {
      before.current = current;
      setEditing(true);
    },
    cancel() {
      restore(before.current);
      close();
    },
    /** After a Save the server accepted, when what is on the screen is what is saved. */
    close,
  };
}

/**
 * Edit, or Cancel and Save, at the right-hand end of a section's footer.
 *
 * <p>Edit is quiet rather than primary. It commits nothing, and on the WhatsApp section the primary
 * style belongs to the templates button whenever something is waiting, which is the one thing on this
 * screen that asks to be pressed. Cancel comes before Save, as on every screen that commits (§4).
 *
 * <p>Save submits the section's `Form` from outside it with `form=`. That lets the form hold only the
 * boxes, so a Test, Send or Reveal button elsewhere in the section can never submit it, and Enter in
 * the test-message number box cannot save the account.
 *
 * <p>The buttons carry different keys so React builds a new element, rather than turning the Edit
 * button into a submit button under the pointer that has just pressed it.
 */
function EditActions({
  editing,
  formId,
  disabled,
  saving,
  saveLabel = "Save",
  savingLabel = "Saving…",
  saveDisabled = false,
  onEdit,
  onCancel,
}: {
  editing: boolean;
  formId: string;
  /** Something in this section is in flight. */
  disabled: boolean;
  saving: boolean;
  saveLabel?: string;
  savingLabel?: string;
  /** A reason other than a box being wrong, which is `Form`'s to say. */
  saveDisabled?: boolean;
  onEdit: () => void;
  onCancel: () => void;
}) {
  if (!editing) {
    return (
      <button
        key="edit"
        type="button"
        onClick={onEdit}
        disabled={disabled}
        className="btn btn-quiet min-h-touch px-6 text-sm disabled:opacity-60"
      >
        Edit
      </button>
    );
  }
  return (
    <>
      <button
        key="cancel"
        type="button"
        onClick={onCancel}
        disabled={disabled}
        className="btn btn-quiet min-h-touch px-5 text-sm disabled:opacity-60"
      >
        Cancel
      </button>
      <button
        key="save"
        type="submit"
        form={formId}
        disabled={disabled || saveDisabled}
        className="btn btn-primary min-h-touch px-6 text-sm transition-colors duration-state disabled:opacity-60"
      >
        {saving ? savingLabel : saveLabel}
      </button>
    </>
  );
}

/**
 * A box showing a saved value that cannot be typed in until Edit. Recessed, like the masked secrets
 * beside it, so it does not look like an empty invitation to type. `readOnly` rather than `disabled`:
 * the value stays in the tab order, can be selected and copied, and a screen reader says "read only"
 * rather than "dimmed". A `<select>` has no read-only state, so the one on this screen is disabled
 * instead and takes the same fill.
 */
const READ_ONLY_BOX = "read-only:bg-sunken";

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
  // True only between a Test connection that succeeded and the next thing pressed, so the keys
  // check can be green as the answer to that press and neutral the rest of the time (T-227).
  const [tested, setTested] = useState(false);
  const [webhookSecret, setWebhookSecret] = useState<string | null>(null);
  const secretId = useId();

  const edit = useEditMode({ provider, keyId, keySecret, replacing }, (before) => {
    setProvider(before.provider);
    setKeyId(before.keyId);
    setKeySecret(before.keySecret);
    setReplacing(before.replacing);
    setError(null);
  });
  const readOnly = !edit.editing;

  async function save() {
    setBusy("save");
    setError(null);
    setSaved(false);
    setTested(false);
    try {
      const next = await api.savePaymentSettings(
        { provider, keyId, keySecret: keySecret.trim() || undefined },
        await getToken()
      );
      onChanged(next);
      setKeySecret("");
      setReplacing(false);
      setSaved(true);
      edit.close();
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
    setTested(false);
    try {
      onChanged(await api.testPaymentSettings(await getToken()));
      setTested(true);
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
    // Named, because the WhatsApp section below has a test button of its own, and a screen reader
    // — or a test — needs to know which one it is on.
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Payment gateway">
      <h2 className="text-lg font-semibold text-ink">Payment gateway</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The account donations are paid into. Paying vendors is under Payments.
      </p>

      <div className="mt-5 grid gap-3 rounded-card bg-sunken px-5 py-4">
        <Check
          ok={Boolean(settings.verifiedAt)}
          justConfirmed={tested && Boolean(settings.verifiedAt)}
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

      {/*
        The boxes, and only the boxes, are the form (T-169b). Key ID and a key secret being typed
        carry `required`, so a blank one is named in red on Save. Save used to be greyed out until
        both were filled, which told nobody which box it was waiting for.
      */}
      <Form
        key={edit.formKey}
        id="payment-gateway-form"
        onSubmit={(event) => {
          event.preventDefault();
          if (edit.editing) void save();
        }}
      >
        <div className="mt-6">
          <HintedField label="Who handles your payments" hint="Ask us if yours is missing.">
            {(id) => (
              <select
                id={id}
                value={provider}
                onChange={(e) => setProvider(e.target.value)}
                disabled={readOnly}
                className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink disabled:bg-sunken"
              >
                {providers.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            )}
          </HintedField>
        </div>

        <div className="mt-5 grid gap-5 sm:grid-cols-2">
          {/* Where to find the value is guidance — wanted once, on the day this is set up, and in the
              way for good afterwards. The secret's own warning below is not, and stays visible. */}
          <HintedField label="Key ID" hint="From your provider’s dashboard, under API keys.">
            {(id) => (
              <input
                id={id}
                value={keyId}
                onChange={(e) => setKeyId(e.target.value)}
                readOnly={readOnly}
                required
                autoComplete="off"
                className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
              />
            )}
          </HintedField>

          {/*
            Both of this field's notes stay visible while the Key ID's moved into an "i", and the
            difference is not inconsistency. One says where to find a value; these say the value can
            never be read back, and one of them carries a live date. A warning about something
            irreversible that only appears under a pointer is a warning nobody was given.
          */}
          <div className="text-sm text-ink-secondary">
            {settings.configured && !replacing ? (
              <>
                Key secret
                {/* mt-1, not mt-1.5: HintedField sets the Key ID's label-to-box gap beside this one
                    at gap-1, and the two boxes are in the same row of the same grid. */}
                <div className="mt-1 flex gap-2">
                  <div className="flex min-h-touch flex-1 items-center rounded-control border border-hairline bg-sunken px-3 tracking-masked text-ink-muted">
                    ••••••••••••••••
                  </div>
                  {/* Replacing a secret is an edit, so it is offered only once Edit is pressed. */}
                  {edit.editing && (
                    <button
                      type="button"
                      onClick={() => setReplacing(true)}
                      className="btn btn-quiet min-h-touch px-3 text-sm"
                    >
                      Replace
                    </button>
                  )}
                </div>
                <span className="mt-1.5 block text-xs text-ink-muted">
                  Saved {when(settings.keySecretSavedAt)}. Kept encrypted, and never shown again.
                </span>
              </>
            ) : (
              <>
                {/* A label now, not bare words beside the box. `Form` names a refused box from its
                    label, and this box had none, so a blank secret would have been called "This
                    field". A screen reader had no name for it either. */}
                <label htmlFor={secretId}>Key secret</label>
                <input
                  id={secretId}
                  type="password"
                  value={keySecret}
                  onChange={(e) => setKeySecret(e.target.value)}
                  readOnly={readOnly}
                  required
                  autoComplete="new-password"
                  className={`mt-1 min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
                />
                <span className="mt-1.5 block text-xs text-ink-muted">
                  Stored encrypted, away from this temple’s records. It is never shown again.
                </span>
              </>
            )}
          </div>
        </div>
      </Form>

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
                  <div className="flex min-h-touch min-w-0 flex-1 items-center overflow-hidden rounded-control bg-sunken px-3 tracking-masked text-ink-muted">
                    ••••••••••••••••••••
                  </div>
                  <button
                    type="button"
                    onClick={reveal}
                    disabled={busy !== null}
                    className="btn btn-quiet min-h-touch px-3 text-sm disabled:opacity-60"
                  >
                    {busy === "reveal" ? "…" : "Reveal"}
                  </button>
                </div>
              )}
              {/* Visible, not an "i": that a reveal is written to the audit log is something the
                  administrator has to be told before they press the button, not after. */}
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
                        className="rounded-control bg-sunken px-2 py-1 font-mono text-xs text-ink-secondary"
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

        While the section is being edited, the footer holds only Cancel and Save. Test connection
        checks what is saved, not what is being typed, so offering it beside half-typed keys would
        answer a question nobody asked.
      */}
      <div className="mt-7 flex flex-wrap items-center gap-3 border-t border-hairline pt-6">
        {!edit.editing && (
          <>
            <button
              type="button"
              onClick={test}
              disabled={busy !== null || !settings.configured}
              className="btn btn-quiet min-h-touch px-5 text-sm disabled:opacity-60"
            >
              {busy === "test" ? "Checking…" : "Test connection"}
            </button>
            {!settings.configured && (
              <span className="text-sm text-ink-muted">
                Press Edit and save your keys first. Save checks your keys with{" "}
                {label(providers, provider)}, and this button re-checks them later.
              </span>
            )}
          </>
        )}
        <span className="flex-1" />
        <EditActions
          editing={edit.editing}
          formId="payment-gateway-form"
          disabled={busy !== null}
          saving={busy === "save"}
          onEdit={() => {
            setSaved(false);
            edit.open();
          }}
          onCancel={edit.cancel}
        />
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

/**
 * One line of a setup checklist.
 *
 * <p>"Not yet" is amber, because the administrator has something to do. "Working" is neutral as a
 * standing status, and green only as the immediate answer to the administrator pressing Test and it
 * succeeding (`justConfirmed`), because green is kept for a user's own action doing what they
 * expected (Rajeev, 2026-09-18, T-227). Open the page a week later and it is plain again. The plain
 * chip sits on `raised` rather than the Badge's `sunken`, because the checklist itself is sunken and
 * a sunken chip on it would vanish.
 */
function Check({
  ok,
  justConfirmed = false,
  okLabel,
  waitLabel,
  title,
  detail,
}: {
  ok: boolean;
  justConfirmed?: boolean;
  okLabel: string;
  waitLabel: string;
  title: string;
  detail: string;
}) {
  return (
    <div className="flex items-start gap-3">
      <span
        className={[
          "mt-0.5 rounded-control px-2.5 py-0.5 text-xs",
          !ok
            ? "bg-warning-bg text-warning"
            : justConfirmed
              ? "bg-success-bg text-success"
              : "bg-raised text-ink-secondary",
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
      <code className="min-h-touch min-w-0 flex-1 overflow-x-auto whitespace-nowrap rounded-control bg-sunken px-3 py-2.5 font-mono text-xs text-ink">
        {value}
      </code>
      <button
        type="button"
        onClick={() => {
          navigator.clipboard?.writeText(value);
          setCopied(true);
        }}
        className="btn btn-quiet min-h-touch px-3 text-sm"
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

// ---- Messaging -------------------------------------------------------------

/**
 * What the view says is waiting when it carries no count. The server always sends one (T-169a); the
 * field is optional in `api.ts` only while the settings test fixtures catch up, so this exists for
 * the type, and reads as nothing waiting.
 */
const NOTHING_PENDING: WhatsAppTemplatesPending = { changed: 0, refused: 0, accountChanged: false, unchecked: 0 };

/** How Meta answered for one template, in words. `HELD_UNDER_ANOTHER_CATEGORY` is a note, not a fault. */
const TEMPLATE_ISSUE_LABEL: Record<WhatsAppTemplateIssueKind, string> = {
  REFUSED: "Refused",
  NOT_REACHED: "Not reached",
  HELD_UNDER_ANOTHER_CATEGORY: "Note",
};

/**
 * What the templates button says (T-169, option 2). Rajeev chose a button that says what is waiting
 * over a fixed "Reload WhatsApp templates": *"I like option 2. I tis clean and honest."*
 *
 * <p>`changed` and `refused` never count one template twice (T-169a), so when both are waiting they
 * are added into one number. A changed account comes first, because then every template has to go to
 * the new account whatever the counts say. A temple with no recorded send and nothing else to say is
 * waiting too, since "last sent on" would have no date to give.
 *
 * <p><b>Unknown is not nothing (T-188).</b> `unchecked` counts templates whose wording at Meta nothing has
 * recorded, as on a temple that last sent before V129. On staging, 2026-09-13, such a temple read "last
 * sent to Meta on …" while Meta held the old wording of eleven templates. So it comes straight after a
 * changed account and ahead of the counts, because "1 template changed" beside eighteen nobody has checked
 * would read as the whole story. With nothing ever sent, "not yet sent" is the truer thing to say. Read as
 * `?? 0` while `api.ts` carries the field as optional mid-wave.
 *
 * <p>Every one of these is twelve words or fewer, in sentence case (§9).
 */
function templatesButtonLabel(pending: WhatsAppTemplatesPending, submittedAt: string | null): string {
  if (pending.accountChanged) return "Templates not yet sent to your new WhatsApp account";
  if ((pending.unchecked ?? 0) > 0) {
    return submittedAt ? "Current template wording waiting to go to Meta" : "Templates not yet sent to Meta";
  }
  if (pending.changed > 0 && pending.refused > 0) {
    return `${pending.changed + pending.refused} templates waiting to go to Meta`;
  }
  if (pending.changed > 0) {
    return pending.changed === 1
      ? "1 template changed since it was last sent"
      : `${pending.changed} templates changed since they were last sent`;
  }
  if (pending.refused > 0) {
    return pending.refused === 1
      ? "1 template Meta did not accept last time"
      : `${pending.refused} templates Meta did not accept last time`;
  }
  if (!submittedAt) return "Templates not yet sent to Meta";
  return `Templates last sent to Meta on ${templeDay(submittedAt)}`;
}

/**
 * WhatsApp, in the same shape as the payment gateway above it, because to an administrator it is the
 * same kind of task: connect an account the temple owns, prove it works, be told what to paste where.
 *
 * <p>The two status lines are separate for the same reason as the gateway's. Whether our messages
 * reach Meta and whether Meta's receipts reach us fail independently, and only the first is settled
 * by pressing a button.
 *
 * <p><b>Templates go to Meta by their own button (T-169).</b> The first connection still sends them,
 * because a temple that has just connected has nothing at Meta yet. After that Save only saves the
 * account, and the templates button sends whatever is waiting: wording a release changed, templates
 * Meta did not take last time, or everything, when the account itself changed. The button is shown
 * only once the temple is connected, because pressing it before that is refused (KMS-400001, the same
 * answer as the test message).
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
  // The Meta App ID (T-200). Not a secret, so it is shown in full and edited like the two ids.
  const [appId, setAppId] = useState(settings.appId ?? "");
  const [accessToken, setAccessToken] = useState("");
  const [appSecret, setAppSecret] = useState("");
  const [replacing, setReplacing] = useState(!settings.connected);
  const [busy, setBusy] = useState<"save" | "test" | "reveal" | "reload" | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);
  const [verifyToken, setVerifyToken] = useState<string | null>(null);
  // The test send (T-151). Rajeev, 2026-09-12: "Ask the use for a phone number to send a test
  // message." The number is asked for in place, where the button was, rather than on a screen of its
  // own: it is one box, used once or twice in a temple's life.
  const [askingForNumber, setAskingForNumber] = useState(false);
  const [testNumber, setTestNumber] = useState("");
  const [sentTo, setSentTo] = useState<string | null>(null);
  // Sending templates takes as long as Meta takes to answer twenty of them, a minute or more. The
  // ref is the guard against a second press, because a second click can land before React has
  // re-rendered the button as disabled.
  const [reloadError, setReloadError] = useState<ApiError | null>(null);
  const [reloaded, setReloaded] = useState(false);
  const reloading = useRef(false);

  const edit = useEditMode({ phoneNumberId, wabaId, appId, accessToken, appSecret, replacing }, (before) => {
    setPhoneNumberId(before.phoneNumberId);
    setWabaId(before.wabaId);
    setAppId(before.appId);
    setAccessToken(before.accessToken);
    setAppSecret(before.appSecret);
    setReplacing(before.replacing);
    setError(null);
  });
  const readOnly = !edit.editing;

  // Present on every answer the server gives (T-169a); the fallbacks are for the optional type only.
  const pending = settings.templatesPending ?? NOTHING_PENDING;
  const issues = settings.refusedTemplates ?? [];
  const waiting =
    pending.accountChanged ||
    pending.changed + pending.refused + (pending.unchecked ?? 0) > 0 ||
    !settings.templatesSubmittedAt;
  const reloadStyle = waiting ? "btn-primary" : "btn-quiet";
  // Waiting ones first. A note about a category is not something to act on, so it goes last.
  const listed = [...issues].sort(
    (a, b) =>
      Number(a.kind === "HELD_UNDER_ANOTHER_CATEGORY") - Number(b.kind === "HELD_UNDER_ANOTHER_CATEGORY")
  );

  async function save() {
    setBusy("save");
    setError(null);
    setSaved(false);
    try {
      const next = await api.saveWhatsAppSettings(
        {
          phoneNumberId: phoneNumberId.trim(),
          wabaId: wabaId.trim(),
          // Always sent, blank included: the server stores what is sent, and blank clears it.
          appId: appId.trim(),
          accessToken: accessToken.trim() || undefined,
          appSecret: appSecret.trim() || undefined,
        },
        await getToken()
      );
      onChanged(next);
      // The box shows what was stored, not what was typed: the server trims it, and blank clears it.
      setAppId(next.appId ?? "");
      setAccessToken("");
      setAppSecret("");
      setReplacing(false);
      setSaved(true);
      edit.close();
    } catch (e) {
      setError(toApiError(e, "We couldn’t connect that WhatsApp account."));
    } finally {
      setBusy(null);
    }
  }

  /**
   * Sends every template that is waiting, and shows what came back. The answer is the whole settings
   * view, so the button's own words and the list under it are Meta's answer to this press.
   */
  async function reload() {
    if (reloading.current) return;
    reloading.current = true;
    setBusy("reload");
    setReloadError(null);
    setReloaded(false);
    setSaved(false);
    setSentTo(null);
    try {
      onChanged(await api.reloadWhatsAppTemplates(await getToken()));
      setReloaded(true);
    } catch (e) {
      setReloadError(toApiError(e, "We couldn’t send your templates to Meta."));
    } finally {
      reloading.current = false;
      setBusy(null);
    }
  }

  /**
   * Sends a real WhatsApp message to the number typed. Whether the number is well formed is the
   * server's to say, with the same rule and the same KMS-400003 as every other phone number, rather
   * than a second copy of that rule here that could drift from it.
   */
  async function sendTest() {
    // Separators removed, as on every E.164 box (T-157). The rule itself stays the server's.
    const number = normalizePhone(testNumber);
    setBusy("test");
    setError(null);
    setSaved(false);
    setSentTo(null);
    try {
      onChanged(await api.sendWhatsAppTestMessage(number, await getToken()));
      setSentTo(number);
      setAskingForNumber(false);
    } catch (e) {
      setError(toApiError(e, "We couldn’t send the test message."));
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
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="WhatsApp">
      <h2 className="text-lg font-semibold text-ink">WhatsApp</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The temple sends as its own number, falling back to SMS.
      </p>

      <div className="mt-5 grid gap-3 rounded-card bg-sunken px-5 py-4">
        <Check
          ok={Boolean(settings.verifiedAt)}
          justConfirmed={sentTo !== null && Boolean(settings.verifiedAt)}
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
              : "Press Edit, enter the four values below, then press Connect."
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

      {/*
        The four boxes are the form (T-169b). The two ids always carry `required`, and so do the token
        and the app secret whenever they are being typed: connecting needs both, and so does
        replacing them, because Meta checks the pair together. Save used to be greyed out until all
        four were filled, which never said which one it was waiting for.
      */}
      <Form
        key={edit.formKey}
        id="whatsapp-form"
        onSubmit={(event) => {
          event.preventDefault();
          if (edit.editing) void save();
        }}
      >
        <div className="mt-6 grid gap-5 sm:grid-cols-2">
          {/* All four of these are "go to this page in Meta's dashboard and copy that box" — read
              once and never again, so they sit in the "i" rather than under four boxes in a row. */}
          <HintedField
            label="Phone number ID"
            hint="Under WhatsApp → API Setup. Not the phone number, the id beneath it."
          >
            {(id) => (
              <input
                id={id}
                value={phoneNumberId}
                onChange={(e) => setPhoneNumberId(e.target.value)}
                readOnly={readOnly}
                required
                className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
              />
            )}
          </HintedField>

          <HintedField
            label="WhatsApp Business Account ID"
            hint="On the same screen. This is what owns your approved message templates."
          >
            {(id) => (
              <input
                id={id}
                value={wabaId}
                onChange={(e) => setWabaId(e.target.value)}
                readOnly={readOnly}
                required
                className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
              />
            )}
          </HintedField>

          {/* The Meta App ID (T-200). Registering the purchase-order message with its PDF is addressed to
              the app, so a temple without it cannot send orders as a PDF; every other message is unaffected,
              which is why it is not required. Not a secret, so it is an ordinary box shown in full. */}
          <HintedField
            label="App ID"
            hint="Meta dashboard → your app → App settings → Basic, at the top. Needed to send purchase orders as a PDF."
          >
            {(id) => (
              <input
                id={id}
                value={appId}
                onChange={(e) => setAppId(e.target.value)}
                readOnly={readOnly}
                // No `pattern`: a pasted id often carries a space either side, which the save trims, and
                // whether what is left is digits is the server's rule, said beside the box if it refuses.
                inputMode="numeric"
                className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
              />
            )}
          </HintedField>

          <div className="sm:col-span-2 grid gap-5 sm:grid-cols-2">
            {settings.connected && !replacing ? (
              <div className="sm:col-span-2">
                <p className="text-sm text-ink-secondary">Access token and app secret</p>
                <div className="mt-1.5 flex gap-2">
                  <div className="flex min-h-touch flex-1 items-center rounded-control bg-sunken px-3 tracking-masked text-ink-muted">
                    ••••••••••••••••••••
                  </div>
                  {/* Replacing them is an edit, so it is offered only once Edit is pressed. */}
                  {edit.editing && (
                    <button
                      type="button"
                      onClick={() => setReplacing(true)}
                      className="btn btn-quiet min-h-touch px-3 text-sm"
                    >
                      Replace
                    </button>
                  )}
                </div>
                {/* Stays on the page while the four field hints around it moved into an "i". It is
                    not guidance: it says these two can never be read back. */}
                <span className="mt-1.5 block text-xs text-ink-muted">
                  Stored encrypted, away from this temple’s records. Neither is ever shown again.
                </span>
              </div>
            ) : (
              <>
                <HintedField
                  label="Permanent access token"
                  hint="A System User token. The temporary one expires in a day."
                >
                  {(id) => (
                    <input
                      id={id}
                      type="password"
                      value={accessToken}
                      onChange={(e) => setAccessToken(e.target.value)}
                      readOnly={readOnly}
                      required
                      autoComplete="new-password"
                      className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
                    />
                  )}
                </HintedField>

                <HintedField
                  label="App secret"
                  hint="App settings → Basic. We check every delivery receipt against it."
                >
                  {(id) => (
                    <input
                      id={id}
                      type="password"
                      value={appSecret}
                      onChange={(e) => setAppSecret(e.target.value)}
                      readOnly={readOnly}
                      required
                      autoComplete="new-password"
                      className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
                    />
                  )}
                </HintedField>
              </>
            )}
          </div>
        </div>
      </Form>

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
                  <div className="flex min-h-touch flex-1 items-center rounded-control bg-sunken px-3 tracking-masked text-ink-muted">
                    ••••••••••••••••••••
                  </div>
                  <button
                    type="button"
                    onClick={reveal}
                    disabled={busy !== null}
                    className="btn btn-quiet min-h-touch px-3 text-sm disabled:opacity-60"
                  >
                    {busy === "reveal" ? "…" : "Reveal"}
                  </button>
                </div>
              )}
              {/* Same rule as the gateway's webhook secret: a reveal that is recorded is said
                  before the button, in the open. */}
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
        </>
      )}

      {/*
        The templates, once there is an account to hold them (T-169b). The date they were last sent
        is the quiet button's own words and is said nowhere else, so it cannot be said twice.

        The button steps aside while the section is being edited, for the same reason as Test: it
        sends to the account that is saved, and beside a half-typed new account that is not the one
        anybody would expect.
      */}
      {settings.connected && (
        <>
          <h3 className="mt-8 text-base font-semibold text-ink">Message templates</h3>
          <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
            Until Meta approves a template, messages using it go by SMS.
          </p>

          {!edit.editing && (
            <div className="mt-4 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={reload}
                disabled={busy !== null}
                aria-busy={busy === "reload"}
                className={`btn ${reloadStyle} min-h-touch px-5 text-sm transition-colors duration-state disabled:opacity-60`}
              >
                {busy === "reload"
                  ? "Sending templates to Meta…"
                  : templatesButtonLabel(pending, settings.templatesSubmittedAt)}
              </button>
              {busy === "reload" && (
                <span role="status" className="text-sm text-ink-muted">
                  This can take a minute or two.
                </span>
              )}
            </div>
          )}

          {reloadError && (
            <div role="alert" className="mt-4 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
              <p className="font-medium">{reloadError.message}</p>
              <p className="mt-0.5">{reloadError.action}</p>
            </div>
          )}
          {reloaded && !reloadError && (
            <p role="status" className={`mt-3 text-sm ${waiting ? "text-ink-secondary" : "text-success"}`}>
              {waiting ? "Sent to Meta. Some templates still need attention." : "Templates sent to Meta."}
            </p>
          )}

          {/*
            What Meta said about each template on the last send. A refusal and a template Meta never
            answered for each give their stored plain reason, and are what the button is waiting to
            send again. A template Meta holds under another category is a note: Meta keeps it as
            marketing, and sending it again cannot change that, so it is not counted as waiting and
            says so.
          */}
          {listed.length > 0 && (
            <ul className="mt-5 grid gap-3" aria-label="What Meta said about each template">
              {listed.map((issue) => {
                const note = issue.kind === "HELD_UNDER_ANOTHER_CATEGORY";
                return (
                  <li key={issue.name} className="flex items-start gap-3">
                    <span
                      className={[
                        "mt-0.5 shrink-0 rounded-control px-2.5 py-0.5 text-xs",
                        note ? "bg-sunken text-ink-secondary" : "bg-warning-bg text-warning",
                      ].join(" ")}
                    >
                      {TEMPLATE_ISSUE_LABEL[issue.kind]}
                    </span>
                    <span className="grid min-w-0">
                      <span className="break-words font-mono text-xs text-ink">{issue.name}</span>
                      <span className="max-w-[70ch] text-xs text-ink-secondary">
                        {issue.reason}
                        {note && " Sending again cannot change this."}
                      </span>
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
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
      {sentTo && !error && (
        <p className="mt-6 text-sm text-success">
          Test message sent to {sentTo}. Check WhatsApp on that phone.
        </p>
      )}

      <div className="mt-7 flex flex-wrap items-end gap-3 border-t border-hairline pt-6">
        {edit.editing ? (
          // What Save does to templates, said where it is about to be pressed. A first connection
          // sends them; after that, Save only saves, and the templates button above does the sending.
          <span className="text-sm text-ink-muted">
            {settings.connected
              ? "Saving does not send templates to Meta."
              : "Connecting also sends your message templates to Meta."}
          </span>
        ) : askingForNumber ? (
          <>
            <div className="min-w-0 flex-1 sm:max-w-xs">
              <HintedField
                label="Send a test message to"
                hint="A WhatsApp number, with its country code. Meta must approve the test message first, which can take up to a day after you connect."
              >
                {(id) => (
                  <input
                    id={id}
                    type="tel"
                    inputMode="tel"
                    autoComplete="tel"
                    placeholder="+919876543210"
                    value={testNumber}
                    onChange={(e) => setTestNumber(e.target.value)}
                    className="min-h-touch w-full rounded-control border border-hairline px-3 text-ink"
                  />
                )}
              </HintedField>
            </div>
            <button
              type="button"
              onClick={sendTest}
              disabled={busy !== null || !normalizePhone(testNumber)}
              className="btn btn-quiet min-h-touch px-5 text-sm disabled:opacity-60"
            >
              {busy === "test" ? "Sending…" : "Send"}
            </button>
            <button
              type="button"
              onClick={() => {
                setAskingForNumber(false);
                setError(null);
              }}
              disabled={busy !== null}
              className="btn btn-quiet min-h-touch px-3 text-sm disabled:opacity-60"
            >
              Cancel
            </button>
          </>
        ) : (
          <button
            type="button"
            onClick={() => {
              setAskingForNumber(true);
              setSentTo(null);
              setSaved(false);
            }}
            disabled={busy !== null || !settings.connected}
            className="btn btn-quiet min-h-touch px-5 text-sm disabled:opacity-60"
          >
            Send a test message
          </button>
        )}
        {!edit.editing && !settings.connected && (
          <span className="text-sm text-ink-muted">
            Press Edit and connect your account first. Then this sends a real message to a phone you
            choose.
          </span>
        )}
        <span className="flex-1" />
        <EditActions
          editing={edit.editing}
          formId="whatsapp-form"
          disabled={busy !== null}
          saving={busy === "save"}
          saveLabel={settings.connected ? "Save" : "Connect"}
          savingLabel={settings.connected ? "Saving…" : "Connecting…"}
          onEdit={() => {
            setAskingForNumber(false);
            setSentTo(null);
            setSaved(false);
            setReloaded(false);
            edit.open();
          }}
          onCancel={edit.cancel}
        />
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
 *
 * <p>The box is not `required`: left empty, a reply reaches the platform instead, which is a real
 * choice. What `Form` does refuse here is an address that is not one, from the box's `type="email"`.
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

  const edit = useEditMode(email, (before) => {
    setEmail(before);
    setError(null);
  });

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.saveTempleContactEmail(email.trim(), await getToken());
      setSaved(true);
      edit.close();
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Email">
      <h2 className="text-lg font-semibold text-ink">Email</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        Where a devotee’s reply comes back to. There is nothing to set up.
      </p>

      <div className="mt-5 rounded-card bg-sunken px-5 py-4">
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

      {/* What happens if it is left blank — guidance, not a warning about anything irreversible,
          and the panel above already shows the live consequence as "Reply-To: not set". */}
      <Form
        key={edit.formKey}
        id="email-form"
        className="mt-6 max-w-md"
        onSubmit={(event) => {
          event.preventDefault();
          if (edit.editing) void save();
        }}
      >
        <HintedField
          label="Your temple’s email address"
          hint="Leave it empty and a reply reaches us instead of you."
        >
          {(id) => (
            <input
              id={id}
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              readOnly={!edit.editing}
              placeholder="kitchen@yourtemple.org"
              className={`min-h-touch w-full rounded-control border border-hairline px-3 text-ink ${READ_ONLY_BOX}`}
            />
          )}
        </HintedField>
      </Form>

      {error && (
        <div role="alert" className="mt-6 rounded-lg bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && <p className="mt-6 text-sm text-success">Saved.</p>}

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <EditActions
          editing={edit.editing}
          formId="email-form"
          disabled={busy}
          saving={busy}
          onEdit={() => {
            setSaved(false);
            edit.open();
          }}
          onCancel={edit.cancel}
        />
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
      applyPalette(document.documentElement, preview.palette, preview.surfaces, preview.finish)
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
          committedRef.current.surfaces,
          committedRef.current.finish
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

  /**
   * Quiet first, loud last (Rajeev, 2026-09-06). The picker used to open on the vibrant packs, which
   * put the loudest thing in the catalogue in front of somebody who had not yet decided they wanted
   * one. A temple that wants a bright application will go looking for it; a temple that wants a calm
   * one should not have to scroll past five festival palettes to find out calm is on offer.
   */
  const families: ThemeFamily[] = ["MUTED", "BALANCED", "VIBRANT"];
  const unsaved = chosen !== saved;

  return (
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Appearance">
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
          className="btn btn-primary min-h-touch shrink-0 px-6 text-sm font-medium transition-colors duration-state disabled:opacity-60"
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
            <fieldset key={family} className="mt-7 min-w-0">
              {/* §5: the finish belongs in the heading. It is the half of the difference between
                  these three groups that somebody can actually put a word to. */}
              <legend className="pl-field-inset text-xs font-medium uppercase tracking-eyebrow text-ink-secondary">
                {THEME_FAMILY_LABELS[family]} · {THEME_FAMILY_FINISH[family]}
              </legend>
              <div className="mt-3 grid items-stretch gap-4 sm:grid-cols-2 lg:grid-cols-3">
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
 * One pack in the picker: a radio you can see, a name, a sentence, and a picture of the pack.
 *
 * <p>The radio is visible rather than `sr-only`. It was hidden behind the card, on the reasoning
 * that the whole card was the target and a stray dot was clutter — which is true right up until
 * somebody cannot tell which one is selected without reading the border colour, in a screen whose
 * entire subject is that border colours change.
 *
 * <p>THEME-TOKENS §5 governs the rest of it: a 2px `accent` border and an `accent-bg` fill for the
 * selected card rather than a change of text colour, the name at 15px semibold, the description on
 * one clamped line, and every card the same height so a long sentence cannot make one card in a row
 * taller than its neighbours.
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
      className={`flex h-full min-w-0 cursor-pointer flex-col rounded-card p-3 transition-colors duration-state ${
        checked
          ? "border-2 border-accent bg-accent-bg"
          : "border-2 border-hairline hover:border-hairline-strong"
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
        <span className="text-[15px] font-semibold text-ink">{pack.name}</span>
        {isCurrent && <span className="text-xs text-ink-muted">in use</span>}
      </span>
      <span className="mt-1 block truncate text-[13px] text-ink-secondary" title={pack.description}>
        {pack.description}
      </span>
      <ThemeMiniature pack={pack} />
    </label>
  );
}

// ---- Volunteer messages ----------------------------------------------------

/** 1 to 20. One a day is a real choice; twenty in a day is not a cap, it is a formality. */
const MIN_BROADCAST = 1;
const MAX_BROADCAST = 20;

/**
 * A number box with its unit after it, laid out so `Form`'s sentence has somewhere to go.
 *
 * <p>`Form` puts its red sentence straight after the box it refuses. These boxes used to sit inside a
 * 7rem wrapper, which would have squeezed the sentence into 7rem, or beside their unit, which would
 * have put it between the box and the word "days". So the box is a direct child of a wrapping row,
 * and the sentence's slot is sent after the unit (`order-last`) onto a line of its own
 * (`basis-full`). The box's width is forced over `Field`'s `w-full`, whose order in the stylesheet
 * is not ours to rely on.
 */
const NUMBER_ROW =
  "flex flex-wrap items-center gap-x-2 [&>[data-form-error-slot]]:order-last [&>[data-form-error-slot]]:basis-full";
const NUMBER_BOX = `!w-28 ${READ_ONLY_BOX}`;

/**
 * How many update messages may go out about one shift in a day.
 *
 * <p><b>Why this screen exists at all.</b> `KMS-400065` has always ended "or ask a Temple Admin to
 * raise the limit", and until now there was nowhere for that administrator to go — the endpoint was
 * written, the client method was written, and no screen called either. An error message that tells
 * somebody to ask for a thing the product cannot do is worse than a bare refusal: it sends a
 * volunteer coordinator to an administrator who then cannot help them, and neither of them finds out
 * why. The number now lives where the message says it lives.
 *
 * <p>Its own section rather than a fourth box under Warnings, which it superficially resembles.
 * Those three are all "how long before a date do you want telling"; this is "how often may we
 * message somebody", which is a question about volunteers rather than about dates — and it is
 * answered by the same person for a different reason.
 *
 * <p><b>The bounds are the box's own `min`, `max` and `required` (T-169b).</b> A cap out of range
 * used to print its own sentence under the box and grey Save out. `Form` now names the box in red
 * when Save is pressed, from the same attributes, so there is one rule and one sentence. Save stays
 * greyed out only while nothing has changed, which is a different reason and still a true one.
 */
function VolunteerMessagesSection({
  initial,
  onSaved,
  getToken,
}: {
  initial: number;
  onSaved: (limit: number) => void;
  getToken: () => Promise<string | undefined>;
}) {
  const [value, setValue] = useState(String(initial));
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const edit = useEditMode(value, (before) => {
    setValue(before);
    setError(null);
  });
  const unchanged = value === String(initial);

  async function save() {
    // `Form` has already refused anything that is not a whole number from 1 to 20.
    const limit = Number(value);
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.setBroadcastLimit(limit, await getToken());
      onSaved(limit);
      setSaved(true);
      edit.close();
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Volunteer messages">
      <h2 className="text-lg font-semibold text-ink">Volunteer messages</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        How often a shift may message the volunteers on it.
      </p>

      <Form
        key={edit.formKey}
        id="volunteer-messages-form"
        className="mt-6 max-w-md"
        onSubmit={(event) => {
          event.preventDefault();
          if (edit.editing) void save();
        }}
      >
        {/* `required` on the box rather than on `Field`: the box always opens on the temple's own
            number, so a "(required)" beside its label would tell nobody anything. The attribute is
            there so a box somebody clears is named rather than sent. */}
        <Field
          id="volunteer-broadcast-daily-limit"
          label="Update messages per shift, per day"
          hint="Reached the cap? The coordinator is told to try tomorrow, or to ask you to raise it. This is where you raise it."
        >
          {(props) => (
            <div className={NUMBER_ROW}>
              <input
                {...props}
                className={`${props.className} ${NUMBER_BOX}`}
                type="number"
                inputMode="numeric"
                min={MIN_BROADCAST}
                max={MAX_BROADCAST}
                required
                readOnly={!edit.editing}
                value={value}
                onChange={(e) => {
                  setValue(e.target.value);
                  setSaved(false);
                }}
              />
              <span className="text-sm text-ink-secondary">a day</span>
            </div>
          )}
        </Field>
      </Form>

      <p className="mt-3 max-w-[60ch] text-sm text-ink-secondary">
        The cap is per shift, not per temple: a busy Sunday with four shifts on it can still send
        four times this many messages. It exists so that one shift being rearranged repeatedly does
        not empty a volunteer’s patience along with their inbox.
      </p>

      {error && (
        <div role="alert" className="mt-6 rounded-card bg-danger-bg px-4 py-3 text-sm text-danger">
          <p className="font-medium">{error.message}</p>
          <p className="mt-0.5">{error.action}</p>
        </div>
      )}
      {saved && !error && <p role="status" className="mt-6 text-sm text-success">Saved.</p>}

      <div className="mt-7 flex items-center gap-3 border-t border-hairline pt-6">
        <span className="flex-1" />
        <EditActions
          editing={edit.editing}
          formId="volunteer-messages-form"
          disabled={busy}
          saving={busy}
          saveDisabled={unchanged}
          onEdit={() => {
            setSaved(false);
            edit.open();
          }}
          onCancel={edit.cancel}
        />
      </div>
    </section>
  );
}

/** The bounds the request record and the database both carry. Kept here so the box says so too. */
const MIN_WARNING_DAYS = 1;
const MAX_WARNING_DAYS = 365;

/**
 * The three horizons, together.
 *
 * <p>They are one section and one Save because the first two were one number until recently — seven
 * days, shared between a sack of flour and a supplier agreement. The contract one outgrew it, and
 * the point of moving both here rather than only the one that changed is that a temple sets its
 * notice in one place and can see them beside each other.
 *
 * <p>The servicing horizon (E3-S10 D5) is the third, and it is here for a sharper reason than
 * tidiness. Its endpoint took it as an <em>optional</em> field precisely because this screen was
 * built for two: a plain required number would have meant every save from here silently resetting a
 * horizon the form was not showing. Now that all three are posted, that hazard is gone.
 *
 * <p>None of the three does anything beyond deciding which rows carry a warning badge. No vendor is
 * dropped, no batch is written off, and no machine is taken out of service by a date.
 *
 * <p><b>The bounds are each box's `min`, `max` and `required` (T-169b)</b>, and `Form` names a box
 * that breaks them when Save is pressed. Each box used to print "A warning is between 1 and 365
 * days." under itself as it was typed, and grey Save out until all three were in range, so two
 * checks said the same thing in two places. Now there is one.
 */
function WarningsSection({
  stockExpiryDays,
  contractEndDays,
  equipmentServiceDays,
  onSaved,
  getToken,
}: {
  stockExpiryDays: number;
  contractEndDays: number;
  equipmentServiceDays: number;
  onSaved: (stockExpiryDays: number, contractEndDays: number, equipmentServiceDays: number) => void;
  getToken: () => Promise<string | undefined>;
}) {
  const [stock, setStock] = useState(String(stockExpiryDays));
  const [contract, setContract] = useState(String(contractEndDays));
  const [service, setService] = useState(String(equipmentServiceDays));
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const edit = useEditMode({ stock, contract, service }, (before) => {
    setStock(before.stock);
    setContract(before.contract);
    setService(before.service);
    setError(null);
  });
  const readOnly = !edit.editing;

  async function save() {
    // `Form` has already refused anything that is not a whole number of days from 1 to 365.
    const stockDays = Number(stock);
    const contractDays = Number(contract);
    const serviceDays = Number(service);
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      await api.setWarningHorizons(
        {
          stockExpiryWarningDays: stockDays,
          contractEndWarningDays: contractDays,
          equipmentServiceWarningDays: serviceDays,
        },
        await getToken()
      );
      onSaved(stockDays, contractDays, serviceDays);
      setSaved(true);
      edit.close();
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card mt-6 px-5 py-6 sm:px-7 sm:py-7" aria-label="Warnings">
      <h2 className="text-lg font-semibold text-ink">Warnings</h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        How much notice you want before a date runs out on you.
      </p>

      {/* `required` on each box rather than on `Field`, for the reason given under Volunteer
          messages: each opens on the temple's own number. */}
      <Form
        key={edit.formKey}
        id="warnings-form"
        onSubmit={(event) => {
          event.preventDefault();
          if (edit.editing) void save();
        }}
      >
        <FieldRow className="mt-6">
          <Field
            id="stock-expiry-warning-days"
            label="Notice before stock expires"
            hint="Batches closer than this are badged on Inventory."
          >
            {(props) => (
              <div className={NUMBER_ROW}>
                <input
                  {...props}
                  className={`${props.className} ${NUMBER_BOX}`}
                  type="number"
                  inputMode="numeric"
                  min={MIN_WARNING_DAYS}
                  max={MAX_WARNING_DAYS}
                  required
                  readOnly={readOnly}
                  value={stock}
                  onChange={(e) => {
                    setStock(e.target.value);
                    setSaved(false);
                  }}
                />
                <span className="text-sm text-ink-secondary">days</span>
              </div>
            )}
          </Field>

          <Field
            id="contract-end-warning-days"
            label="Notice before a vendor contract ends"
            hint="Enough time to renegotiate, or to find somebody else."
          >
            {(props) => (
              <div className={NUMBER_ROW}>
                <input
                  {...props}
                  className={`${props.className} ${NUMBER_BOX}`}
                  type="number"
                  inputMode="numeric"
                  min={MIN_WARNING_DAYS}
                  max={MAX_WARNING_DAYS}
                  required
                  readOnly={readOnly}
                  value={contract}
                  onChange={(e) => {
                    setContract(e.target.value);
                    setSaved(false);
                  }}
                />
                <span className="text-sm text-ink-secondary">days</span>
              </div>
            )}
          </Field>

          <Field
            id="equipment-service-warning-days"
            label="Notice before a machine is due a service"
            hint="Long enough to get the engineer booked."
          >
            {(props) => (
              <div className={NUMBER_ROW}>
                <input
                  {...props}
                  className={`${props.className} ${NUMBER_BOX}`}
                  type="number"
                  inputMode="numeric"
                  min={MIN_WARNING_DAYS}
                  max={MAX_WARNING_DAYS}
                  required
                  readOnly={readOnly}
                  value={service}
                  onChange={(e) => {
                    setService(e.target.value);
                    setSaved(false);
                  }}
                />
                <span className="text-sm text-ink-secondary">days</span>
              </div>
            )}
          </Field>
        </FieldRow>
      </Form>

      <p className="mt-4 max-w-[60ch] text-sm text-ink-secondary">
        All three only put a badge on a screen. Nothing is dropped or written off.
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
        <EditActions
          editing={edit.editing}
          formId="warnings-form"
          disabled={busy}
          saving={busy}
          onEdit={() => {
            setSaved(false);
            edit.open();
          }}
          onCancel={edit.cancel}
        />
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
  return iso ? moment(iso) : "—";
}
