"use client";

import { useCallback, useState } from "react";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Field } from "@/components/Field";
import { RequireRole } from "@/components/RequireRole";
import { Loading } from "@/components/Loading";
import { Button } from "@/components/ds/Button";
import { EmptyState } from "@/components/ds/EmptyState";
import { FieldRow } from "@/components/ds/FieldRow";
import { InlineNotice } from "@/components/ds/InlineNotice";
import {
  ACTIONS_ROW,
  TABLE,
  TD_ACTIONS,
  TD_NUM,
  TD_TEXT,
  THEAD,
  TH_ACTIONS,
  TH_NUM,
  TH_TEXT,
  TR,
  WRAP,
} from "@/components/ds/table";
import { api, toApiError, type ApiError, type MealKindInput, type MealKindView } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { hhmm } from "@/lib/format";
import { useAuthedQuery } from "@/lib/use-authed-query";

/**
 * What this temple calls its meals, and when each one is due (E4-S7).
 *
 * <p>The endpoints behind this screen have existed since E4-S7 and nothing in the application has
 * ever called three of them. `GET /api/v1/meal-kinds` has four callers — the planner, the composer,
 * the day view — and `POST`, `PUT` and `DELETE` had **none**, so a temple was stuck with the six
 * kinds provisioning gave it: it could not start serving an evening meal, could not call Lunch
 * *Raj Bhog*, and could not remove a kind it had added by mistake, because there was no way to add
 * one in the first place. T-038 went further and shipped a whole rename cascade, with a migration
 * and a test suite, that no user could reach. This screen is the caller.
 *
 * <p>Temple Admin only, matching the server exactly: reading the kinds is `MANAGE_MEAL_PLANS`,
 * because the planner has to know a Deity Offering has no usual hour, but changing them is
 * `MANAGE_TEMPLE_SETTINGS`. What time the temple eats, and what it calls the meal, is a standing
 * decision about the temple rather than something a cook settles mid-shift.
 *
 * <p>It is the sibling of `/settings/occasions` and is built to the same shape on purpose — one
 * table, one add form above it, an edit and a delete per row — because curating the kinds of meal
 * and curating the festival occasions are the same act on two different standing facts, and a
 * temple admin should not have to learn two screens to do them.
 */
export default function MealKindsPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN"]}>
      <MealKindsView />
    </RequireRole>
  );
}

/**
 * The form's own state, all of it strings and booleans.
 *
 * <p>`readyTime` and `order` are kept as typed rather than as numbers, because an empty control is
 * the empty string and not zero, and in both cases empty *means something*. An empty ready time is
 * a kind that always asks; an empty order is "put it after everything else". Converted once, at the
 * edge, so there is never a moment where a blank field and a zero look alike.
 */
interface Draft {
  name: string;
  /** "HH:mm" from the time control, or "" for a kind that must always be given a time. */
  readyTime: string;
  /** The sort order as typed. Blank on the add form, where it means "last". */
  order: string;
  isEvent: boolean;
  needsOccasion: boolean;
}

const EMPTY_DRAFT: Draft = {
  name: "",
  readyTime: "",
  order: "",
  isEvent: false,
  needsOccasion: false,
};

function draftOf(kind: MealKindView): Draft {
  return {
    name: kind.name,
    // The API sends "HH:mm:ss" and a time control will not accept the seconds, so it is trimmed to
    // the two parts a person types. `hhmm` renders an em dash for null, which is right for a table
    // cell and wrong for an input, so null becomes the empty string here instead.
    readyTime: kind.defaultReadyTime ? hhmm(kind.defaultReadyTime) : "",
    order: String(kind.sortOrder),
    isEvent: kind.isEvent,
    needsOccasion: kind.needsOccasion,
  };
}

/**
 * The draft as the server wants it.
 *
 * <p>Every field of `MealKindInput` is sent every time, including the two flags, and that is the
 * part worth stating: `PUT /api/v1/meal-kinds/{id}` is a whole-record write, so an edit that
 * omitted `isEvent` would quietly turn the temple's Event kind into an ordinary meal — the planner
 * would stop asking for the event's name, and nobody would connect that to having renamed something
 * on a settings screen a week earlier. The flags are on the form for the same reason.
 */
function inputOf(draft: Draft, fallbackOrder: number): MealKindInput {
  const order = Number(draft.order.trim());
  return {
    name: draft.name.trim(),
    sortOrder: draft.order.trim() && Number.isFinite(order) ? order : fallbackOrder,
    // Blank is null and not "00:00": a kind with no default time is one the planner asks about
    // every time, which is what the occasional kinds — a feast, a deity offering, an event — need.
    defaultReadyTime: draft.readyTime.trim() || null,
    isEvent: draft.isEvent,
    needsOccasion: draft.needsOccasion,
  };
}

/**
 * What the planner will ask for beyond a recipe, said in the planner's own terms.
 *
 * <p>The record holds two booleans, and two booleans in a table column tell a reader nothing. What
 * they actually mean is which extra questions the meal composer puts up, so that is what the column
 * says.
 */
function whatItAsksFor(kind: MealKindView): string {
  const asks: string[] = [];
  if (kind.isEvent) asks.push("The event’s name, and whether it is going outside");
  if (kind.needsOccasion) asks.push("Which festival it is for");
  return asks.length ? asks.join(" · ") : "Nothing beyond the dishes";
}

function MealKindsView() {
  const { getToken } = useAuth();
  const fetcher = useCallback((token: string | undefined) => api.listMealKinds(token), []);
  const { data, error, loading, reload } = useAuthedQuery(fetcher);
  const kinds = data ?? [];

  // Where a new kind goes if nobody says: after everything the temple already has, in the same
  // steps of ten the seeded kinds use, so there is room to slot something in between later.
  const nextOrder = kinds.reduce((highest, k) => Math.max(highest, k.sortOrder), 0) + 10;

  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [editing, setEditing] = useState<MealKindView | null>(null);
  const [confirming, setConfirming] = useState<MealKindView | null>(null);
  const [flash, setFlash] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setActionError(null);
    try {
      await api.createMealKind(inputOf(draft, nextOrder), await getToken());
      setFlash(`${draft.name.trim()} was added.`);
      setDraft(EMPTY_DRAFT);
      // Refetched rather than reloaded, so the new kind appears in the table below without the
      // reader losing their place — and so the order column shows what the server actually stored.
      reload();
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t add that kind of meal."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/settings/meal-kinds" />

      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto max-w-content">
          <header className="mb-8">
            <h1>Meal kinds</h1>
            <p className="mt-1 text-ink-secondary">
              The meals this temple cooks, in the order they come in the day, and the time each one
              is normally due. The planner offers these when a meal is being planned.
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

          <section className="card mb-8 px-6 py-5" aria-labelledby="add-meal-kind-heading">
            <h2 id="add-meal-kind-heading" className="text-lg">
              Add a kind of meal
            </h2>
            <form className="mt-4 grid gap-5" aria-label="Add a kind of meal" onSubmit={add}>
              <MealKindFields idPrefix="add" draft={draft} onChange={setDraft} lastPlace={nextOrder} />

              <div>
                <Button type="submit" busy={busy}>
                  Add meal kind
                </Button>
              </div>
            </form>
          </section>

          {loading ? (
            <Loading label="Loading meal kinds…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : kinds.length === 0 ? (
            <EmptyState title="No kinds of meal yet">
              A kind of meal is what the planner plans against — breakfast, lunch, a festival feast.
              Add the meals this temple cooks.
            </EmptyState>
          ) : (
            <div className="table-wrap overflow-x-auto">
              <table className={TABLE}>
                <thead className={THEAD}>
                  <tr>
                    <th className={`${TH_TEXT} ${WRAP}`}>Meal kind</th>
                    <th className={TH_TEXT}>Usually ready by</th>
                    <th className={`${TH_TEXT} ${WRAP}`}>What the planner asks for</th>
                    <th className={TH_NUM}>Order</th>
                    <th className={TH_ACTIONS}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {kinds.map((k) => (
                    <tr key={k.id} className={TR}>
                      <td className={`${TD_TEXT} ${WRAP}`}>
                        <span className="font-medium">{k.name}</span>
                      </td>
                      <td className={`${TD_TEXT} tabular-nums text-ink-secondary`}>
                        {/* Not an em dash. "No usual time" is a decision the temple made, and the
                            planner behaves differently because of it — it asks every time — so the
                            column says it in words rather than leaving a blank to be read as
                            missing data. */}
                        {k.defaultReadyTime ? hhmm(k.defaultReadyTime) : "Asked every time"}
                      </td>
                      <td className={`${TD_TEXT} ${WRAP} text-ink-secondary`}>{whatItAsksFor(k)}</td>
                      <td className={`${TD_NUM} text-ink-secondary`}>{k.sortOrder}</td>
                      <td className={TD_ACTIONS}>
                        <div className={ACTIONS_ROW}>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={busy}
                            onClick={() => {
                              setActionError(null);
                              setEditing(k);
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
                              setConfirming(k);
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
        <EditMealKind
          kind={editing}
          onDone={(name) => {
            setEditing(null);
            setFlash(`${name} was saved.`);
            reload();
          }}
          onCancel={() => setEditing(null)}
        />
      )}

      {confirming && (
        <DeleteMealKind
          kind={confirming}
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
 * The fields a kind of meal has, shared by the add form and the edit dialog.
 *
 * <p>Shared for the reason the occasions screen gives: a field added to one and not the other is
 * how a screen comes to accept a value it will not let you correct. The ids are prefixed because
 * both forms can be mounted at once, and two controls with one id leave a label pointing at
 * whichever the browser saw first.
 */
function MealKindFields({
  idPrefix,
  draft,
  onChange,
  lastPlace,
}: {
  idPrefix: string;
  draft: Draft;
  onChange: (draft: Draft) => void;
  /** Where a blank order would put this kind — shown, rather than left to be discovered. */
  lastPlace: number;
}) {
  return (
    <>
      <Field id={`${idPrefix}-name`} label="Name" required>
        {(props) => (
          <input
            {...props}
            type="text"
            required
            maxLength={80}
            placeholder="Raj Bhog"
            value={draft.name}
            onChange={(e) => onChange({ ...draft, name: e.target.value })}
          />
        )}
      </Field>

      <FieldRow>
        <Field
          id={`${idPrefix}-ready-time`}
          label="Usually ready by"
          hint="The time this meal is normally due. Leave it blank and the planner asks for a time every time — which is what a feast or an event wants."
        >
          {(props) => (
            <input
              {...props}
              type="time"
              value={draft.readyTime}
              onChange={(e) => onChange({ ...draft, readyTime: e.target.value })}
            />
          )}
        </Field>

        <Field
          id={`${idPrefix}-order`}
          label="Order in the day"
          hint="A smaller number comes first, and the temple’s own meals are numbered in tens so there is room to slot one in between. Leave it blank to put this one last."
        >
          {(props) => (
            <input
              {...props}
              type="number"
              inputMode="numeric"
              min={0}
              placeholder={String(lastPlace)}
              value={draft.order}
              onChange={(e) => onChange({ ...draft, order: e.target.value })}
            />
          )}
        </Field>
      </FieldRow>

      {/*
        The two flags, as sentences rather than as field names. Both are on the edit dialog as well
        as on the add form, and they have to be: the PUT writes the whole record, so a dialog that
        did not carry them would clear whichever the kind had set.
      */}
      <fieldset className="grid gap-2">
        <legend className="pl-field-inset text-sm font-medium text-ink">
          What the planner should ask for
        </legend>
        <label className="flex items-baseline gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.isEvent}
            onChange={(e) => onChange({ ...draft, isEvent: e.target.checked })}
            className="mt-1 accent-accent"
          />
          <span>
            <span className="text-ink">This kind of meal is an event</span>{" "}
            <span className="text-ink-muted">
              it is given a name of its own, and asked whether it is going outside the temple
            </span>
          </span>
        </label>
        <label className="flex items-baseline gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.needsOccasion}
            onChange={(e) => onChange({ ...draft, needsOccasion: e.target.checked })}
            className="mt-1 accent-accent"
          />
          <span>
            <span className="text-ink">This kind of meal is a feast</span>{" "}
            <span className="text-ink-muted">
              it must name the festival it is for, such as Janmastami
            </span>
          </span>
        </label>
      </fieldset>
    </>
  );
}

/**
 * Editing one, and saying out loud what a rename does.
 *
 * <p>A rename here is not a cosmetic edit, and this is the one screen where that has to be said. A
 * meal does not reference its kind, it stores the kind's *name*, in three tables — `meal_plans`,
 * `meal_services` and `shifts` — so the server carries a new name across all three (T-038, V96).
 * That is the safe behaviour and it is the server's, not this screen's; what the screen owes the
 * reader is the fact that pressing Save rewrites rows they are not looking at. It is shown only
 * once the name has actually changed, because it is a consequence of what has just been typed
 * rather than standing advice — the same call the occasions screen makes about its leap-day note.
 *
 * <p>Nothing here pre-checks the new name against the others. A duplicate is the server's refusal
 * to make — `MEAL_KIND_ALREADY_EXISTS`, caught off the database's own index so two admins renaming
 * at once cannot slip between a read and a write — and a guard in the browser could only ever
 * disagree with it.
 */
function EditMealKind({
  kind,
  onDone,
  onCancel,
}: {
  kind: MealKindView;
  onDone: (name: string) => void;
  onCancel: () => void;
}) {
  const { getToken } = useAuth();
  const [draft, setDraft] = useState<Draft>(draftOf(kind));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const newName = draft.name.trim();
  // Exactly the comparison the server makes before it cascades: case-sensitive, so correcting
  // "lunch" to "Lunch" counts as a rename — it is what every screen prints, and the stored copies
  // have to print it too.
  const renaming = newName.length > 0 && newName !== kind.name;

  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.updateMealKind(kind.id, inputOf(draft, kind.sortOrder), await getToken());
      onDone(newName);
    } catch (e) {
      setError(toApiError(e, "We couldn’t save that kind of meal."));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="edit-meal-kind-title"
    >
      <div className="modal max-h-[90vh] w-full max-w-prose overflow-y-auto px-8 py-7">
        <h2 id="edit-meal-kind-title" className="text-lg">
          Edit {kind.name}
        </h2>

        <form className="mt-5 grid gap-5" aria-label="Edit a kind of meal" onSubmit={save}>
          <MealKindFields
            idPrefix="edit"
            draft={draft}
            onChange={setDraft}
            lastPlace={kind.sortOrder}
          />

          {/* Visible text and not a hint: it is a consequence of what has just been typed, and a
              reader must not have to go looking for it. */}
          {renaming && (
            <InlineNotice tone="warning" title={`Everything recorded as ${kind.name} is renamed too`}>
              Meals already planned or cooked as {kind.name}, and volunteer shifts posted for it,
              take the name {newName}. Nothing is lost and no history is broken — it all reads as
              {" "}
              {newName} from now on.
            </InlineNotice>
          )}

          {error && <ErrorNotice error={error} />}

          <div className="flex flex-wrap items-center justify-end gap-3">
            <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" busy={busy}>
              Save meal kind
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * Delete, and the refusal it will often meet.
 *
 * <p>The server refuses to remove a kind the temple has ever used — `MEAL_KIND_IN_USE`,
 * `KMS-400126` — because a plan, a recorded meal and a volunteer shift all store the kind by name
 * and four read paths resolve that stored name back again; removing a used kind arms a failure on a
 * job card or a reuse preview months later. So this dialog says what the answer will be *before* the
 * press, and when the refusal comes it is rendered in the server's own words with its own next step,
 * which is "rename it instead".
 *
 * <p>Nothing is pre-checked here. Whether a kind has ever been used is a fact about three tables
 * this screen cannot see, and a browser-side guess at it would be wrong in exactly the case that
 * matters — a kind used by nothing but a linked shift.
 */
function DeleteMealKind({
  kind,
  onDone,
  onCancel,
}: {
  kind: MealKindView;
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
      await api.deleteMealKind(kind.id, await getToken());
      onDone(kind.name);
    } catch (e) {
      setError(toApiError(e, "We couldn’t remove that kind of meal."));
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 px-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-meal-kind-title"
    >
      <div className="modal w-full max-w-prose px-8 py-7">
        <h2 id="delete-meal-kind-title" className="text-lg text-danger">
          Remove {kind.name}?
        </h2>
        <p className="mt-2 text-sm text-ink-secondary">
          The planner stops offering it. This works only for a kind nothing has ever been planned,
          cooked or rostered as — if this temple has used it, we will say so and leave it where it
          is.
        </p>
        <p className="mt-2 text-sm text-ink-secondary">
          If the temple has simply started calling this meal something else, edit it and change the
          name instead. Everything recorded under the old name takes the new one.
        </p>

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
            Remove meal kind
          </Button>
        </div>
      </div>
    </div>
  );
}
