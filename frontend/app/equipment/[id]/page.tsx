"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useState, type ReactNode } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Button } from "@/components/ds/Button";
import { ServiceCompanyPicker } from "@/components/EquipmentForm";
import {
  CATEGORY_LABEL,
  CONDITION_LABEL,
  ConditionBadge,
  INTERVAL_UNITS,
  SOURCE_LABEL,
  ServiceState,
  intervalUnitLabel,
  intervalWords,
} from "@/components/EquipmentWords";
import { TABLE, TD_TEXT, THEAD, TH_TEXT, TR, WRAP } from "@/components/ds/table";
import {
  api,
  toApiError,
  type ApiError,
  type EquipmentCondition,
  type EquipmentView,
  type ServiceIntervalUnit,
  type ServiceProviderView,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { dateWithYear, moment, money, todayIso } from "@/lib/format";

/**
 * One piece of equipment: the whole record, and its two histories (E3-S11).
 *
 * <p><strong>Two histories, not one merged trail.</strong> A service is a different event from a
 * change of condition, and a grinder can be serviced every six months for five years without its
 * condition ever moving off good. The audit trail answers "what state has this been in"; the
 * service trail answers "when did somebody last look at it". Running them together would produce a
 * list that answers neither.
 *
 * <p>Recording a service reloads the record rather than patching the row in place, so *Next
 * service* moves the moment the service lands — the whole point of a derived date (E3-S10 D4) is
 * that nothing anywhere has to be kept in step with it by hand.
 */

const FIELD = "min-h-touch rounded border border-hairline bg-canvas px-3";

const CONDITIONS: EquipmentCondition[] = ["GOOD", "NEEDS_REPAIR", "IN_REPAIR", "SCRAPPED"];

export default function EquipmentItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <EquipmentItemView />
    </RequireRole>
  );
}

function EquipmentItemView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { appUser, getToken } = useAuth();

  // Recording a service, setting an interval and reading the temple's list of who fixes things are
  // all one permission, and it is the temple admin's alone (E3-S10 D10). Kitchen staff read this
  // page — they are the ones standing in front of the grinder — and are offered none of the three.
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";

  const fetchItem = useCallback((token: string | undefined) => api.getEquipment(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchItem);

  const [providerNonce, setProviderNonce] = useState(0);
  const fetchProviders = useCallback(
    (token: string | undefined) => {
      void providerNonce;
      return isAdmin ? api.listServiceProviders(token) : Promise.resolve([]);
    },
    [isAdmin, providerNonce]
  );
  const { data: providerData } = useAuthedQuery(fetchProviders);
  const providers = (providerData ?? []) as ServiceProviderView[];

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [open, setOpen] = useState<"service" | "condition" | "schedule" | null>(null);

  const item = data?.equipment;

  async function run(mutation: (token: string | undefined) => Promise<unknown>, failure: string) {
    setBusy(true);
    setActionError(null);
    try {
      await mutation(await getToken());
      reload();
      setOpen(null);
      return true;
    } catch (e) {
      setActionError(toApiError(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function addProvider(input: { name: string; phone: string | null }) {
    setActionError(null);
    try {
      const created = await api.createServiceProvider(input, await getToken());
      setProviderNonce((n) => n + 1);
      return created.id;
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t add that company."));
      return null;
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/equipment" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <Link href="/equipment" className="text-sm text-accent-text hover:underline">
            ← Equipment
          </Link>

          {loading ? (
            <Loading />
          ) : error ? (
            <div className="mt-6">
              <ErrorNotice error={error} />
            </div>
          ) : item && data ? (
            <>
              <header className="mb-6 mt-3 flex flex-wrap items-start justify-between gap-4">
                <div>
                  <h1>{item.name}</h1>
                  <p className="mt-1 text-ink-secondary">
                    {CATEGORY_LABEL[item.category]}
                    {item.storageLocation ? ` · ${item.storageLocation}` : ""}
                  </p>
                </div>
                <div className="flex flex-wrap gap-3">
                  <Button variant="secondary" onClick={() => setOpen("condition")}>
                    Change condition
                  </Button>
                  {isAdmin && (
                    <>
                      <Button variant="ghost" onClick={() => setOpen("schedule")}>
                        Change the schedule
                      </Button>
                      <Button onClick={() => setOpen("service")}>Record a service</Button>
                    </>
                  )}
                </div>
              </header>

              {actionError && (
                <div className="mb-6">
                  <ErrorNotice error={actionError} />
                </div>
              )}

              {open === "condition" && (
                <ChangeConditionForm
                  current={item.condition}
                  busy={busy}
                  onCancel={() => setOpen(null)}
                  onSubmit={(input) =>
                    run(
                      (t) => api.changeEquipmentCondition(id, input, t),
                      "We couldn’t record that change."
                    )
                  }
                />
              )}

              {open === "service" && isAdmin && (
                <RecordServiceForm
                  providers={providers}
                  defaultProviderId={item.serviceProviderId}
                  busy={busy}
                  onCancel={() => setOpen(null)}
                  onAddProvider={addProvider}
                  onSubmit={(input) =>
                    run(
                      (t) => api.recordEquipmentService(id, input, t),
                      "We couldn’t record that service."
                    )
                  }
                />
              )}

              {open === "schedule" && isAdmin && (
                <ScheduleForm
                  item={item}
                  providers={providers}
                  busy={busy}
                  onCancel={() => setOpen(null)}
                  onAddProvider={addProvider}
                  onSubmit={(input) =>
                    run(
                      (t) => api.setEquipmentServiceSchedule(id, input, t),
                      "We couldn’t save that schedule."
                    )
                  }
                />
              )}

              <Record item={item} providers={providers} />

              <section className="mb-8">
                {/* Newest first, because the question a person opens this with is "when was it last
                    looked at" and the answer is the top row. */}
                <h2 className="mb-3 text-lg">
                  Service history{" "}
                  <span className="text-sm font-normal text-ink-secondary">
                    — every visit, newest first
                  </span>
                </h2>
                {data.services.length === 0 ? (
                  <p className="rounded-lg bg-raised px-6 py-8 text-center text-ink-secondary">
                    Nobody has recorded a service against this yet.
                  </p>
                ) : (
                  <div className="overflow-x-auto rounded-lg bg-raised">
                    <table className={`${TABLE} text-sm`}>
                      <thead className={THEAD}>
                        <tr>
                          <th className={TH_TEXT}>Serviced on</th>
                          <th className={`${TH_TEXT} ${WRAP}`}>Company</th>
                          <th className={`${TH_TEXT} ${WRAP}`}>What was done</th>
                          <th className={TH_TEXT}>Cost</th>
                          <th className={TH_TEXT}>Recorded by</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.services.map((s) => (
                          <tr key={s.id} className={TR}>
                            <td className={TD_TEXT}>{dateWithYear(s.servicedOn)}</td>
                            <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                              {s.serviceProviderName ?? "—"}
                            </td>
                            <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                              {s.workDone ?? "—"}
                            </td>
                            <td className={TD_TEXT}>
                              {s.costInr == null ? "—" : money(s.costInr, "INR")}
                            </td>
                            <td className={`${TD_TEXT} text-ink-secondary`}>
                              {s.actorName ?? "—"}
                              <span className="block text-xs text-ink-muted">
                                {moment(s.createdAt)}
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>

              <section>
                <h2 className="mb-3 text-lg">
                  Audit trail{" "}
                  <span className="text-sm font-normal text-ink-secondary">
                    — every change of state, and why
                  </span>
                </h2>
                {data.history.length === 0 ? (
                  <p className="rounded-lg bg-raised px-6 py-8 text-center text-ink-secondary">
                    Its condition has not moved since it was registered.
                  </p>
                ) : (
                  <div className="overflow-x-auto rounded-lg bg-raised">
                    <table className={`${TABLE} text-sm`}>
                      <thead className={THEAD}>
                        <tr>
                          <th className={TH_TEXT}>When</th>
                          <th className={TH_TEXT}>Change</th>
                          <th className={`${TH_TEXT} ${WRAP}`}>Why</th>
                          <th className={TH_TEXT}>By</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.history.map((h) => (
                          <tr key={h.id} className={TR}>
                            <td className={`${TD_TEXT} text-ink-secondary`}>
                              {moment(h.createdAt)}
                            </td>
                            <td className={TD_TEXT}>
                              {h.fromCondition ? `${CONDITION_LABEL[h.fromCondition]} → ` : ""}
                              {CONDITION_LABEL[h.toCondition]}
                            </td>
                            <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>
                              {h.reason ?? "—"}
                            </td>
                            <td className={`${TD_TEXT} text-ink-secondary`}>{h.actorName ?? "—"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </>
          ) : null}
        </div>
      </main>
    </div>
  );
}

/**
 * Every field the register holds, which is what this page exists for — the list carries five of
 * them and the other eleven are here (E3-S11 D1).
 */
function Record({ item, providers }: { item: EquipmentView; providers: ServiceProviderView[] }) {
  // The phone rides on the provider list rather than on the machine, because it is a fact about the
  // firm and not about the grinder. Absent for a reader who may not read that list, which is the
  // same reader who may not book the engineer.
  const provider = providers.find((p) => p.id === item.serviceProviderId);

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5">
      <dl className="grid grid-cols-2 gap-x-8 gap-y-4 sm:grid-cols-3">
        <Fact label="Condition">
          <ConditionBadge condition={item.condition} />
        </Fact>
        <Fact label="Where it lives">{item.storageLocation ?? "—"}</Fact>
        <Fact label="Kind">{CATEGORY_LABEL[item.category]}</Fact>
        <Fact label="How it came here">{item.source ? SOURCE_LABEL[item.source] : "—"}</Fact>
        <Fact label="Acquired on">
          {item.acquisitionDate ? dateWithYear(item.acquisitionDate) : "—"}
        </Fact>
        <Fact label="What it cost">
          {item.purchaseCostInr == null ? "—" : money(item.purchaseCostInr, "INR")}
        </Fact>
        <Fact label="Warranty runs to">
          {item.warrantyExpiry ? dateWithYear(item.warrantyExpiry) : "—"}
        </Fact>
        <Fact label="Serial number">{item.serialNumber ?? "—"}</Fact>
        <Fact label="Serviced every">
          {intervalWords(item.serviceIntervalCount, item.serviceIntervalUnit) ?? "—"}
        </Fact>
        <Fact label="Last serviced">
          {item.lastServicedOn ? dateWithYear(item.lastServicedOn) : "Never"}
        </Fact>
        <Fact label="Next service">
          <ServiceState item={item} />
        </Fact>
        <Fact label="Service company">
          {item.serviceProviderName ?? "—"}
          {provider?.phone ? (
            <span className="block text-xs text-ink-muted">{provider.phone}</span>
          ) : null}
        </Fact>
        {item.notes && (
          <div className="col-span-2 sm:col-span-3">
            <Fact label="Notes">{item.notes}</Fact>
          </div>
        )}
      </dl>
    </section>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-sm text-ink-secondary">{label}</dt>
      <dd className="mt-0.5 text-ink">{children}</dd>
    </div>
  );
}

/** Moving a machine to a new state, with the reason that is the whole point of the flow. */
function ChangeConditionForm({
  current,
  busy,
  onCancel,
  onSubmit,
}: {
  current: EquipmentCondition;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: { condition: EquipmentCondition; reason: string }) => void;
}) {
  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="condition-heading">
      <h2 id="condition-heading" className="text-lg">
        Change condition
      </h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The reason is kept for good, beside who wrote it and when. Scrapped is the end of the road —
        it stays on the register and drops out of the list.
      </p>
      <form
        className="mt-4 grid grid-cols-2 gap-4"
        aria-label="Change condition"
        onSubmit={(e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          onSubmit({
            condition: String(f.get("condition")) as EquipmentCondition,
            reason: String(f.get("reason") ?? "").trim(),
          });
        }}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">New condition</span>
          <select name="condition" defaultValue={current} className={FIELD}>
            {CONDITIONS.map((c) => (
              <option key={c} value={c}>
                {CONDITION_LABEL[c]}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Why</span>
          <input name="reason" required maxLength={500} className={FIELD} />
        </label>
        <div className="col-span-2 flex gap-3">
          <Button type="submit" disabled={busy}>
            Record the change
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </form>
    </section>
  );
}

/**
 * Recording a visit that has happened (E3-S10 D2).
 *
 * <p>Only the date is insisted on. Who came, what they did and what it cost are things a temple may
 * genuinely not have to hand a week later, and a form that refuses the row until every box is
 * filled produces no row at all — which is the outcome the feature exists to prevent.
 */
function RecordServiceForm({
  providers,
  defaultProviderId,
  busy,
  onCancel,
  onSubmit,
  onAddProvider,
}: {
  providers: ServiceProviderView[];
  defaultProviderId: string | null;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    servicedOn: string;
    serviceProviderId: string | null;
    workDone: string | null;
    costInr: number | null;
  }) => void;
  onAddProvider: (input: { name: string; phone: string | null }) => Promise<string | null>;
}) {
  // Opens on the company the machine is already signed up with, because that is who came in almost
  // every case. It stays changeable: a one-off repair by somebody else is exactly the visit worth
  // recording accurately, and a temple whose contract has moved should not have to edit the machine
  // before it can write down who actually turned up.
  const [providerId, setProviderId] = useState(defaultProviderId ?? "");

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="service-heading">
      <h2 id="service-heading" className="text-lg">
        Record a service
      </h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        Once written down it stays written down. There is no way to edit or remove a service, and no
        last-serviced date anybody can type into — the newest row here is what that date means.
      </p>
      <form
        className="mt-4 grid grid-cols-2 gap-4"
        aria-label="Record a service"
        onSubmit={(e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          const cost = String(f.get("costInr") ?? "").trim();
          const work = String(f.get("workDone") ?? "").trim();
          onSubmit({
            servicedOn: String(f.get("servicedOn")),
            serviceProviderId: providerId === "" ? null : providerId,
            workDone: work === "" ? null : work,
            costInr: cost === "" ? null : Number(cost),
          });
        }}
      >
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Serviced on</span>
          <input
            name="servicedOn"
            type="date"
            required
            max={todayIso()}
            defaultValue={todayIso()}
            className={FIELD}
          />
          <span className="pl-field-inset text-sm text-ink-secondary">
            A service dated next Tuesday has not happened yet.
          </span>
        </label>

        <ServiceCompanyPicker
          providers={providers}
          value={providerId}
          onChange={setProviderId}
          onAdd={onAddProvider}
        />

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What was done</span>
          <input name="workDone" maxLength={1000} className={FIELD} />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">What it cost (₹)</span>
          <input name="costInr" type="number" min="0" step="0.01" className={FIELD} />
        </label>

        <div className="col-span-2 flex gap-3">
          <Button type="submit" disabled={busy}>
            Record the service
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </form>
    </section>
  );
}

/**
 * How often this must be looked at, and by whom.
 *
 * <p>Here as well as on the register form, because a temple signs a maintenance contract long after
 * it unpacks the machine, and a schedule that could only ever be set in the minute the thing was
 * registered would be a schedule most machines never got. Sending an empty count clears it.
 */
function ScheduleForm({
  item,
  providers,
  busy,
  onCancel,
  onSubmit,
  onAddProvider,
}: {
  item: EquipmentView;
  providers: ServiceProviderView[];
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    intervalCount: number | null;
    intervalUnit: ServiceIntervalUnit | null;
    serviceProviderId: string | null;
  }) => void;
  onAddProvider: (input: { name: string; phone: string | null }) => Promise<string | null>;
}) {
  const [count, setCount] = useState(
    item.serviceIntervalCount == null ? "" : String(item.serviceIntervalCount)
  );
  const [unit, setUnit] = useState<ServiceIntervalUnit>(item.serviceIntervalUnit ?? "MONTHS");
  const [providerId, setProviderId] = useState(item.serviceProviderId ?? "");

  return (
    <section className="mb-8 rounded-lg bg-raised px-6 py-5" aria-labelledby="schedule-heading">
      <h2 id="schedule-heading" className="text-lg">
        Change the schedule
      </h2>
      <p className="mt-1 max-w-[60ch] text-sm text-ink-secondary">
        The next service date is worked out from this and the newest service, so changing it moves
        the date straight away. Empty it and the machine reads as not scheduled.
      </p>
      <div className="mt-4 grid grid-cols-2 gap-4">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Service it every</span>
          <div className="flex gap-2">
            <input
              aria-label="How often"
              type="number"
              min="1"
              max="100"
              value={count}
              onChange={(e) => setCount(e.target.value)}
              className={`${FIELD} min-w-0 flex-1`}
            />
            <select
              aria-label="Interval unit"
              value={unit}
              onChange={(e) => setUnit(e.target.value as ServiceIntervalUnit)}
              className={FIELD}
            >
              {INTERVAL_UNITS.map((u) => (
                <option key={u} value={u}>
                  {intervalUnitLabel(u)}
                </option>
              ))}
            </select>
          </div>
        </label>

        <ServiceCompanyPicker
          providers={providers}
          value={providerId}
          onChange={setProviderId}
          onAdd={onAddProvider}
        />

        <div className="col-span-2 flex gap-3">
          <Button
            type="button"
            disabled={busy}
            onClick={() =>
              onSubmit({
                intervalCount: count.trim() === "" ? null : Number(count),
                intervalUnit: count.trim() === "" ? null : unit,
                serviceProviderId: providerId === "" ? null : providerId,
              })
            }
          >
            Save the schedule
          </Button>
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </div>
    </section>
  );
}
