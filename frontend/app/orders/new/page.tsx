"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Loading } from "@/components/Loading";
import { RequireRole } from "@/components/RequireRole";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { FocusScreen } from "@/components/ds/FocusScreen";
import { api } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * Raising a purchase order by hand — step one, and it asks one question (T-026, D-7).
 *
 * <p>`POST /api/v1/purchase-orders` has existed since E5-S3 and had no caller: an order could only
 * be generated from the shopping list, which is no help at all for the thing this exists for — a
 * one-off buy from a shop the regenerator has never suggested.
 *
 * <p><b>Why the vendor is asked on a screen of its own, before anything else.</b> A vendor that is
 * not in the list yet has to be added, and adding one is a screen at `/vendors/new`. Asked here,
 * leaving for it costs nothing: no line has been entered, so there is no draft to lose and nothing
 * to restore on the way back. The alternative — one screen with the picker inline — would have
 * needed two mechanisms this application has never had, a `returnTo` parameter and a form draft in
 * `sessionStorage`, and restoring form state in an effect is the exact shape that has already bitten
 * this codebase once, in the ref-guarded flash banner. D-7 ruled it.
 *
 * <p>It is also true of the domain rather than merely convenient. The vendor is not a field of a
 * purchase order, it is the order's identity: `orders/[id]` refuses to move an order to a different
 * vendor after the fact and tells you to cancel it and raise another. A question that can never be
 * answered again belongs first.
 *
 * <p>Nothing here is new. `FocusScreen`, a native `<select>` over a related entity, and a
 * `ButtonLink` beside it are all in use on other screens — `/invoices/new` picks a vendor this same
 * way — which is what D-3 asked for.
 */

/** Named so the header's primary button can submit the form in the body. */
const FORM = "choose-po-vendor";
const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * The vendors this order may be raised against: the active ones, and only those.
 *
 * <p><b>`true` means active-only, and the flag really is the other way round.</b> The endpoint asks
 * `includeInactive` and `listVendors` inverts it — `listVendors(false)`, the default, asks for the
 * inactive ones as well. `api.ts`'s own comment records the day that inversion was a bug.
 *
 * <p>The narrowing is this screen's job because the server will not do it: `requireVendor` checks
 * only that the vendor row exists, so an order against a supplier somebody deliberately dropped —
 * with a reason, kept as history — would be accepted without complaint. The picker not offering one
 * is the whole of the guard.
 *
 * <p>Declared at module scope so it is the same function object on every render. `useAuthedQuery`
 * lists its fetcher in the effect's dependencies, so an arrow written inline re-fetches for ever.
 */
const activeVendors = (token: string | undefined) => api.listVendors(true, token);

export default function NewPurchaseOrderPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <ChooseVendorView />
    </RequireRole>
  );
}

function ChooseVendorView() {
  const router = useRouter();
  const { data, error, loading } = useAuthedQuery(activeVendors);
  const vendors = data ?? [];
  const [vendorId, setVendorId] = useState("");

  // The vendor travels in the URL rather than in state, so step two is linkable and survives a
  // reload — the same reason every one of these screens has an address of its own.
  function choose(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (vendorId === "") return;
    router.push(`/orders/new/lines?vendor=${encodeURIComponent(vendorId)}`);
  }

  return (
    <FocusScreen
      task="Raise a purchase order"
      who="The vendor first. It cannot be changed once the order is raised."
      activeHref="/orders"
      actions={
        <>
          <ButtonLink href="/orders" variant="secondary">
            Cancel
          </ButtonLink>
          <Button type="submit" form={FORM} disabled={loading || vendorId === ""}>
            Continue
          </Button>
        </>
      }
    >
      {error && <ErrorNotice error={error} />}

      {loading ? (
        <Loading label="Loading vendors…" />
      ) : (
        <>
          <form
            id={FORM}
            aria-label="Choose a vendor"
            onSubmit={choose}
            className="flex flex-wrap items-end gap-3"
          >
            <label className="flex flex-col gap-1 text-sm text-ink-secondary">
              <span className="pl-field-inset font-medium text-ink">Vendor</span>
              <select
                name="vendorId"
                required
                value={vendorId}
                onChange={(e) => setVendorId(e.target.value)}
                className={FIELD}
              >
                <option value="">Choose a vendor…</option>
                {vendors.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.name}
                  </option>
                ))}
              </select>
            </label>
            {/* Secondary, beside the field it belongs to. The screen's one primary is Continue, in
                the header, and two terracotta buttons would be two answers to what to press. */}
            <ButtonLink href="/vendors/new" variant="secondary">
              Add a vendor
            </ButtonLink>
          </form>

          {vendors.length === 0 && !error && (
            <p className="max-w-prose text-ink-secondary">
              No vendor is active yet. Add the shop or supplier this order goes to, then come back
              and raise it.
            </p>
          )}
        </>
      )}
    </FocusScreen>
  );
}
