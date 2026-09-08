"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { Field } from "@/components/Field";
import { InfoHint } from "@/components/ds/InfoHint";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { CookingLoader } from "@/components/CookingLoader";
import { Loading } from "@/components/Loading";
import { ApiError, api, toApiError, type TenantDetail } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Correcting a temple after it has been brought onto the platform (T-008, narrowed by D-17).
 *
 * <p>Everything on this screen was write-once until the `PATCH` behind it existed. A temple whose
 * name was misspelled could be fixed only by deleting it and provisioning it again — and 80G
 * approval, which arrives from the Income Tax department months after a temple starts using the
 * product, could not be recorded at any point after provisioning, so that temple's receipts stayed
 * wrong for good.
 *
 * <p><strong>Why this is operator territory and not the temple's own settings.</strong> D-13, ruled
 * 2026-09-07, against a recommendation to split the fields so a temple admin could correct its own
 * address. So the screen lives beside the provisioning flow, at `/tenants/[id]/edit`, behind the
 * same `SUPER_ADMIN` guard as every other screen under `/tenants`, and nothing like it appears
 * under `/settings`. The cost is stated plainly because it is real: a temple cannot fix its own
 * street name, and every correction is an operator ticket. What it buys is one auditable answer to
 * "who may change what a temple is", with 80G — a legal status no temple should be able to assert
 * about itself — on the operator's side without a field-by-field permission boundary.
 *
 * <p><strong>Three fields, not seven (D-17, ruled 2026-09-07).</strong> The screen shipped offering
 * every provisioning field, because that was the shape of the gap it was filling. Rajeev narrowed
 * it field by field to the ones that actually change over a temple's life: its **name**, because
 * temples are renamed; its **address**, because streets are renamed and pincodes are wrong, neither
 * of which moves the building; and its **80G approval**, which is the defect this screen was built
 * for. His argument was that we onboard a temple a few times a year at best, and that the reason to
 * edit a coordinate would be the temple physically moving — "a HUGE establishment which took a
 * great deal of time, money and effort to build". That never happens.
 *
 * <p>So latitude, longitude, timezone and currency are **shown and not offered**. Shown, because an
 * operator opening this screen should be able to read what the temple is; not offered, because
 * there is nothing here anyone should change. The server refuses a change to any of them, so a
 * control here would be a control that only ever produces a refusal — which is the same reasoning
 * that has kept the web address off this screen since it shipped.
 *
 * <p><strong>A provisioning typo in the coordinates therefore cannot be corrected here.</strong>
 * D-17 accepts that with its eyes open: an error big enough to change the calendar — wrong city,
 * transposed digits, wrong hemisphere — is big enough to be obvious immediately, and one small
 * enough to go unnoticed moves sunrise by seconds and no tithi at all. A typo caught during
 * onboarding costs nothing, because the temple has no data yet: delete it and create it again.
 *
 * <p>What is missing relative to `/tenants/new` is the whole administrator section — those three
 * fields describe a person, and a person is corrected on their own record.
 */

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
          // The three the operator may change come off the form; the four D-17 froze come off the
          // temple this screen loaded. All seven go, because the endpoint is a whole-record
          // replacement and a field it does not receive is a caller bug, not "leave it alone".
          //
          // Read from `temple` rather than carried in hidden inputs, which was the other way to do
          // it. A hidden input would put the values back inside the form only to have this handler
          // take them out again through FormData — a longer path to the same payload, with two
          // extra failure modes worth avoiding: a hidden input is a string, so `latitude` would go
          // back through Number() and a lost value would arrive as 0 rather than as an error, and
          // a hidden field is editable by anyone with a devtools panel open, which is exactly the
          // suggestion this screen has just stopped making. `temple` is what the server sent for
          // this record, so what goes back is what came, and the server refuses it otherwise.
          name: String(form.get("name") ?? "").trim(),
          address: String(form.get("address") ?? "").trim(),
          latitude: temple.latitude,
          longitude: temple.longitude,
          timezone: temple.timezone,
          currency: temple.currency,
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
        {/* The whole of what may change about a temple: a name, an address, and a legal status that
            arrives long after the temple was created. Everything else was settled when it was
            created and is read in the section below rather than offered here. */}
        <section className="space-y-5">
          <h2>What can be changed</h2>

          <Field id="name" label="Name" error={fieldErrors.name} required>
            {(props) => <input {...props} name="name" type="text" defaultValue={temple.name} />}
          </Field>

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

        {/*
          Shown, not offered (D-17). These five are exactly what the server refuses a change to, so
          a control for any of them would be a control whose only possible outcome is a refusal —
          which is the reasoning the web address has been treated with since this screen shipped,
          now applied to the four fields that joined it. They stay on the page rather than
          disappearing from it because an operator who came to correct a name is entitled to read
          what else the record says about this temple.

          A <dl>, laid out as the temple's own page lays out its details, rather than fields with
          their inputs greyed out. Two reasons, and the second is not cosmetic: a disabled input
          submits nothing, so had these stayed as controls the four values would have left the form
          as empty strings and the save would have failed for a reason invisible on the screen.
        */}
        <section className="space-y-4">
          <h2>Fixed when the temple was created</h2>
          <p className="text-sm text-ink-secondary">
            These can’t be changed. A temple doesn’t move, so where it is and the timezone its
            calendar is worked out from stay as they were set. Its currency is set once, because
            changing it later would show donations and payments in a currency they were never in.
            Its web address is already in links and filenames people have saved.
          </p>

          <dl className="grid grid-cols-1 gap-x-10 gap-y-4 sm:grid-cols-2">
            <Fixed label="Web address" value={`/t/${temple.slug}`} mono />
            <Fixed label="Latitude" value={String(temple.latitude)} />
            <Fixed label="Longitude" value={String(temple.longitude)} />
            <Fixed label="Timezone" value={temple.timezone} />
            <Fixed label="Currency" value={temple.currency} />
          </dl>
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

/**
 * One thing about the temple that is displayed and not offered.
 *
 * <p>Deliberately not a `Field` with its control swapped for text: a label sitting over a value
 * where every other label on the page sits over a box invites the reading that this one is a box
 * that has stopped working. The temple's own page renders its details exactly this way, so an
 * operator arriving from it meets the same presentation twice, and the difference between "you may
 * change this" and "this is what it says" is carried by the layout rather than by a disabled
 * attribute nobody can see the reason for.
 */
function Fixed({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="text-sm text-ink-secondary">{label}</dt>
      <dd className={`mt-1 ${mono ? "font-mono" : ""}`}>{value}</dd>
    </div>
  );
}
