"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
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
import { cooksQuantity, longDate, moment, stepForUnit, unitLabel } from "@/lib/format";
import { wholeNumberProblem } from "@/components/ds/formMessages";
import { ALL_LANGUAGES, ENGLISH } from "@/lib/languages";
import { generateAndDownload } from "@/lib/document-download";
import { RULED_TABLE, THEAD, TR, TH_PRIMARY, TD_PRIMARY, TH_FIXED, TD_FIXED_NUM } from "@/components/ds/table";

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
/**
 * A titled card whose table runs to its edges. `p-0` alone left the card's own title flush against
 * the border, 0px in where every other card's title sits 24px in; this pads the title only.
 */
const BLEED_WITH_TITLE = "p-0 [&>header]:px-6 [&>header]:pt-6";

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
      {/* useSearchParams, for the confirmation "New request" comes back with (T-370). */}
      <Suspense>
        <IngredientRequestRecordView />
      </Suspense>
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
      <main className="min-w-0 flex-1 px-4 py-10 sm:px-8">
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

  /**
   * The confirmation "New request" arrives with (T-370, staging defect 3).
   *
   * <p>Submitting a new request created it and then left the person on the emptied form with nothing
   * on the screen saying so — no confirmation, no reference, no sign that `IR-2026-0003` now existed.
   * A create ends on the thing it created everywhere else in this application, and says what
   * happened when it gets there; this is that ending. The sentence goes into the same `notice` the
   * page's own acts use, so there is one confirmation line on this screen and not two kinds.
   *
   * <p>Its words are the page's own: "Sent for review." and "Back to a draft." are what withdrawing
   * and submitting already say here, so a create says the same thing in the same shape. The
   * reference is not repeated in it — it is the h1, two lines above.
   *
   * <p><strong>The ref guards the capture.</strong> Setting state re-renders, and `router` is a new
   * object on each render, so without it this effect re-runs for ever — the repo's known
   * flash-capture loop, which takes a vitest run out of memory. Read once, then the address is
   * cleared so a reload does not say it again.
   */
  const search = useSearchParams();
  const created = search.get("created");
  const captured = useRef(false);
  useEffect(() => {
    if (captured.current || !created) return;
    captured.current = true;
    setNotice(created === "submitted" ? "Created and sent for review." : "Saved as a draft.");
    router.replace(`/ingredient-requests/${id}`);
  }, [created, router, id]);

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
            <Button disabled={busy !== null} onClick={submitForReview} busy={busy === "submitting"}>
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
      {/* Red, like a field error: the request cannot go until this is fixed (T-227). */}
      {problem && <InlineNotice tone="danger" title={problem} />}
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
      {/* Both of these explain a settled request, shown every time it is opened, so both are
          information: nothing to act on, and not the moment the reader's own action succeeded
          (Rajeev, 2026-09-18, T-227). The issue itself has its own green flash. */}
      {status === "DENIED" && (
        <InlineNotice tone="info" title="This request was denied, and that is final.">
          A refusal that could be edited and shown again would not be a refusal. Raise a fresh
          request if the kitchen still needs something, and this one stays on the record with the
          reason.
        </InlineNotice>
      )}
      {status === "ISSUED" && (
        <InlineNotice tone="info" title="The goods have gone over the counter.">
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
              className="rounded-control border border-hairline px-3 py-2"
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

      <Card title="What was asked for" padding={BLEED_WITH_TITLE}>
        {detail.lines.length === 0 ? (
          <p className="px-6 py-8 text-center text-ink-secondary">
            Nothing has been added to this request yet.
          </p>
        ) : (
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Ingredient</th>
                <th className={TH_FIXED}>Asked for</th>
                <th className={TH_FIXED}>Handed over</th>
              </tr>
            </thead>
            <tbody>
              {detail.lines.map((line) => (
                <tr key={line.id} className={TR}>
                  <td className={TD_PRIMARY}>{line.ingredientName}</td>
                  <td data-label="Asked for" className={`${TD_FIXED_NUM} text-ink-secondary`}>
                    {cooksQuantity(line.quantity, line.unit)}
                  </td>
                  <td data-label="Handed over" className={`${TD_FIXED_NUM} text-ink-secondary`}>
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

      <Card title="What it is for" padding={BLEED_WITH_TITLE}>
        {detail.dishes.length === 0 ? (
          <p className="px-6 py-8 text-center text-ink-secondary">
            No dishes named yet. A request cannot go for review without them.
          </p>
        ) : (
          <table className={RULED_TABLE}>
            <thead className={THEAD}>
              <tr>
                <th className={TH_PRIMARY}>Dish</th>
                <th className={TH_FIXED}>How much</th>
              </tr>
            </thead>
            <tbody>
              {detail.dishes.map((dish) => (
                <tr key={dish.id} className={TR}>
                  <td className={TD_PRIMARY}>{dish.dishName}</td>
                  <td data-label="How much" className={`${TD_FIXED_NUM} text-ink-secondary`}>
                    {cooksQuantity(dish.quantity, dish.unit)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {/* Only an issuer is offered the sheet. Every endpoint behind this card — the language
          list it opens with included — is guarded by ISSUE_INGREDIENTS, which kitchen staff do
          not hold, so shown to a cook the card 403s on mount before a button is pressed and then
          refuses both of them. That is the exact thing the note at the top of this file says this
          screen must not do. The card is a reading act in spirit, but it is not one in the API,
          and the screen follows the API rather than the intention.

          Nothing is hidden by hiding it: what was issued is on the lines above, and the notice
          below tells a cook who is walking the store room with the sheet. */}
      {(approved || status === "ISSUED") && mayIssue && (
        <WorkOrder requestId={id} reference={detail.request.reference} />
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
  return `${what} — ${who}, ${moment(event.at)}`;
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
  /** Nothing is said under a box until Record has been pressed, as every other form here behaves. */
  const [tried, setTried] = useState(false);

  /*
   * A line issued in a counted unit goes out as whole things (T-424). A line already on file
   * holding a fraction — an approved 1.5 aprons from before this rule — still opens showing 1.5,
   * and is refused on the press rather than rounded behind the storekeeper's back.
   */
  function wholeProblem(line: IngredientRequestDetail["lines"][number]): string | null {
    return wholeNumberProblem(`Issued ${line.ingredientName}`, stepForUnit(line.unit), amounts[line.id]);
  }
  const anyWholeProblem = detail.lines.some((l) => wholeProblem(l) !== null);

  const shortOfStock = error?.code === "KMS-400042";

  async function record() {
    setTried(true);
    if (anyWholeProblem) return;
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

      {/* Red, like a field error: nothing can be recorded until every line has a figure (T-227). */}
      {problem && (
        <div className="mb-4">
          <InlineNotice tone="danger" title={problem} />
        </div>
      )}

      {/* A refusal for short stock is one red message, not a red refusal with an amber note under
          it: "nothing was issued" is the refusal, explained, and it needs dealing with now
          (Rajeev, 2026-09-18, T-227). Drawn as ErrorNotice draws, with the explanation and the
          short lines between the refusal and its reference code. Every other refusal is the plain
          ErrorNotice. */}
      {error && !shortOfStock && (
        <div className="mb-4">
          <ErrorNotice error={error} />
        </div>
      )}
      {error && shortOfStock && (
        <div role="alert" className="mb-4 rounded border border-danger bg-danger-bg p-4 text-danger">
          <p className="font-medium">Nothing was issued. {error.message}</p>
          <p className="mt-1 text-sm">
            The whole request goes over the counter together or not at all, so no stock has moved
            and the books are unchanged.
          </p>
          {error.fieldErrors.length > 0 && (
            <ul className="mt-2 grid gap-1 text-sm">
              {error.fieldErrors.map((f) => (
                <li key={f.field}>
                  {f.field}: {f.message}
                </li>
              ))}
            </ul>
          )}
          <p className="mt-2 text-sm">
            If the shelf holds more than the books say, correct the count on the inventory screen
            first, then record the issue again.
          </p>
          <p className="mt-1 text-sm">{error.action}</p>
          <p className="mt-3 text-xs text-danger">
            If you need help, quote <span className="font-mono font-medium">{error.code}</span>
          </p>
        </div>
      )}

      <div className="overflow-x-auto rounded-lg border border-hairline">
        <table className={RULED_TABLE}>
          <thead className={THEAD}>
            <tr>
              <th className={TH_PRIMARY}>Ingredient</th>
              <th className={TH_FIXED}>Approved</th>
              <th className={TH_FIXED}>Actually issued</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.map((line) => (
              <tr key={line.id} className={TR}>
                <td className={TD_PRIMARY}>{line.ingredientName}</td>
                <td data-label="Approved" className={`${TD_FIXED_NUM} text-ink-secondary`}>
                  {cooksQuantity(line.quantity, line.unit)}
                </td>
                <td className={TD_FIXED_NUM}>
                  <span className="flex items-center gap-2">
                    <input
                      aria-label={`Issued ${line.ingredientName}`}
                      type="number"
                      min="0"
                      step={stepForUnit(line.unit)}
                      aria-invalid={tried && wholeProblem(line) ? true : undefined}
                      value={amounts[line.id] ?? ""}
                      onChange={(e) =>
                        setAmounts((prev) => ({ ...prev, [line.id]: e.target.value }))
                      }
                      className="min-h-touch min-w-28 rounded-control border border-hairline px-3"
                    />
                    {/* The stored unit, never the promoted one: the box submits kilograms, and
                        labelling it grams would invite a thousandfold error. */}
                    <span className="text-ink-secondary">{unitLabel(line.unit)}</span>
                  </span>
                  {tried && wholeProblem(line) && (
                    <span className="mt-1 block text-xs text-danger">{wholeProblem(line)}</span>
                  )}
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
          className="min-h-touch rounded-control border border-hairline px-3"
        />
      </label>

      <div className="mt-4">
        <Button disabled={busy} onClick={record} busy={busy}>
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

/**
 * The sheet the storekeeper carries round the store room.
 *
 * <p>Both ways out of one control, as the job card settled it (P4): the browser's own print view is
 * instant and works when the worker is down, and the PDF is versioned and downloadable. The picker
 * offers all 23 languages from the client's own list rather than the server's answer, so a slow or
 * failed call cannot quietly shrink it to English — the server is asked only which one to open on.
 *
 * <p>The print view needs an Authorization header, so it cannot be a plain link: it is fetched and
 * written into a new window, the way the purchase-order page does it.
 *
 * <p>Rendered for an issuer only. Every call it makes needs `ISSUE_INGREDIENTS`, the language list
 * on mount first of all, so the caller gates it rather than the component offering a picker that
 * will be refused before anybody has chosen anything.
 */
function WorkOrder({ requestId, reference }: { requestId: string; reference: string }) {
  const { getToken } = useAuth();
  const languages = useAuthedQuery(useCallback((t?: string) => api.workOrderLanguages(t), []));
  const [language, setLanguage] = useState<string | null>(null);
  const [busy, setBusy] = useState<"pdf" | "print" | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const chosen = language ?? languages.data?.defaultLanguage ?? ENGLISH.code;

  async function download() {
    setBusy("pdf");
    setError(null);
    try {
      const token = await getToken();
      await generateAndDownload({
        request: () => api.requestWorkOrder(requestId, chosen, token),
        status: (documentId) => api.getWorkOrderDocument(documentId, token),
        download: (documentId) => api.downloadWorkOrderDocument(documentId, token),
        filename: `${reference}.pdf`,
      });
    } catch (e) {
      setError(toApiError(e, "We couldn't produce that work order."));
    } finally {
      setBusy(null);
    }
  }

  async function print() {
    setBusy("print");
    setError(null);
    try {
      const token = await getToken();
      const response = await fetch(api.workOrderPrintUrl(requestId, chosen), {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      });
      if (!response.ok) {
        throw new Error("print");
      }
      const html = await response.text();
      const window_ = window.open("", "_blank");
      if (window_) {
        window_.document.write(html);
        window_.document.close();
      }
    } catch {
      setError(
        toApiError(null, "We couldn't open that work order.")
      );
    } finally {
      setBusy(null);
    }
  }

  return (
    <Card title="The work order">
      <p className="max-w-prose text-ink-secondary">
        What to pick, which lot to pick it from, and two boxes to sign. The lots are worked out when
        you print it, not when it was approved, so it always names what is on the shelf today.
      </p>

      {error && (
        <div className="mt-4">
          <ErrorNotice error={error} />
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Language</span>
          <select
            aria-label="Language"
            value={chosen}
            onChange={(e) => setLanguage(e.target.value)}
            className="min-h-touch rounded-control border border-hairline px-3"
          >
            {ALL_LANGUAGES.map((l) => (
              <option key={l.code} value={l.code}>
                {l.label}
              </option>
            ))}
          </select>
        </label>

        <Button onClick={download} disabled={busy !== null} busy={busy === "pdf"}>
          {busy === "pdf" ? (
            <span className="inline-flex items-center gap-2">
              <BusyPot />
              Preparing…
            </span>
          ) : (
            "Download work order"
          )}
        </Button>

        <Button variant="secondary" onClick={print} disabled={busy !== null}>
          {busy === "print" ? "Opening…" : "Print"}
        </Button>
      </div>
    </Card>
  );
}
