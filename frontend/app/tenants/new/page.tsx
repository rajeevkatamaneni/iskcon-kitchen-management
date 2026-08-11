"use client";

import { useState } from "react";
import Link from "next/link";
import { Sidebar } from "@/components/Sidebar";
import { Field } from "@/components/Field";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ApiError, api, toApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";

/**
 * Bring a temple onto the platform.
 *
 * <p>One form, not a wizard. A temple and its first administrator are created together because
 * a temple nobody can sign into is a dead record — and splitting that across steps invites
 * abandoning halfway.
 *
 * <p>Grouped into three sections so the form reads as three short questions rather than eleven
 * fields: who the temple is, where it is, and who runs it.
 */
export default function NewTenantPage() {
  return (
    <RequireRole roles={["SUPER_ADMIN"]}>
      <NewTenantForm />
    </RequireRole>
  );
}

/**
 * Turn a temple name into a valid web address: lowercase, non-alphanumerics collapsed to single
 * hyphens, no leading/trailing hyphen. "Sri Sri Radha Govinda Temple" → "sri-sri-radha-govinda-temple".
 */
function slugify(value: string): string {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60)
    .replace(/-+$/g, "");
}

/**
 * Keep only the leading "+" and the digits. A phone typed with spaces, dashes, or an invisible
 * zero-width/non-breaking character (common from autofill or a paste) is otherwise rejected by the
 * strict E.164 check even though the number itself is fine.
 */
function normalizePhone(value: string): string {
  return value.replace(/[^\d+]/g, "");
}

function NewTenantForm() {
  const { getToken } = useAuth();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [created, setCreated] = useState<string | null>(null);

  // The slug follows the name until the operator edits it by hand, so a valid web address exists
  // without them having to know what a "slug" is.
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setFieldErrors({});

    const form = new FormData(event.currentTarget);

    try {
      const token = await getToken();
      const result = await api.provisionTenant(
        {
          name: name.trim(),
          slug: slugify(slug),
          address: String(form.get("address") ?? "").trim(),
          latitude: Number(form.get("latitude")),
          longitude: Number(form.get("longitude")),
          timezone: String(form.get("timezone") ?? ""),
          currency: String(form.get("currency") ?? "INR"),
          is80gApproved: form.get("is80gApproved") === "on",
          adminName: String(form.get("adminName") ?? "").trim(),
          adminEmail: String(form.get("adminEmail") ?? "").trim(),
          adminPhone: normalizePhone(String(form.get("adminPhone") ?? "")),
        },
        token
      );

      setCreated(result.slug);
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e);
        setFieldErrors(e.byField());
      } else {
        setError(toApiError(e, "We couldn't add the temple."));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar templeName="Platform" activeHref="/tenants" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-prose">
          <header className="mb-8">
            <Link href="/tenants" className="text-sm text-ink-secondary hover:text-ink">
              Temples
            </Link>
            <h1 className="mt-2">Add a temple</h1>
            <p className="mt-1 text-ink-secondary">
              This creates the temple&rsquo;s workspace and its first administrator, who can
              sign in straight away.
            </p>
          </header>

          {created && (
            <div className="mb-6 rounded border border-success/20 bg-success-bg p-4 text-success">
              <p className="font-medium">{created} is ready.</p>
              <p className="mt-1 text-sm">
                Its administrator can sign in with the email address you entered.
              </p>
            </div>
          )}

          {error && (
            <div className="mb-6">
              <ErrorNotice error={error} />
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-8">
            <section className="space-y-5">
              <h2>The temple</h2>

              <Field id="name" label="Name" error={fieldErrors.name} required>
                {(props) => (
                  <input
                    {...props}
                    name="name"
                    type="text"
                    placeholder="Sri Sri Radha Govinda Temple"
                    value={name}
                    onChange={(e) => {
                      setName(e.target.value);
                      if (!slugEdited) setSlug(slugify(e.target.value));
                    }}
                  />
                )}
              </Field>

              <Field
                id="slug"
                label="Web address"
                hint="Suggested from the name; used in links devotees will see, like /t/radha-govinda. This cannot be changed later."
                error={fieldErrors.slug}
                required
              >
                {(props) => (
                  <input
                    {...props}
                    name="slug"
                    type="text"
                    placeholder="radha-govinda"
                    value={slug}
                    onChange={(e) => {
                      setSlugEdited(true);
                      setSlug(e.target.value);
                    }}
                  />
                )}
              </Field>

              <Field id="address" label="Address" error={fieldErrors.address}>
                {(props) => (
                  <input {...props} name="address" type="text" placeholder="Bengaluru, Karnataka" />
                )}
              </Field>
            </section>

            <section className="space-y-5">
              <h2>Where it is</h2>
              <p className="text-sm text-ink-secondary">
                The Vaishnava calendar is calculated from the temple&rsquo;s exact location, so
                Ekadashi and festival dates are correct for this temple rather than approximate.
              </p>

              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
                <Field id="latitude" label="Latitude" error={fieldErrors.latitude} required>
                  {(props) => (
                    <input {...props} name="latitude" type="number" step="any" placeholder="12.9716" />
                  )}
                </Field>

                <Field id="longitude" label="Longitude" error={fieldErrors.longitude} required>
                  {(props) => (
                    <input {...props} name="longitude" type="number" step="any" placeholder="77.5946" />
                  )}
                </Field>
              </div>

              <Field
                id="timezone"
                label="Timezone"
                hint="Used with the coordinates to work out local sunrise."
                error={fieldErrors.timezone}
                required
              >
                {(props) => (
                  <select {...props} name="timezone" defaultValue="Asia/Kolkata">
                    <option value="Asia/Kolkata">Asia/Kolkata (IST)</option>
                    <option value="Asia/Dubai">Asia/Dubai</option>
                    <option value="Europe/London">Europe/London</option>
                    <option value="America/New_York">America/New_York</option>
                  </select>
                )}
              </Field>

              <Field id="currency" label="Currency" error={fieldErrors.currency} required>
                {(props) => (
                  <select {...props} name="currency" defaultValue="INR">
                    <option value="INR">Indian rupee (INR)</option>
                    <option value="USD">US dollar (USD)</option>
                    <option value="GBP">Pound sterling (GBP)</option>
                  </select>
                )}
              </Field>

              <label className="flex items-start gap-3">
                <input
                  name="is80gApproved"
                  type="checkbox"
                  className="mt-1 h-5 w-5 rounded-sm border-hairline-strong"
                />
                <span>
                  <span className="text-sm font-medium text-ink">
                    Approved for 80G receipts
                  </span>
                  <span className="mt-0.5 block text-sm text-ink-secondary">
                    If approved, donors are offered the option to provide a PAN so the temple
                    can issue a tax certificate.
                  </span>
                </span>
              </label>
            </section>

            <section className="space-y-5">
              <h2>Who runs it</h2>
              <p className="text-sm text-ink-secondary">
                This person becomes the temple administrator. They can add everyone else.
              </p>

              <Field id="adminName" label="Full name" error={fieldErrors.adminName} required>
                {(props) => <input {...props} name="adminName" type="text" />}
              </Field>

              <Field id="adminEmail" label="Email address" error={fieldErrors.adminEmail} required>
                {(props) => (
                  <input {...props} name="adminEmail" type="email" placeholder="name@example.com" />
                )}
              </Field>

              <Field
                id="adminPhone"
                label="Phone number"
                hint="Used for sign-in and reminders. Include the country code."
                error={fieldErrors.adminPhone}
                required
              >
                {(props) => (
                  <input {...props} name="adminPhone" type="tel" placeholder="+919876543210" />
                )}
              </Field>
            </section>

            <div className="flex items-center gap-3 border-t border-hairline pt-6">
              <button
                type="submit"
                disabled={submitting}
                className="min-h-touch rounded bg-accent px-6 text-ink-inverse transition-colors duration-state hover:bg-accent-hover disabled:opacity-60"
              >
                {submitting ? "Adding temple…" : "Add temple"}
              </button>

              <Link
                href="/tenants"
                className="flex min-h-touch items-center rounded border border-hairline-strong px-5 transition-colors duration-state hover:bg-raised"
              >
                Cancel
              </Link>
            </div>
          </form>
        </div>
      </main>
    </div>
  );
}
