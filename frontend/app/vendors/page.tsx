"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Badge } from "@/components/ds/Badge";
import { VendorStatusDialog } from "@/components/VendorStatusDialog";
import { api, type VendorView } from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { contractWarning, dateWithYear } from "@/lib/format";
import { languageLabel } from "@/lib/languages";
import { Loading } from "@/components/Loading";
import { TABLE, THEAD, TR, TH_TEXT, TH_ACTIONS, TD_TEXT, TD_DATE, TD_ACTIONS, WRAP } from "@/components/ds/table";
import { Button } from "@/components/ds/Button";

export default function VendorsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — for the confirmation a new vendor comes back with — needs a boundary. */}
      <Suspense>
        <VendorsView />
      </Suspense>
    </RequireRole>
  );
}

function VendorsView() {
  const [activeOnly, setActiveOnly] = useState(false);
  const fetchVendors = useCallback(
    (token: string | undefined) => api.listVendors(activeOnly, token),
    [activeOnly]
  );
  const { data, error, loading, reload } = useAuthedQuery(fetchVendors);
  const vendors = data ?? [];

  // Changing whether a vendor is active is never a bare click: it asks for a reason first, and the
  // reason is what somebody reads months later when they wonder whether this supplier can be used
  // again. The dialog owns the call and its own failure, so nothing is left here to hold.
  const [changing, setChanging] = useState<VendorView | null>(null);

  // Adding a vendor happens on /vendors/new and ends back here, so the confirmation has to travel
  // in the URL. Captured behind a ref because setting it re-renders, and a router object that is
  // new on each render would otherwise turn this effect into a loop.
  const router = useRouter();
  const added = useSearchParams().get("added");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/vendors");
  }, [added, router]);

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/vendors" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Vendors</h1>
              <p className="mt-1 text-ink-secondary">
                The WhatsApp number a purchase order goes to.
              </p>
            </div>
            <ButtonLink href="/vendors/new">Add a vendor</ButtonLink>
          </header>

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} was added.`}>
                Set which ingredients they supply so the shopping list can suggest them.
              </InlineNotice>
            </div>
          )}

          <div className="mb-4">
            <label className="text-sm text-ink-secondary">
              <input type="checkbox" checked={activeOnly} onChange={(e) => setActiveOnly(e.target.checked)} className="mr-2 align-middle" />
              Active vendors only
            </label>
          </div>

          {loading ? (
            <Loading label="Loading vendors…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : vendors.length === 0 ? (
            <div className="rounded-lg bg-raised px-6 py-14 text-center">
              <p className="text-lg">No vendors yet</p>
              <p className="mx-auto mt-2 max-w-prose text-ink-secondary">
                Add a vendor, then set which ingredients they supply.
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg bg-raised">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Vendor</th>
                    <th className={TH_TEXT}>Phone</th>
                    <th className={TH_TEXT}>Language</th>
                    <th className={TH_TEXT}>Contract ends</th>
                    <th className={TH_TEXT}>Status</th>
                    <th className={TH_ACTIONS}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {vendors.map((v) => (
                    <tr key={v.id} className={TR}>
                      <td className={`${TD_TEXT} ${WRAP}`}>
                        <Link href={`/vendors/${v.id}`} className="font-medium text-accent-text hover:underline">
                          {v.name}
                        </Link>
                        {v.contactPerson && <span className="ml-2 text-xs text-ink-muted">{v.contactPerson}</span>}
                      </td>
                      <td className={`${TD_TEXT} text-ink-secondary tabular-nums`}>{v.phone}</td>
                      <td className={`${TD_TEXT} text-ink-secondary`}>{languageLabel(v.preferredLanguage)}</td>
                      {/* Recorded and warned about, and that is all. Nothing on this screen or behind
                          it filters, sorts or deactivates on this date. */}
                      <td className={`${TD_DATE} text-ink-secondary`}>
                        {v.contractEndDate ? dateWithYear(v.contractEndDate) : "—"}
                      </td>
                      <td className={TD_TEXT}>
                        <div className="flex items-center gap-1.5">
                          {v.active ? (
                            <span className="rounded-sm bg-success-bg px-2 py-1 text-xs text-success font-semibold">Active</span>
                          ) : (
                            <span className="rounded-sm bg-sunken px-2 py-1 text-xs text-ink-muted font-semibold">Inactive</span>
                          )}
                          {!v.whatsappReachable && (
                            <span className="rounded-sm bg-warning-bg px-2 py-1 text-xs text-warning font-semibold">Recheck WhatsApp</span>
                          )}
                          {v.contractEndingSoon && v.contractEndDate && (
                            <Badge tone="warning" shape="square">{contractWarning(v.contractEndDate)}</Badge>
                          )}
                        </div>
                      </td>
                      <td className={TD_ACTIONS}>
                        <Button variant="ghost" size="sm" onClick={() => setChanging(v)}>
                          {v.active ? "Make inactive" : "Bring back"}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>

      {changing && (
        <VendorStatusDialog
          vendor={changing}
          onCancel={() => setChanging(null)}
          onDone={() => {
            setChanging(null);
            reload();
          }}
        />
      )}
    </div>
  );
}
