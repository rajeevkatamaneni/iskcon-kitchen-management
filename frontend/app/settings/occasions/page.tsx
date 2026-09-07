"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Field } from "@/components/Field";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Badge } from "@/components/ds/Badge";
import { Button } from "@/components/ds/Button";
import { EmptyState } from "@/components/ds/EmptyState";
import { FieldRow } from "@/components/ds/FieldRow";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  ACTIONS_ROW,
  TABLE,
  TD_ACTIONS,
  TD_TEXT,
  THEAD,
  TH_ACTIONS,
  TH_TEXT,
  TR,
  WRAP,
} from "@/components/ds/table";
import { api, toApiError, type ApiError, type OccasionView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * The temple's own list of festival occasions (E4-S2).
 *
 * <p>Until now the catalogue could only be read: the meal composer offers it as an autocomplete
 * when a feast needs naming, and everything in it arrived with the temple at provisioning. That
 * left a temple unable to add the one occasion nobody else can add for it — its own anniversary,
 * its founding acarya's appearance day, the day the deities were installed.
 *
 * <p>Rajeev asked whether an Event could carry this instead, and the answer was no: an Event is a
 * thing cooked on one date, and an occasion is a recurring entry that tells the planner what kind
 * of day it is. They are different objects with different lifetimes, so this screen curates the
 * occasion rather than standing in for it.
 *
 * <p>Temple Admin only, matching the server: reading the catalogue is `MANAGE_MEAL_PLANS`, but
 * curating it is `MANAGE_TEMPLE_SETTINGS`, because what the temple observes is a standing decision
 * about the temple rather than a choice made while planning a week's meals.
 */
export default function OccasionsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <OccasionsView />
    </RequireRole>
  );
}

/** How an occasion's dates are found, in the two words a person would use for each. */
type OccasionKind = "COMPUTED" | "MANUAL";

/**
 * The months, with the longest day each one can hold.
 *
 * <p>February is 29 rather than 28 on purpose: the server accepts any day from 1 to 31 and simply
 * produces no occurrence in a year where the date does not exist, so a temple that observes
 * something on 29 February may say so and see it in leap years only. The screen says that out loud
 * rather than silently accepting a date that will mostly not happen.
 */
const MONTHS: readonly { value: number; label: string; days: number }[] = [
  { value: 1, label: "January", days: 31 },
  { value: 2, label: "February", days: 29 },
  { value: 3, label: "March", days: 31 },
  { value: 4, label: "April", days: 30 },
  { value: 5, label: "May", days: 31 },
  { value: 6, label: "June", days: 30 },
  { value: 7, label: "July", days: 31 },
  { value: 8, label: "August", days: 31 },
  { value: 9, label: "September", days: 30 },
  { value: 10, label: "October", days: 31 },
  { value: 11, label: "November", days: 30 },
  { value: 12, label: "December", days: 31 },
];

function monthLabel(month: number | null): string {
  return MONTHS.find((m) => m.value === month)?.label ?? "";
}

/** What a row's date column says, in the reader's terms rather than the database's. */
function whenItFalls(o: OccasionView): string {
  if (o.type === "MANUAL") {
    return o.fixedMonth && o.fixedDay ? `${o.fixedDay} ${monthLabel(o.fixedMonth)}, every year` : "No date set";
  }
  return o.matchText ? `Whatever day the calendar gives “${o.matchText}”` : "From the calendar";
}

/**
 * The form's own state, all of it strings.
 *
 * <p>An empty number input is the empty string and not zero, and the difference matters here —
 * blank servings mean the temple has not said, which is a value the server stores as nothing at
 * all. Kept as typed and converted once, at the edge, rather than juggling two representations.
 */
interface Draft {
  name: string;
  kind: OccasionKind;
  matchText: string;
  fixedMonth: string;
  fixedDay: string;
  defaultServings: string;
  notes: string;
}

const EMPTY_DRAFT: Draft = {
  name: "",
  kind: "COMPUTED",
  matchText: "",
  fixedMonth: "1",
  fixedDay: "1",
  defaultServings: "",
  notes: "",
};

function draftOf(o: OccasionView): Draft {
  return {
    name: o.name,
    kind: o.type,
    matchText: o.matchText ?? "",
    fixedMonth: String(o.fixedMonth ?? 1),
    fixedDay: String(o.fixedDay ?? 1),
    defaultServings: o.defaultServings === null ? "" : String(o.defaultServings),
    notes: o.notes ?? "",
  };
}

function numberOrNull(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}

/**
 * The fields the server actually wants for this kind, and nothing from the other kind.
 *
 * <p>The server ignores a match text on a fixed-date occasion and a date on a computed one, but
 * sending them anyway would leave stale values behind on the record for the next reader to puzzle
 * over. Only the pair that belongs to the kind is sent, and the other is sent as nothing.
 */
function dateFieldsOf(draft: Draft) {
  return draft.kind === "COMPUTED"
    ? { matchText: draft.matchText.trim(), fixedMonth: null, fixedDay: null }
    : {
        matchText: null,
        fixedMonth: numberOrNull(draft.fixedMonth),
        fixedDay: numberOrNull(draft.fixedDay),
      };
}

function OccasionsView() {
  const { getToken } = useAuth();
  const fetcher = useCallback((token: string | undefined) => api.listOccasions(token), []);
  const { data, error, loading, reload } = useAuthedQuery(fetcher);
  const occasions = data ?? [];

  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [editing, setEditing] = useState<OccasionView | null>(null);
  const [confirming, setConfirming] = useState<OccasionView | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setActionError(null);
    try {
      await api.createOccasion(
        {
          name: draft.name.trim(),
          type: draft.kind,
          ...dateFieldsOf(draft),
          defaultServings: numberOrNull(draft.defaultServings),
          notes: draft.notes.trim() || null,
        },
        await getToken()
      );
      setFlash(`${draft.name.trim()} was added.`);
      setDraft(EMPTY_DRAFT);
      // The list is refetched rather than the page reloaded, so the new occasion appears in the
      // table below without the reader losing their place on the screen.
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t add that occasion."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/settings/occasions" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Festival occasions</h1>
            <p className="mt-1 text-ink-secondary">
              The days this temple observes. The planner marks them on the calendar and offers them
              by name when a feast is being planned.
            </p>
          </header>

          {actionError && (
            <div className="mb-6">
              <ErrorNotice error={actionError} />
            </div>
          )}

          {flash && (
            <div className="mb-6">
              <InlineNotice tone="success" autoDismiss title={flash}>
                Anyone planning a meal sees it from their next screen.
              </InlineNotice>
            </div>
          )}

          <section className="card mb-8 px-6 py-5" aria-labelledby="add-occasion-heading">
            <h2 id="add-occasion-heading" className="text-lg">
              Add an occasion
            </h2>
            <form className="mt-4 grid gap-5" aria-label="Add an occasion" onSubmit={add}>
              <fieldset className="grid gap-2">
                <legend className="pl-field-inset text-sm font-medium text-ink">
                  How its date is decided
                </legend>
                {/*
                  Asked first, and asked once. The server takes no type on an edit — a computed
                  occasion and a fixed-date one are different things and it asks you to recreate
                  rather than convert — so this is the one decision on the form that cannot be
                  changed later, and it belongs above the fields it governs rather than beneath
                  them.
                */}
                <label className="flex items-baseline gap-2 text-sm">
                  <input
                    type="radio"
                    name="occasion-kind"
                    value="COMPUTED"
                    checked={draft.kind === "COMPUTED"}
                    onChange={() => setDraft({ ...draft, kind: "COMPUTED" })}
                    className="mt-1 accent-accent"
                  />
                  <span>
                    <span className="text-ink">The Vaishnava calendar decides</span>{" "}
                    <span className="text-ink-muted">
                      moves with the moon, like Janmastami or Gaura Purnima
                    </span>
                  </span>
                </label>
                <label className="flex items-baseline gap-2 text-sm">
                  <input
                    type="radio"
                    name="occasion-kind"
                    value="MANUAL"
                    checked={draft.kind === "MANUAL"}
                    onChange={() => setDraft({ ...draft, kind: "MANUAL" })}
                    className="mt-1 accent-accent"
                  />
                  <span>
                    <span className="text-ink">The same date every year</span>{" "}
                    <span className="text-ink-muted">
                      a temple anniversary, an installation day
                    </span>
                  </span>
                </label>
              </fieldset>

              <OccasionFields idPrefix="add" draft={draft} onChange={setDraft} />

              <div>
                <Button type="submit" busy={busy}>
                  Add occasion
                </Button>
              </div>
            </form>
          </section>

          {loading ? (
            <Loading label="Loading occasions…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : occasions.length === 0 ? (
            <EmptyState title="No occasions yet">
              An occasion tells the planner what kind of day a date is, and gives a feast its name.
              Add the days this temple observes.
            </EmptyState>
          ) : (
            <div className="table-wrap overflow-x-auto">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Occasion</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>When it falls</th>
                    <th className={TH_TEXT}>Usual servings</th>
                    <th className={TH_ACTIONS}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {occasions.map((o) => (
                    <tr key={o.id} className={TR}>
                      <td className={`${TD_TEXT} ${WRAP}`}>
                        <span className="flex flex-wrap items-center gap-2">
                          <span className="font-medium">{o.name}</span>
                          {o.seeded && <Badge>Standard</Badge>}
                        </span>
                        {o.notes && (
                          <span className="mt-0.5 block text-sm text-ink-secondary">{o.notes}</span>
                        )}
                      </td>
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{whenItFalls(o)}</td>
                      <td className={`${TD_TEXT} text-ink-secondary`}>
                        {o.defaultServings === null ? "Not set" : o.defaultServings}
                      </td>
                      <td className={TD_ACTIONS}>
                        <div className={ACTIONS_ROW}>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={busy}
                            onClick={() => {
                              setActionError(null);
                              setEditing(o);
                            }}
                          >
                            Edit
                          </Button>
                          <Button
                            variant="danger"
                            size="sm"
                            disabled={busy}
                            onClick={() => {
                              setActionError(null);
                              setConfirming(o);
                            }}
                          >
                            Delete
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </main>

      {editing && (
        <EditOccasion
          occasion={editing}
          onDone={(name) => {
            setEditing(null);
            setFlash(`${name} was saved.`);
            reload();
          }}
          onCancel={() => setEditing(null)}
        />
      )}

      {confirming && (
        <DeleteOccasion
          occasion={confirming}
          onDone={(name) => {
            setConfirming(null);
            setFlash(`${name} was removed.`);
            reload();
          }}
          onCancel={() => setConfirming(null)}
        />
      )}
    </div>
  );
}

/**
 * The fields an occasion has, shared by the add form and the edit dialog.
 *
 * <p>Shared because the two forms ask for the same things and must go on asking for the same
 * things: a field added to one and not the other is how a screen comes to accept a value it will
 * not let you correct. The ids are prefixed rather than fixed, because both forms can be mounted
 * at once and two controls with one id leave a label pointing at whichever the browser saw first.
 */
function OccasionFields({
  idPrefix,
  draft,
  onChange,
}: {
  idPrefix: string;
  draft: Draft;
  onChange: (draft: Draft) => void;
}) {
  const month = MONTHS.find((m) => m.value === Number(draft.fixedMonth)) ?? MONTHS[0];
  const leapDay = draft.kind === "MANUAL" && month.value === 2 && Number(draft.fixedDay) === 29;

  return (
    <>
      <Field id={`${idPrefix}-name`} label="Name" required>
        {(props) => (
          <input
            {...props}
            type="text"
            required
            maxLength={200}
            placeholder="Temple Anniversary"
            value={draft.name}
            onChange={(e) => onChange({ ...draft, name: e.target.value })}
          />
        )}
      </Field>

      {draft.kind === "COMPUTED" ? (
        <Field
          id={`${idPrefix}-match-text`}
          label="Wording in the calendar"
          hint="Part of the name the calendar prints for this day, such as Janmastami."
          required
        >
          {(props) => (
            <input
              {...props}
              type="text"
              required
              maxLength={200}
              placeholder="Janmastami"
              value={draft.matchText}
              onChange={(e) => onChange({ ...draft, matchText: e.target.value })}
            />
          )}
        </Field>
      ) : (
        <div>
          <FieldRow>
            <Field id={`${idPrefix}-fixed-month`} label="Month" required>
              {(props) => (
                <select
                  {...props}
                  value={draft.fixedMonth}
                  onChange={(e) => onChange({ ...draft, fixedMonth: e.target.value })}
                >
                  {MONTHS.map((m) => (
                    <option key={m.value} value={String(m.value)}>
                      {m.label}
                    </option>
                  ))}
                </select>
              )}
            </Field>

            <Field id={`${idPrefix}-fixed-day`} label="Day" required>
              {(props) => (
                <input
                  {...props}
                  type="number"
                  inputMode="numeric"
                  required
                  min={1}
                  max={month.days}
                  value={draft.fixedDay}
                  onChange={(e) => onChange({ ...draft, fixedDay: e.target.value })}
                />
              )}
            </Field>
          </FieldRow>

          {/*
            Visible text and not a hint: it is a consequence of what has just been typed, and the
            reader has to see it without going looking. The server behaves exactly as this says —
            a date that does not exist in a given year simply produces no occurrence that year.
          */}
          {leapDay && (
            <p className="mt-2 pl-field-inset text-sm text-ink-secondary">
              29 February falls in leap years only. In every other year this occasion does not come
              round.
            </p>
          )}
        </div>
      )}

      <Field
        id={`${idPrefix}-default-servings`}
        label="Usual servings"
        hint="What this occasion normally cooks for. Left blank if it varies."
      >
        {(props) => (
          <input
            {...props}
            type="number"
            inputMode="numeric"
            min={0}
            value={draft.defaultServings}
            onChange={(e) => onChange({ ...draft, defaultServings: e.target.value })}
          />
        )}
      </Field>

      <Field id={`${idPrefix}-notes`} label="Notes" hint="Anything the kitchen should know about this day.">
        {(props) => (
          <textarea
            {...props}
            // The one field somebody writes a sentence in, so it takes the vertical padding a
            // single-line control gets from its height alone.
            className={`${props.className} py-2`}
            rows={2}
            maxLength={1000}
            value={draft.notes}
            onChange={(e) => onChange({ ...draft, notes: e.target.value })}
          />
        )}
      </Field>
    </>
  );
}

/**
 * Editing one, and the one thing that cannot be edited.
 *
 * <p>`UpdateOccasionRequest` carries no type, deliberately, so this dialog does not offer the
 * choice at all rather than offering it and refusing it on save. What it does instead is say which
 * kind this occasion is and what to do if that is the thing that needs changing, because a reader
 * who came here to change it needs an answer and not a missing control.
 */
function EditOccasion({
  occasion,
  onDone,
  onCancel,
}: {
  occasion: OccasionView;
  onDone: (name: string) => void;
  onCancel: () => void;
}) {
  const { getToken } = useAuth();
  const [draft, setDraft] = useState<Draft>(draftOf(occasion));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.updateOccasion(
        occasion.id,
        {
          name: draft.name.trim(),
          ...dateFieldsOf(draft),
          defaultServings: numberOrNull(draft.defaultServings),
          notes: draft.notes.trim() || null,
        },
        await getToken()
      );
      onDone(draft.name.trim());
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that occasion."));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="edit-occasion-title"
    >
      <div className="modal max-h-[90vh] w-full max-w-prose overflow-y-auto px-8 py-7">
        <h2 id="edit-occasion-title" className="text-lg">
          Edit {occasion.name}
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          {draft.kind === "COMPUTED"
            ? "The Vaishnava calendar decides this date. To put it on a fixed date instead, add it again as a new occasion and remove this one."
            : "This occasion falls on the same date every year. To let the calendar decide it instead, add it again as a new occasion and remove this one."}
        </p>

        <form className="mt-5 grid gap-5" aria-label="Edit an occasion" onSubmit={save}>
          <OccasionFields idPrefix="edit" draft={draft} onChange={setDraft} />

          {error && <ErrorNotice error={error} />}

          <div className="flex flex-wrap items-center justify-end gap-3">
            <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" busy={busy}>
              Save occasion
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * Delete, and what the reader is owed before pressing it.
 *
 * <p>The server does not refuse this. Meal plans keep the occasion’s name as text rather than a
 * reference to the row (E4-S4), so removing an occasion the planner has already resolved against
 * orphans nothing and raises nothing — and that is precisely why the consequence has to be stated
 * here instead. What actually happens is that days it used to mark stop being marked, while every
 * plan already made goes on reading exactly as it did. A reader who is not told that will read the
 * silence as "nothing happened" and go looking for the change on the calendar.
 *
 * <p>If the server ever does refuse — a later reference, a request that never arrives — it is
 * shown in the reader’s own terms with its code, and the dialog stays open, rather than the raw
 * failure reaching the screen.
 */
function DeleteOccasion({
  occasion,
  onDone,
  onCancel,
}: {
  occasion: OccasionView;
  onDone: (name: string) => void;
  onCancel: () => void;
}) {
  const { getToken } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api.deleteOccasion(occasion.id, await getToken());
      onDone(occasion.name);
    } catch (e) {
      setError(toApiError(e, "We couldn’t remove that occasion."));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-occasion-title"
    >
      <div className="modal w-full max-w-prose px-8 py-7">
        <h2 id="delete-occasion-title" className="text-lg text-danger">
          Remove {occasion.name}?
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          The planner stops marking this day and stops offering the name. Meals already planned or
          cooked for it keep the name they were saved with, so nothing in your records changes.
        </p>
        {occasion.seeded && (
          <p className="mt-2 text-sm text-ink-secondary">
            This is one of the festivals every temple starts with. Removing it here removes it for
            this temple only, and it does not come back on its own.
          </p>
        )}

        {error && (
          <div className="mt-4">
            <ErrorNotice error={error} />
          </div>
        )}

        <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
          <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button type="button" variant="danger" onClick={remove} busy={busy}>
            Remove occasion
          </Button>
        </div>
      </div>
    </div>
  );
}
