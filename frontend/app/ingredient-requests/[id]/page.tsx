"use client";

import { useCallback, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { Sidebar } from "@/components/Sidebar";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RequireRole } from "@/components/RequireRole";
import { BusyPot, Loading } from "@/components/Loading";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { RequestStatusBadge } from "@/components/IngredientRequestStatus";
import {
  api,
  toApiError,
  type ApiError,
  type IngredientRequestDetail,
  type IngredientRequestEvent,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { cooksQuantity, longDate, unitLabel } from "@/lib/format";

/**
 * One request, everything on it, and only the acts this person may perform in this state (E10-S10).
 *
 * <p>The rules are the API's and it enforces every one of them on every request — this screen is
 * not the security boundary and must not be trusted as one. What it owes the reader is the other
 * half: never offering somebody a button they will only be refused at, and saying plainly when a
 * request has reached a state nobody can act on.
 *
 * <p>Quantities are the cook's figures throughout, not the ledger's. This sheet is walked around a
 * store room by somebody weighing things, and 10.08 Kg and 10 Kg are the same sack of rice.
 */

/** What an event says if the server ever writes one with no sentence of its own. */
const EVENT_LABEL: Record<string, string> = {
  CREATED: "Raised as a draft",
  EDITED: "Edited",
  SUBMITTED: "Sent for review",
  WITHDRAWN: "Withdrawn to a draft",
  APPROVED: "Approved",
  DENIED: "Denied",
  ISSUED: "Issued",
};

export default function IngredientRequestPage() {
  return (
    <RequireRole roles={["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]}>
      <IngredientRequestRecordView />
    </RequireRole>
  );
}

function IngredientRequestRecordView() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { data, error, loading, reload } = useAuthedQuery(
    useCallback((token: string | undefined) => api.getIngredientRequest(id, token), [id])
  );

  return (
    <div className="flex min-h-screen">
      <Sidebar activeHref="/ingredient-requests" />
      <main className="min-w-0 flex-1 px-8 py-10">
        <div className="mx-auto grid max-w-content gap-6">
          {loading ? (
            <Loading label="Loading the request…" />
          ) : error ? (
            <ErrorNotice error={error} />
          ) : data ? (
            <RequestRecord id={id} detail={data} reload={reload} />
          ) : null}
        </div>
      </main>
    </div>
  );
}

function RequestRecord({
  id,
  detail,
  reload,
}: {
  id: string;
  detail: IngredientRequestDetail;
  reload: () => void;
}) {
  const { appUser, getToken } = useAuth();
  const router = useRouter();
  const request = detail.request;

  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<ApiError | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Something this screen refused to send, said in words rather than as a reference code. */
  const [problem, setProblem] = useState<string | null>(null);
  const [decisionNote, setDecisionNote] = useState("");
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // Who this person is to this request. The role decides which kind of person they are and the
  // authorship decides which rows are theirs — the same two layers the API answers in.
  const role = appUser?.role;
  const mayApprove = role === "TEMPLE_ADMIN" || role === "KITCHEN_MANAGER";
  const mayIssue = mayApprove;
  const isAuthor = appUser?.userId === request.requestedBy;
  const status = request.status;

  const draft = status === "DRAFT";
  const awaitingReview = status === "SUBMITTED";
  const approved = status === "APPROVED";
  const closed = status === "DENIED" || status === "ISSUED";

  const mayEdit = (draft && isAuthor) || (awaitingReview && (isAuthor || mayApprove));
  const mayDelete = draft && (isAuthor || mayApprove);
  const maySubmit = draft && isAuthor;
  const mayWithdraw = awaitingReview && (isAuthor || mayApprove);

  async function run(kind: string, act: (token: string | undefined) => Promise<void>, ok: string) {
    setBusy(kind);
    setActionError(null);
    setProblem(null);
    setNotice(null);
    try {
      await act(await getToken());
      setNotice(ok);
      reload();
    } catch (e) {
      setActionError(toApiError(e, "That didn’t work."));
    } finally {
      setBusy(null);
    }
  }

  function submitForReview() {
    // The same two refusals the API makes, made here so nobody loses a click to a reference code.
    if (detail.lines.length === 0) {
      setProblem("This request asks for nothing yet. Add what the kitchen needs from the store.");
      return;
    }
    if (detail.dishes.length === 0) {
      setProblem(
        "Say what you are cooking before you send this for review. An approver reads the list against what it is for, and cannot judge one without the other."
      );
      return;
    }
    run("submitting", (t) => api.submitIngredientRequest(id, t), "Sent for review.");
  }

  async function remove() {
    setBusy("deleting");
    setActionError(null);
    try {
      await api.deleteIngredientRequest(id, await getToken());
      // Nothing to come back to: the request is gone, so the list is the only honest destination.
      router.push(`/ingredient-requests?deleted=${encodeURIComponent(request.reference)}`);
    } catch (e) {
      setActionError(toApiError(e, "We couldn’t delete that request."));
      setConfirmingDelete(false);
      setBusy(null);
    }
  }

  return (
    <>
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="font-mono text-2xl font-semibold text-ink">{request.reference}</h1>
            <RequestStatusBadge status={status} />
          </div>
          <p className="mt-1 text-ink-secondary">
            {request.kitchenName} · needed on {longDate(request.neededOn)} · raised by{" "}
            {request.requestedByName}
          </p>
        </div>

        <div className="flex flex-wrap gap-2">
          {mayEdit && (
            <ButtonLink href={`/ingredient-requests/${id}/edit`} variant="secondary">
              Edit
            </ButtonLink>
          )}
          {mayWithdraw && (
            <Button
              variant="secondary"
              disabled={busy !== null}
              onClick={() =>
                run("withdrawing", (t) => api.withdrawIngredientRequest(id, t), "Back to a draft.")
              }
            >
              Withdraw to a draft
            </Button>
          )}
          {mayDelete && !confirmingDelete && (
            <Button variant="danger" disabled={busy !== null} onClick={() => setConfirmingDelete(true)}>
              Delete
            </Button>
          )}
          {mayDelete && confirmingDelete && (
            <>
              <Button variant="secondary" disabled={busy !== null} onClick={() => setConfirmingDelete(false)}>
                Keep it
              </Button>
              <Button variant="danger" disabled={busy !== null} onClick={remove}>
                Delete for good
              </Button>
            </>
          )}
          {maySubmit && (
            <Button disabled={busy !== null} onClick={submitForReview}>
              {busy === "submitting" ? (
                <span className="inline-flex items-center gap-2">
                  <BusyPot />
                  Sending…
                </span>
              ) : (
                "Submit for review"
              )}
            </Button>
          )}
        </div>
      </header>

      {notice && <InlineNotice tone="success" autoDismiss title={notice} />}
      {problem && <InlineNotice tone="warning" title={problem} />}
      {actionError && <ErrorNotice error={actionError} />}

      {/* What this state means for whoever is reading, before they go looking for a button. */}
      {draft && !isAuthor && (
        <InlineNotice title="This is somebody else’s draft.">
          {mayApprove
            ? "You can read it and you can delete it, but only the person who raised it can send it for review."
            : "Only the person who raised it can change it or send it for review."}
        </InlineNotice>
      )}
      {awaitingReview && !isAuthor && !mayApprove && (
        <InlineNotice title="This request is waiting for an answer.">
          An administrator or a kitchen manager decides it.
        </InlineNotice>
      )}
      {status === "DENIED" && (
        <InlineNotice tone="warning" title="This request was denied, and that is final.">
          A refusal that could be edited and shown again would not be a refusal. Raise a fresh
          request if the kitchen still needs something, and this one stays on the record with the
          reason.
        </InlineNotice>
      )}
      {status === "ISSUED" && (
        <InlineNotice tone="success" title="The goods have gone over the counter.">
          The stock has been drawn down against this request, so nothing on it can change now.
        </InlineNotice>
      )}

      {request.purpose && (
        <Card title="Reason">
          <p className="max-w-prose whitespace-pre-line">{request.purpose}</p>
        </Card>
      )}

      {awaitingReview && mayApprove && (
        <Card title="Your answer">
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Note (optional)</span>
            <textarea
              value={decisionNote}
              onChange={(e) => setDecisionNote(e.target.value)}
              rows={2}
              placeholder="Take the sunflower oil from the opened tin."
              className="rounded border border-hairline bg-raised px-3 py-2"
            />
          </label>
          {/* Outside the label: a label's accessible name is everything it contains, and a
              sentence of explanation would become the name of the field. */}
          <p className="pl-field-inset mt-1 text-sm text-ink-secondary">
            Whatever you write is kept on the record and read by whoever asks about this later.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button
              disabled={busy !== null}
              onClick={() =>
                run(
                  "approving",
                  (t) => api.approveIngredientRequest(id, decisionNote.trim() || null, t),
                  "Approved. The store can issue against it now."
                )
              }
            >
              Approve
            </Button>
            <Button
              variant="danger"
              disabled={busy !== null}
              onClick={() =>
                run(
                  "denying",
                  (t) => api.denyIngredientRequest(id, decisionNote.trim() || null, t),
                  "Denied. The reason is on the record."
                )
              }
            >
              Deny
            </Button>
          </div>
        </Card>
      )}

      <Card title="What was asked for" padding="p-0">
        {detail.lines.length === 0 ? (
          <p className="px-6 py-8 text-center text-ink-secondary">
            Nothing has been added to this request yet.
          </p>
        ) : (
          <table className="w-full text-left">
            <thead className="bg-sunken text-sm text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Ingredient</th>
                <th className="px-5 py-3 font-medium">Asked for</th>
                <th className="px-5 py-3 font-medium">Handed over</th>
              </tr>
            </thead>
            <tbody>
              {detail.lines.map((line) => (
                <tr key={line.id} className="border-t border-hairline hover:bg-sunken">
                  <td className="px-5 py-4">{line.ingredientName}</td>
                  <td className="px-5 py-4 text-ink-secondary">
                    {cooksQuantity(line.quantity, line.unit)}
                  </td>
                  <td className="px-5 py-4 text-ink-secondary">
                    {line.issuedQuantity == null
                      ? "—"
                      : cooksQuantity(line.issuedQuantity, line.issuedUnit ?? line.unit)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="What it is for" padding="p-0">
        {detail.dishes.length === 0 ? (
          <p className="px-6 py-8 text-center text-ink-secondary">
            No dishes named yet. A request cannot go for review without them.
          </p>
        ) : (
          <table className="w-full text-left">
            <thead className="bg-sunken text-sm text-ink-secondary">
              <tr>
                <th className="px-5 py-3 font-medium">Dish</th>
                <th className="px-5 py-3 font-medium">How much</th>
              </tr>
            </thead>
            <tbody>
              {detail.dishes.map((dish) => (
                <tr key={dish.id} className="border-t border-hairline hover:bg-sunken">
                  <td className="px-5 py-4">{dish.dishName}</td>
                  <td className="px-5 py-4 text-ink-secondary">
                    {cooksQuantity(dish.quantity, dish.unit)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {approved && (
        <Card title="The work order">
          <p className="max-w-prose text-ink-secondary">
            The sheet that names what to pick, which lot to pick it from and who signs for it is not
            built yet. It arrives with E10-S11 and will download from here.
          </p>
          <div className="mt-4">
            <Button variant="secondary" disabled>
              Download work order
            </Button>
          </div>
        </Card>
      )}

      {approved && mayIssue && (
        <RecordIssue id={id} detail={detail} reload={reload} />
      )}
      {approved && !mayIssue && (
        <InlineNotice title="Approved, and waiting on the store.">
          An administrator or a kitchen manager records what actually goes over the counter, and
          that is the moment the stock falls.
        </InlineNotice>
      )}

      <Card title="What has happened to it">
        <ol className="grid gap-3">
          {detail.events.map((event) => (
            <li key={event.id} className="border-l-2 border-hairline pl-4">
              <p>{sentence(event)}</p>
            </li>
          ))}
        </ol>
      </Card>
    </>
  );
}

/** One event as a line somebody can read: what happened, who did it, and when. */
function sentence(event: IngredientRequestEvent): string {
  const what = event.detail ?? EVENT_LABEL[event.eventType] ?? "Updated";
  const who = event.actorName ?? "Somebody no longer at this temple";
  return `${what} — ${who}, ${when(event.at)}`;
}

function when(iso: string): string {
  return new Date(iso).toLocaleString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * Recording what the store actually handed over — the one act on this screen that moves stock.
 *
 * <p>Every line is pre-filled with the quantity that was approved, because the storekeeper who
 * checked the sheet and handed it all over wants to press one button. Zero is a legitimate answer
 * and means nothing went over the counter for that line, which is a fact worth keeping.
 *
 * <p>All or nothing, by design. If any line is short the whole issue is refused and no stock moves
 * at all — allowing it would drive the books negative, which the inventory service forbids
 * outright. A store whose books say 2 Kg while its shelf holds 20 Kg has a counting problem, and
 * the fix is a count correction before the issue is recorded.
 */
function RecordIssue({
  id,
  detail,
  reload,
}: {
  id: string;
  detail: IngredientRequestDetail;
  reload: () => void;
}) {
  const { getToken } = useAuth();
  const [amounts, setAmounts] = useState<Record<string, string>>(() =>
    Object.fromEntries(detail.lines.map((l) => [l.id, String(l.quantity)]))
  );
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const shortOfStock = error?.code === "KMS-4911";

  async function record() {
    const lines = detail.lines.map((l) => ({
      lineId: l.id,
      quantity: Number(amounts[l.id]),
      unit: l.unit,
    }));
    if (lines.some((l) => !Number.isFinite(l.quantity) || l.quantity < 0)) {
      setProblem("Put a figure against every line. Zero is a fine answer where nothing went out.");
      return;
    }
    setBusy(true);
    setError(null);
    setProblem(null);
    try {
      await api.recordIngredientIssue(id, { lines, note: note.trim() || null }, await getToken());
      reload();
    } catch (e) {
      setError(toApiError(e, "We couldn’t record that issue."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Record what was issued">
      <p className="mb-4 max-w-prose text-ink-secondary">
        Each line starts at the quantity that was approved. Change anything that went out short, and
        put zero against anything that did not go out at all.
      </p>

      {problem && (
        <div className="mb-4">
          <InlineNotice tone="warning" title={problem} />
        </div>
      )}

      {error && (
        <div className="mb-4 grid gap-3">
          <ErrorNotice error={error} />
          {shortOfStock && (
            <InlineNotice tone="warning" title="Nothing was issued.">
              <p>
                The whole request goes over the counter together or not at all, so no stock has
                moved and the books are unchanged.
              </p>
              {error.fieldErrors.length > 0 && (
                <ul className="mt-2 grid gap-1">
                  {error.fieldErrors.map((f) => (
                    <li key={f.field}>
                      {f.field}: {f.message}
                    </li>
                  ))}
                </ul>
              )}
              <p className="mt-2">
                If the shelf holds more than the books say, correct the count on the inventory
                screen first, then record the issue again.
              </p>
            </InlineNotice>
          )}
        </div>
      )}

      <div className="overflow-hidden rounded-lg border border-hairline">
        <table className="w-full text-left">
          <thead className="bg-sunken text-sm text-ink-secondary">
            <tr>
              <th className="px-5 py-3 font-medium">Ingredient</th>
              <th className="px-5 py-3 font-medium">Approved</th>
              <th className="px-5 py-3 font-medium">Actually issued</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.map((line) => (
              <tr key={line.id} className="border-t border-hairline hover:bg-sunken">
                <td className="px-5 py-3">{line.ingredientName}</td>
                <td className="px-5 py-3 text-ink-secondary">
                  {cooksQuantity(line.quantity, line.unit)}
                </td>
                <td className="px-5 py-3">
                  <span className="flex items-center gap-2">
                    <input
                      aria-label={`Issued ${line.ingredientName}`}
                      type="number"
                      min="0"
                      step="any"
                      value={amounts[line.id] ?? ""}
                      onChange={(e) =>
                        setAmounts((prev) => ({ ...prev, [line.id]: e.target.value }))
                      }
                      className="min-h-touch w-28 rounded border border-hairline bg-raised px-3"
                    />
                    {/* The stored unit, never the promoted one: the box submits kilograms, and
                        labelling it grams would invite a thousandfold error. */}
                    <span className="text-ink-secondary">{unitLabel(line.unit)}</span>
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <label className="mt-5 flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Note (optional)</span>
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Collected by Gopal at the back door."
          className="min-h-touch rounded border border-hairline bg-raised px-3"
        />
      </label>

      <div className="mt-4">
        <Button disabled={busy} onClick={record}>
          {busy ? (
            <span className="inline-flex items-center gap-2">
              <BusyPot />
              Recording…
            </span>
          ) : (
            "Record the issue"
          )}
        </Button>
      </div>
    </Card>
  );
}
