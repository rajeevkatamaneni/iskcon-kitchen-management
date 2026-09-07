"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
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
 *
 * <p><strong>Scrapping is asked about twice.</strong> It sits in the same dropdown as three values
 * that can be taken back, and it is the only one that cannot: the service refuses every condition
 * change after it, by design. A misclick one row down the list was therefore permanent, so the
 * commit opens {@link ConfirmScrap} first and names that consequence in the words the reader needs
 * — "cannot be undone" — before anything is sent. The other three keep going straight through:
 * a confirmation on a reversible act teaches people to click past confirmations.
 */

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/** The number beside the unit picker: three digits at most, so a fixed width rather than flex-1. */
// "3" and "months" are a phrase, not a row: the count is as wide as three digits and the unit
// as wide as its longest word. Letting the unit take the rest of the line put a tiny box
// beside a huge one, which is the shape Rajeev objected to on 2026-09-04.
const COUNT_FIELD = "w-20 shrink-0";
const UNIT_FIELD = "w-32 shrink-0";

const CONDITIONS: EquipmentCondition[] = ["GOOD", "NEEDS_REPAIR", "IN_REPAIR", "SCRAPPED"];

export default function EquipmentItemPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      {/* useSearchParams — a correction made on /equipment/[id]/edit comes back here with its
          confirmation in the URL. */}
      <Suspense>
        <EquipmentItemView />
      </Suspense>
    </RequireRole>
  );
}

function EquipmentItemView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { appUser, getToken } = useAuth();

  // Recording a service and setting an interval are one permission, and it is the temple admin's
  // alone (E3-S10 D10). Kitchen staff read this page — they are the ones standing in front of the
  // grinder — and are offered neither.
  const isAdmin = appUser?.role === "TEMPLE_ADMIN";

  const fetchItem = useCallback((token: string | undefined) => api.getEquipment(id, token), [id]);
  const { data, error, loading, reload } = useAuthedQuery(fetchItem);

  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [open, setOpen] = useState<"service" | "condition" | "schedule" | null>(null);

  const item = data?.equipment;

  // Editing happens on /equipment/[id]/edit and ends back here, so the confirmation travels in the
  // URL. Captured behind a ref because setting it re-renders, and a router object that is new on
  // each render would otherwise turn this effect into a loop — the fault found on Ingredients.
  const search = useSearchParams();
  const router = useRouter();
  const saved = search.get("saved");
  const [flash, setFlash] = useState<string | null>(null);
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !saved) return;
    captured.current = true;
    setFlash(saved);
    router.replace(`/equipment/${id}`);
  }, [saved, router, id]);

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
                    {item.storageLocation ?? "Nobody has said where it lives"}
                  </p>
                </div>
                <div className="flex flex-wrap gap-3">
                  {/* A correction, not a state change: a transposed digit in a serial number or a
                      warranty date a year out. Its own screen, because eight fields is well past
                      the four the design system converts at. */}
                  <ButtonLink href={`/equipment/${id}/edit`} variant="ghost">
                    Edit details
                  </ButtonLink>
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

              {flash && (
                <div className="mb-6">
                  {/* Clears itself after five seconds: there is nothing left in it to act on. */}
                  <InlineNotice tone="success" autoDismiss>
                    {flash} has been updated.
                  </InlineNotice>
                </div>
              )}

              {actionError && (
                <div className="mb-6">
                  <ErrorNotice error={actionError} />
                </div>
              )}

              {open === "condition" && (
                <ChangeConditionForm
                  name={item.name}
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
                  defaultCompany={item.serviceCompany}
                  busy={busy}
                  onCancel={() => setOpen(null)}
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
                  busy={busy}
                  onCancel={() => setOpen(null)}
                  onSubmit={(input) =>
                    run(
                      (t) => api.setEquipmentServiceSchedule(id, input, t),
                      "We couldn’t save that schedule."
                    )
                  }
                />
              )}

              <Record item={item} />

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
                  <p className="card px-6 py-8 text-center text-ink-secondary">
                    Nobody has recorded a service against this yet.
                  </p>
                ) : (
                  <div className="table-wrap overflow-x-auto">
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
                              {s.serviceCompany ?? "—"}
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
                  <p className="card px-6 py-8 text-center text-ink-secondary">
                    Its condition has not moved since it was registered.
                  </p>
                ) : (
                  <div className="table-wrap overflow-x-auto">
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
function Record({ item }: { item: EquipmentView }) {
  return (
    <section className="card mb-8 px-6 py-5">
      <dl className="grid grid-cols-2 gap-x-8 gap-y-4 sm:grid-cols-3">
        <Fact label="Condition">
          <ConditionBadge condition={item.condition} />
        </Fact>
        <Fact label="Where it lives">{item.storageLocation ?? "—"}</Fact>
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
          {item.serviceCompany ?? "—"}
          {item.serviceCompanyPhone ? (
            <span className="block text-xs text-ink-muted">{item.serviceCompanyPhone}</span>
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

/**
 * Moving a machine to a new state, with the reason that is the whole point of the flow.
 *
 * <p>Three of the four values here can be taken back by choosing another one tomorrow. The fourth
 * cannot: `SCRAPPED` is terminal by design in `EquipmentService`, which refuses every condition
 * change made after it, and it sits one row below *In repair* in an ordinary dropdown. So the
 * commit stops on that value and asks (docket M7). The other three are sent the moment the button
 * is pressed and gain nothing — a dialog in front of a reversible act is a dialog people learn to
 * dismiss without reading, which is precisely what would blunt this one.
 */
function ChangeConditionForm({
  name,
  current,
  busy,
  onCancel,
  onSubmit,
}: {
  /** The machine's own name, so the question names what is about to be scrapped. */
  name: string;
  current: EquipmentCondition;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: { condition: EquipmentCondition; reason: string }) => void;
}) {
  // What the person filled in, held while they answer the question. Null at every other moment.
  const [pending, setPending] = useState<{
    condition: EquipmentCondition;
    reason: string;
  } | null>(null);

  return (
    <section className="card mb-8 px-6 py-5" aria-labelledby="condition-heading">
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
          const input = {
            condition: String(f.get("condition")) as EquipmentCondition,
            reason: String(f.get("reason") ?? "").trim(),
          };
          // The one value that cannot be taken back is the one that gets asked about. Everything
          // typed stays in the form behind the dialog, so Cancel costs nothing but the click.
          if (input.condition === "SCRAPPED") {
            setPending(input);
            return;
          }
          onSubmit(input);
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

      {pending && (
        <ConfirmScrap
          name={name}
          busy={busy}
          onCancel={() => setPending(null)}
          onConfirm={() => {
            // Closed before the request goes, so that a failure lands on the error notice above the
            // form rather than behind a dialog covering it.
            setPending(null);
            onSubmit(pending);
          }}
        />
      )}
    </section>
  );
}

/**
 * The question asked before a machine is scrapped.
 *
 * <p>It exists to say the one thing the dropdown could not: that this is the end. The copy names
 * the consequence rather than asking "are you sure" — a person who has misread the row is certain,
 * and certainty is not what is being tested. What is being tested is whether they know that no
 * later change of condition will be accepted.
 *
 * <p>It does not offer a way back, because there is not one. Whether scrapping should ever become
 * reversible is a question outstanding with Rajeev; until it is answered, a screen that implied an
 * undo existed would be worse than the misclick it was added to prevent.
 */
function ConfirmScrap({
  name,
  busy,
  onCancel,
  onConfirm,
}: {
  name: string;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  // Escape cancels, as it does anywhere a panel covers what somebody was reading.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onCancel]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="scrap-title"
    >
      <div className="modal w-full max-w-prose px-8 py-7">
        <h2 id="scrap-title" className="text-lg text-danger">
          Scrap {name}?
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          Scrapping cannot be undone. Its condition can never be changed again, and it drops off the
          equipment list.
        </p>
        <p className="mt-2 text-sm text-ink-secondary">
          The record stays on the register with everything written against it, so its history and
          what it cost are still readable. If the machine is only broken, choose{" "}
          <em>Needs repair</em> instead — that one can be taken back.
        </p>

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          {/* The keyboard lands on Cancel, not on the destructive button: nothing here should be
              one Enter away from a dialog somebody has not finished reading. */}
          <Button autoFocus type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="button" variant="danger" busy={busy} onClick={onConfirm}>
            Scrap it
          </Button>
        </div>
      </div>
    </div>
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
  defaultCompany,
  busy,
  onCancel,
  onSubmit,
}: {
  defaultCompany: string | null;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    servicedOn: string;
    serviceCompany: string | null;
    workDone: string | null;
    costInr: number | null;
  }) => void;
}) {
  // Opens on the company the machine is already signed up with, because that is who came in almost
  // every case. It stays changeable: a one-off repair by somebody else is exactly the visit worth
  // recording accurately, and a temple whose contract has moved should not have to edit the machine
  // before it can write down who actually turned up.
  const [company, setCompany] = useState(defaultCompany ?? "");

  return (
    <section className="card mb-8 px-6 py-5" aria-labelledby="service-heading">
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
            serviceCompany: company.trim() === "" ? null : company.trim(),
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

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Service company</span>
          <input
            value={company}
            onChange={(e) => setCompany(e.target.value)}
            maxLength={200}
            className={FIELD}
          />
        </label>

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
  busy,
  onCancel,
  onSubmit,
}: {
  item: EquipmentView;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    intervalCount: number | null;
    intervalUnit: ServiceIntervalUnit | null;
    serviceCompany: string | null;
    serviceCompanyPhone: string | null;
  }) => void;
}) {
  const [count, setCount] = useState(
    item.serviceIntervalCount == null ? "" : String(item.serviceIntervalCount)
  );
  const [unit, setUnit] = useState<ServiceIntervalUnit>(item.serviceIntervalUnit ?? "MONTHS");
  const [company, setCompany] = useState(item.serviceCompany ?? "");
  const [companyPhone, setCompanyPhone] = useState(item.serviceCompanyPhone ?? "");

  return (
    <section className="card mb-8 px-6 py-5" aria-labelledby="schedule-heading">
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
              className={`${FIELD} ${COUNT_FIELD}`}
            />
            <select
              aria-label="Interval unit"
              value={unit}
              onChange={(e) => setUnit(e.target.value as ServiceIntervalUnit)}
              className={`${FIELD} ${UNIT_FIELD}`}
            >
              {INTERVAL_UNITS.map((u) => (
                <option key={u} value={u}>
                  {intervalUnitLabel(u)}
                </option>
              ))}
            </select>
          </div>
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Service company</span>
          <input
            value={company}
            onChange={(e) => setCompany(e.target.value)}
            maxLength={200}
            className={FIELD}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Their phone number</span>
          <input
            value={companyPhone}
            onChange={(e) => setCompanyPhone(e.target.value)}
            maxLength={40}
            className={FIELD}
          />
        </label>

        <div className="col-span-2 flex gap-3">
          <Button
            type="button"
            disabled={busy}
            onClick={() =>
              onSubmit({
                intervalCount: count.trim() === "" ? null : Number(count),
                intervalUnit: count.trim() === "" ? null : unit,
                serviceCompany: company.trim() === "" ? null : company.trim(),
                serviceCompanyPhone: companyPhone.trim() === "" ? null : companyPhone.trim(),
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
