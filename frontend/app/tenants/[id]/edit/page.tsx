"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { Field, FIELD_HINT } from "@/components/Field";
import { InfoHint } from "@/components/ds/InfoHint";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { CookingLoader } from "@/components/CookingLoader";
import { Loading } from "@/components/Loading";
import { ApiError, api, toApiError, type TenantDetail } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Correcting a temple after it has been brought onto the platform (T-008, docket A1 + A2).
 *
 * <p>Everything on this screen was write-once until the `PATCH` behind it existed. A temple whose
 * name was misspelled, whose coordinates were transposed or whose timezone was picked wrongly could
 * be fixed only by deleting the temple and provisioning it again — and 80G approval, which arrives
 * from the Income Tax department months after a temple starts using the product, could not be
 * recorded at any point after provisioning, so that temple's receipts stayed wrong for good.
 *
 * <p><strong>Why this is operator territory and not the temple's own settings.</strong> D-13, ruled
 * 2026-09-07, against a recommendation to split the fields so a temple admin could correct its own
 * address. So the screen lives beside the provisioning flow, at `/tenants/[id]/edit`, behind the
 * same `SUPER_ADMIN` guard as every other screen under `/tenants`, and nothing like it appears
 * under `/settings`. The cost is stated plainly because it is real: a temple cannot fix its own
 * street name, and every correction is an operator ticket. What it buys is one auditable answer to
 * "who may change what a temple is", with the two dangerous fields — timezone, which rewrites the
 * temple's whole calendar, and 80G, which is a legal status — on the operator's side without a
 * field-by-field permission boundary.
 *
 * <p>Laid out as `/tenants/new` is, in the same two sections and the same field order, because it
 * is the same information: somebody correcting a coordinate they typed wrongly ten minutes ago
 * should find it where they typed it. What is missing relative to that form is the whole third
 * section — the administrator — because those three fields describe a person, and the person is
 * corrected on their own record.
 */

/**
 * The zones a temple is offered, matching `/tenants/new`'s list exactly.
 *
 * <p>A stored zone outside this list is added to it rather than dropped — see `optionsFor`. A
 * `<select>` whose value is not among its options silently falls back to the first one, which here
 * would mean an operator correcting a spelling and unknowingly moving the temple to Kolkata, and
 * the calendar rebuilding itself around it.
 *
 * <p>Naming `Asia/Kolkata` here is what `design-system.test.ts`'s "nobody hard-codes a time zone"
 * rule catches, and this file is exempted from it by name. The exemption is narrow and the reason
 * is not the provisioning form's: that screen genuinely defaults a new temple to this zone, whereas
 * this one defaults to nothing at all — the select opens on `temple.timezone`, and these are the
 * alternatives offered beside it. **This list and `/tenants/new`'s are two copies that must agree
 * and nothing makes them.** Consolidating them is worth doing and was out of scope here.
 */
const TIMEZONES: [string, string][] = [
  ["Asia/Kolkata", "Asia/Kolkata (IST)"],
  ["Asia/Dubai", "Asia/Dubai"],
  ["Europe/London", "Europe/London"],
  ["America/New_York", "America/New_York"],
];

const CURRENCIES: [string, string][] = [
  ["INR", "Indian rupee (INR)"],
  ["USD", "US dollar (USD)"],
  ["GBP", "Pound sterling (GBP)"],
];

/** The offered list, with whatever the temple actually has kept in it. */
function optionsFor(offered: [string, string][], current: string): [string, string][] {
  return offered.some(([value]) => value === current)
    ? offered
    : [...offered, [current, current] as [string, string]];
}

export default function EditTenantPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <EditTenantView />
    </RequireRole>
  );
}

function EditTenantView() {
  const params = useParams<{ id: string }>();
  const id = params.id;

  // useCallback, not an inline arrow: useAuthedQuery's effect depends on the fetcher's identity, so
  // a new closure each render re-fetches in a loop.
  const fetcher = useCallback((token: string | undefined) => api.getTenant(id, token), [id]);
  const { data: temple, error, loading } = useAuthedQuery(fetcher);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/tenants" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-prose">
          {loading ? (
            <Loading label="Loading temple…" />
          ) : error || !temple ? (
            <ErrorNotice error={error ?? toApiError(null, "We couldn’t load this temple.")} />
          ) : (
            <EditTenantForm id={id} temple={temple} />
          )}
        </div>
      </main>
    </div>
  );
}

function EditTenantForm({ id, temple }: { id: string; temple: TenantDetail }) {
  const { getToken } = useAuth();
  const router = useRouter();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setFieldErrors({});

    const form = new FormData(event.currentTarget);

    try {
      await api.updateTenant(
        id,
        {
          name: String(form.get("name") ?? "").trim(),
          address: String(form.get("address") ?? "").trim(),
          latitude: Number(form.get("latitude")),
          longitude: Number(form.get("longitude")),
          timezone: String(form.get("timezone") ?? ""),
          currency: String(form.get("currency") ?? ""),
          is80gApproved: form.get("is80gApproved") === "on",
        },
        await getToken()
      );

      // Back to the temple's own page, which is where the correction can be read back. No banner
      // waiting there and none here: the page shows the corrected name in its heading and the new
      // 80G status in its details, which says more than a sentence claiming the save worked.
      // Leave `saving` true so the loader holds through the navigation rather than flashing the
      // form back for a frame.
      router.push(`/tenants/${id}`);
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e);
        setFieldErrors(e.byField());
      } else {
        setError(toApiError(e, "We couldn’t save those changes."));
      }
      setSaving(false);
    }
  }

  const timezones = optionsFor(TIMEZONES, temple.timezone);
  const currencies = optionsFor(CURRENCIES, temple.currency);

  return (
    <>
      {saving && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
          role="status"
          aria-live="polite"
        >
          <div className="card flex flex-col items-center gap-4 px-10 py-8 text-center">
            <CookingLoader className="h-14 w-14 text-accent" />
            <p className="font-medium">Saving {temple.name}…</p>
          </div>
        </div>
      )}

      <header className="mb-8">
        <Link href={`/tenants/${id}`} className="text-sm text-ink-secondary hover:text-ink">
          {temple.name}
        </Link>
        <h1 className="mt-2">Edit this temple</h1>
        <p className="mt-1 text-ink-secondary">
          Corrections to what the temple is. Its people are managed inside the temple itself.
        </p>
      </header>

      {error && (
        <div className="mb-6">
          <ErrorNotice error={error} />
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-8">
        <section className="space-y-5">
          <h2>The temple</h2>

          <Field id="name" label="Name" error={fieldErrors.name} required>
            {(props) => <input {...props} name="name" type="text" defaultValue={temple.name} />}
          </Field>

          {/* Shown, not offered. The web address is fixed at creation — it is in URLs, in export
              filenames and in whatever the operator has written down — and the server refuses a
              slug outright rather than dropping it. Somebody who came here to change it should
              read why on the screen instead of discovering it as a refusal after typing. */}
          <p className="text-sm text-ink-muted">
            Web address: <span className="font-mono">/t/{temple.slug}</span> — fixed when the temple
            was created and can’t be changed.
          </p>

          <Field id="address" label="Address" error={fieldErrors.address}>
            {(props) => (
              <input
                {...props}
                name="address"
                type="text"
                defaultValue={temple.address ?? ""}
                placeholder="Bengaluru, Karnataka"
              />
            )}
          </Field>
        </section>

        <section className="space-y-5">
          <h2>Where it’s located</h2>
          <p className="text-sm text-ink-secondary">
            The Vaishnava calendar is worked out from the exact location.
          </p>

          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
            <Field id="latitude" label="Latitude" error={fieldErrors.latitude} required>
              {(props) => (
                <input
                  {...props}
                  name="latitude"
                  type="number"
                  step="any"
                  defaultValue={temple.latitude}
                />
              )}
            </Field>

            <Field id="longitude" label="Longitude" error={fieldErrors.longitude} required>
              {(props) => (
                <input
                  {...props}
                  name="longitude"
                  type="number"
                  step="any"
                  defaultValue={temple.longitude}
                />
              )}
            </Field>
          </div>

          {/*
            The consequence is visible text under the box, not the label's "i", and that is the
            rule Field states rather than a preference: guidance goes in the hint, and "anything a
            person must not miss — a warning" stays as visible text the caller lays out itself.
            This is a warning. Changing a temple's timezone rewrites `calendar_days` for it, which
            is every tithi, Ekadashi and sunrise the temple plans by, and an operator who discovers
            that afterwards has already done it.
          */}
          <div>
            <Field id="timezone" label="Timezone" error={fieldErrors.timezone} required>
              {(props) => (
                <select {...props} name="timezone" defaultValue={temple.timezone}>
                  {timezones.map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <p className={`mt-1.5 ${FIELD_HINT}`}>
              Changing this rebuilds the temple’s calendar — its tithi, Ekadashi dates and sunrise
              times are all worked out from it.
            </p>
          </div>

          <Field id="currency" label="Currency" error={fieldErrors.currency} required>
            {(props) => (
              <select {...props} name="currency" defaultValue={temple.currency}>
                {currencies.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            )}
          </Field>

          {/* The "i" sits beside the <label>, never inside it, for the reason Field spells out on
              /tenants/new: a <label>'s control is its first labelable descendant, so a button
              within this one would take the tick's own name. */}
          <span className="flex items-center gap-1.5">
            <label className="flex items-center gap-3">
              <input
                name="is80gApproved"
                type="checkbox"
                defaultChecked={temple.is_80g_approved}
                className="h-5 w-5 rounded-sm border-hairline-strong accent-accent"
              />
              <span className="text-sm font-medium text-ink">Approved for 80G receipts</span>
            </label>
            <InfoHint
              text="Tick this once the temple's 80G approval comes through. Donors are then offered a PAN for a tax certificate."
              label="Approved for 80G receipts"
            />
          </span>
        </section>

        <div className="flex items-center gap-3 border-t border-hairline pt-6">
          <button
            type="submit"
            disabled={saving}
            className="btn btn-primary min-h-touch px-6 transition-colors duration-state disabled:opacity-60"
          >
            {saving ? "Saving…" : "Save changes"}
          </button>

          <Link
            href={`/tenants/${id}`}
            className="flex min-h-touch items-center rounded border border-hairline-strong px-5 transition-colors duration-state hover:bg-raised"
          >
            Cancel
          </Link>
        </div>
      </form>
    </>
  );
}
