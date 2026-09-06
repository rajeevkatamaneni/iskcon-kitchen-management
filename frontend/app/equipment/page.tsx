"use client";

import Link from "next/link";
import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Loading } from "@/components/Loading";
import {
  CONDITION_LABEL,
  ConditionBadge,
  ServiceState,
} from "@/components/EquipmentWords";
import {
  TABLE,
  TD_TEXT,
  THEAD,
  TH_TEXT,
  TR,
  WRAP,
} from "@/components/ds/table";
import {
  api,
  type EquipmentCondition,
  type EquipmentServiceStatus,
  type EquipmentView,
} from "@/lib/api";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * The equipment register (E3-S11).
 *
 * <p>The register has been recording things since August and nobody has ever been able to look at
 * it — there was no page, no menu entry and no way in. This is the way in.
 *
 * <p><strong>Five columns and only five</strong> (D1). Name, Location, Status, Next service and
 * Service company. The other fields the register holds live on the item's own page: all of them at
 * once needs a horizontal scroll on a laptop, which is the density complaint raised against the
 * recipe list. If the five turn out to be the wrong five, that is the thing to say.
 *
 * <p><strong>Modelled on Inventory, not Ingredients</strong> (D2): a list, a detail page, and
 * registering on a screen of its own. Inline row editing was rejected — it works for Inventory's
 * three editable fields and would be absurd for twelve.
 */

const CONDITIONS: EquipmentCondition[] = ["GOOD", "NEEDS_REPAIR", "IN_REPAIR", "SCRAPPED"];

/**
 * The service states somebody would filter by, and only those two.
 *
 * <p>*Fine* and *not scheduled* are on every row already and asking for either is asking for
 * "everything except the ones I can see at a glance". These two are the ones a person opens this
 * screen to find.
 */
const SERVICE_FILTERS: { value: EquipmentServiceStatus; label: string }[] = [
  { value: "OVERDUE", label: "Overdue" },
  { value: "DUE_SOON", label: "Due soon" },
];

const SELECT = "min-h-touch rounded-control border border-hairline px-3";

export default function EquipmentPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — the Today nudge arrives here already filtered, and a newly registered
          machine comes back with its confirmation in the URL. */}
      <Suspense>
        <EquipmentList />
      </Suspense>
    </RequireRole>
  );
}

function EquipmentList() {
  const params = useSearchParams();
  const router = useRouter();

  // Scrapped machines are out of the default list, so asking for them changes what is fetched.
  const [includeScrapped, setIncludeScrapped] = useState(false);
  const fetchEquipment = useMemo(
    () => (token: string | undefined) => api.listEquipment({ includeScrapped }, token),
    [includeScrapped]
  );
  const { data, error, loading } = useAuthedQuery(fetchEquipment);
  const items = useMemo(() => data ?? [], [data]);

  // Today links here with the overdue filter already applied — sending an administrator to look
  // for them among everything else is not much of a nudge.
  const [serviceStatus, setServiceStatus] = useState<string>(
    () => params.get("serviceStatus") ?? ""
  );
  const [condition, setCondition] = useState("");
  const [location, setLocation] = useState("");

  const [flash, setFlash] = useState<string | null>(null);

  // Registering happens on /equipment/new and ends back here, so the confirmation travels in the
  // URL. Captured behind a ref because setting it re-renders, and a router object that is new on
  // each render would otherwise turn this effect into a loop — the exact fault found on Ingredients.
  const added = params.get("added");
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !added) return;
    captured.current = true;
    setFlash(added);
    router.replace("/equipment");
  }, [added, router]);

  const locations = useMemo(
    () => [...new Set(items.map((i) => i.storageLocation).filter(Boolean))].sort() as string[],
    [items]
  );

  // All three narrow together rather than replacing one another. They are applied here rather than
  // asked of the server for the reason `api.listEquipment` gives: a round trip per filter would
  // leave each dropdown offering only the values that survived the filter already set.
  const visible = items.filter(
    (i) =>
      (!condition || i.condition === condition) &&
      (!location || i.storageLocation === location) &&
      (!serviceStatus || i.serviceStatus === serviceStatus)
  );

  const overdue = items.filter((i) => i.serviceStatus === "OVERDUE").length;

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/equipment" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8 flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1>Equipment</h1>
              <p className="mt-1 text-ink-secondary">
                What the temple owns, what state it is in, and when it is next due to be looked at.
              </p>
            </div>
            <ButtonLink href="/equipment/new">Register equipment</ButtonLink>
          </header>

          {overdue > 0 && (
            <div className="mb-6">
              <button
                type="button"
                onClick={() => setServiceStatus((s) => (s === "OVERDUE" ? "" : "OVERDUE"))}
                className={`rounded-md px-4 py-2 text-sm ${
                  serviceStatus === "OVERDUE" ? "bg-danger text-ink-inverse" : "bg-danger-bg text-danger"
                }`}
              >
                {overdue === 1
                  ? "1 machine is past its service date"
                  : `${overdue} machines are past their service date`}
                {serviceStatus === "OVERDUE" ? ", showing only these" : ""}
              </button>
            </div>
          )}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={`${flash} is now on the register.`}>
                Set what state it is in and record a service against it from its own page.
              </InlineNotice>
            </div>
          )}

          {items.length > 0 && (
            <div className="mb-4 flex flex-wrap items-center gap-4">
              <label className="text-sm text-ink-secondary">
                <span className="font-medium text-ink">Condition</span>
                <select
                  value={condition}
                  onChange={(e) => setCondition(e.target.value)}
                  className={`ml-2 ${SELECT}`}
                >
                  <option value="">All</option>
                  {CONDITIONS.map((c) => (
                    <option key={c} value={c}>
                      {CONDITION_LABEL[c]}
                    </option>
                  ))}
                </select>
              </label>

              {locations.length > 0 && (
                <label className="text-sm text-ink-secondary">
                  <span className="font-medium text-ink">Location</span>
                  <select
                    value={location}
                    onChange={(e) => setLocation(e.target.value)}
                    className={`ml-2 ${SELECT}`}
                  >
                    <option value="">All</option>
                    {locations.map((l) => (
                      <option key={l} value={l}>
                        {l}
                      </option>
                    ))}
                  </select>
                </label>
              )}

              <label className="text-sm text-ink-secondary">
                <span className="font-medium text-ink">Service</span>
                <select
                  value={serviceStatus}
                  onChange={(e) => setServiceStatus(e.target.value)}
                  className={`ml-2 ${SELECT}`}
                >
                  <option value="">All</option>
                  {SERVICE_FILTERS.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </label>

              {/* Scrapped is terminal and drops out of the default view, but the register keeps it
                  for good — a scrap report is a thing a temple asks for. It is never overdue, even
                  when it is on the screen (E3-S10 D6). */}
              <label className="flex items-center gap-2 text-sm text-ink">
                <input
                  type="checkbox"
                  checked={includeScrapped}
                  onChange={(e) => setIncludeScrapped(e.target.checked)}
                  className="accent-accent"
                />
                <span>Show scrapped items</span>
              </label>
            </div>
          )}

          {loading ? (
            <Loading label="Loading the register…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : items.length === 0 ? (
            <EmptyState
              title="Nothing on the register yet"
              action={<ButtonLink href="/equipment/new">Register equipment</ButtonLink>}
            >
              Start with one machine — what it is, where it lives and how often it needs looking at.
              Everything the temple owns can go on here, down to the trestle tables.
            </EmptyState>
          ) : visible.length === 0 ? (
            <p className="card px-6 py-8 text-center text-ink-secondary">
              Nothing on the register matches those filters.
            </p>
          ) : (
            /* The table scrolls inside its own box if it has to. The page never does — that is the
               complaint this list was designed around. */
            <div className="table-wrap overflow-x-auto">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Name</th>
                    <th className={TH_TEXT}>Location</th>
                    <th className={TH_TEXT}>Status</th>
                    <th className={TH_TEXT}>Next service</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>Service company</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((i: EquipmentView) => (
                    <tr key={i.id} className={TR}>
                      <td className={`${TD_TEXT} ${WRAP}`}>
                        {/* The name and nothing beside it. The kind — machine, tool, furniture —
                            was removed on 2026-09-04: a closed vocabulary of three the temple
                            could not extend was worse than none, and "Wet Grinder 10L" already
                            says what the thing is. */}
                        <Link
                          href={`/equipment/${i.id}`}
                          className="font-medium text-accent-text hover:underline"
                        >
                          {i.name}
                        </Link>
                      </td>
                      <td className={`${TD_TEXT} text-ink-secondary`}>{i.storageLocation ?? "—"}</td>
                      <td className={TD_TEXT}>
                        <ConditionBadge condition={i.condition} />
                      </td>
                      <td className={TD_TEXT}>
                        <ServiceState item={i} />
                      </td>
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                        {i.serviceCompany ?? "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
