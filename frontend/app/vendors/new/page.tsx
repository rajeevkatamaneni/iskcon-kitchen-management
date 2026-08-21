"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api, toApiError, type ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { ALL_LANGUAGES } from "@/lib/languages";

/**
 * Add a vendor — eight fields, so a screen rather than a panel over the list.
 *
 * <p>The threshold settled in Q1 of the 2026-08-21 brief is four: a form of four fields or more
 * gets its own URL, and three or fewer stays where it is. Eight is well over it, and the panel this
 * replaces sat on top of the very list somebody was checking the vendor was not already in.
 *
 * <p>The commit button is in the header and the form is in the body, which is why the button carries
 * `form`: an HTML button can submit a form it is not inside, as long as it names it. That is what
 * lets rule 6 hold — one place to commit, and it is not at the foot of a long form.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "add-vendor";
const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

export default function NewVendorPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <NewVendorView />
    </RequireRole>
  );
}

function NewVendorView() {
  const { getToken } = useAuth();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const f = new FormData(event.currentTarget);
    const name = String(f.get("name") ?? "").trim();
    setBusy(true);
    setError(null);
    try {
      await api.createVendor(
        {
          name,
          phone: String(f.get("phone") ?? "").trim(),
          contactPerson: emptyToNull(String(f.get("contactPerson") ?? "")),
          email: emptyToNull(String(f.get("email") ?? "")),
          address: emptyToNull(String(f.get("address") ?? "")),
          gstin: emptyToNull(String(f.get("gstin") ?? "")),
          preferredLanguage: String(f.get("preferredLanguage") ?? "en"),
          notes: emptyToNull(String(f.get("notes") ?? "")),
        },
        await getToken()
      );
      // Rule 8: back to the list, with the confirmation waiting there rather than here.
      router.push(`/vendors?added=${encodeURIComponent(name)}`);
    } catch (e) {
      setError(toApiError(e, "We couldn't add that vendor."));
      setBusy(false);
    }
  }

  return (
    <FocusScreen
      task="Add a vendor"
      who="New supplier for this temple"
      activeHref="/vendors"
      actions={
        <>
          <ButtonLink href="/vendors" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={busy}>
            Add vendor
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      <form id={FORM} className="grid grid-cols-2 gap-4" aria-label="Add a vendor" onSubmit={add}>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Name</span>
          <input name="name" required className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Phone</span>
          <input name="phone" required placeholder="+919876543210" className={FIELD} />
          <span className="pl-field-inset text-sm text-ink-secondary">With the country code</span>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Contact person</span>
          <input name="contactPerson" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Email</span>
          <input name="email" type="email" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">GSTIN</span>
          <input name="gstin" className={FIELD} />
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Preferred language</span>
          <select name="preferredLanguage" defaultValue="en" className={FIELD}>
            {ALL_LANGUAGES.map((l) => (
              <option key={l.code} value={l.code}>
                {l.label}
              </option>
            ))}
          </select>
          <span className="pl-field-inset text-sm text-ink-secondary">Purchase orders go out in this</span>
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Address</span>
          <input name="address" className={FIELD} />
        </label>
        <label className="col-span-2 flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes</span>
          <input name="notes" className={FIELD} />
        </label>
      </form>
    </FocusScreen>
  );
}

function emptyToNull(s: string): string | null {
  const t = s.trim();
  return t === "" ? null : t;
}
