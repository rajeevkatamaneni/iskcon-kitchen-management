"use client";

import { useState } from "react";

import { Button } from "@/components/ds/Button";
import type { PreviousEmploymentInput, PreviousEmploymentView } from "@/lib/api";
import { normalizePhone } from "@/lib/phone";

/**
 * Where somebody worked before this temple, on the edit screen (T-428). Rajeev, 2026-09-20:
 * "employer, their title there, manager's name, manager's phone number, from date, to date, reason
 * for leaving".
 *
 * <h3>Several jobs, added and removed on the spot</h3>
 *
 * <p>One bordered block per job, the way the emergency contact is one bordered block, with *Remove*
 * on the block it removes and one *Add a job* under them all. A record with none shows no blocks at
 * all and just the button — an empty block on every record would read as a job the temple failed to
 * fill in.
 *
 * <h3>How it is read back</h3>
 *
 * <p>Every block writes the same field names, so the browser hands them back as parallel lists in
 * document order and {@link readPreviousEmployment} zips them up. That is what makes adding and
 * removing blocks free: there is no index baked into a name that has to be renumbered when the
 * middle one goes.
 *
 * <p>The whole list is sent on every Save and the server replaces what it holds, which is the same
 * shape as the screen: the admin sees all the jobs at once and edits them together.
 */
export function PreviousEmploymentFields({ jobs }: { jobs: PreviousEmploymentView[] }) {
  // Keys, not values. Each block is uncontrolled — `defaultValue` from the stored job, and the
  // browser keeps what is typed — so re-rendering to add or remove a block must not disturb what is
  // in the others, and a stable key per block is what guarantees that.
  const [blocks, setBlocks] = useState<{ key: string; job: PreviousEmploymentView | null }[]>(() =>
    jobs.map((job, i) => ({ key: `stored-${job.id ?? i}`, job })),
  );
  const [added, setAdded] = useState(0);

  function add() {
    const key = `new-${added}`;
    setAdded(added + 1);
    setBlocks((current) => [...current, { key, job: null }]);
  }

  return (
    <div className="col-span-full grid gap-4">
      <div>
        <h2 className="text-lg">Previous employment</h2>
        <p className="mt-1 text-sm text-ink-secondary">
          What they told you at their interview. Only the employer is needed.
        </p>
      </div>

      {blocks.map(({ key, job }) => (
        <fieldset
          key={key}
          data-previous-job=""
          // Two columns and not three. Seven fields divide into four sensible pairs at two —
          // employer and title, the manager and their number, the two dates, then the reason across
          // both — and at three the last row would be one box with two empty columns beside it,
          // which is the dead space the layout rule is about. One column below the sm breakpoint.
          className="grid grid-cols-1 gap-4 rounded border border-hairline px-4 pb-3 pt-1 sm:grid-cols-2"
        >
          <legend className="flex items-center gap-3 px-1 text-sm text-ink-secondary">
            <span>Job</span>
            {/* On the block it removes, not in a list of buttons somewhere else: there is never a
                question about which job is about to go. */}
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setBlocks((current) => current.filter((b) => b.key !== key))}
            >
              Remove
            </Button>
          </legend>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Employer</span>
            <input name="prevEmployer" required defaultValue={job?.employer ?? ""} className={FIELD} />
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Their title there</span>
            <input name="prevTheirTitle" defaultValue={job?.theirTitle ?? ""} className={FIELD} />
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Manager’s name</span>
            <input name="prevManagerName" defaultValue={job?.managerName ?? ""} className={FIELD} />
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">Manager’s phone</span>
            <input
              name="prevManagerPhone"
              placeholder="+919876543210"
              defaultValue={job?.managerPhone ?? ""}
              className={FIELD}
            />
          </label>

          {/* Two cells of the parent grid rather than a pair nested inside one cell. Nested, the
              two date boxes would have shared a single column and each been about 150px wide at
              1280 — a native date picker is barely that, and narrower again on a phone. */}
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">From</span>
            <input name="prevFromDate" type="date" defaultValue={job?.fromDate ?? ""} className={FIELD} />
          </label>
          <label className="flex flex-col gap-1 text-sm text-ink-secondary">
            <span className="pl-field-inset font-medium text-ink">To</span>
            <input name="prevToDate" type="date" defaultValue={job?.toDate ?? ""} className={FIELD} />
          </label>

          <label className="flex flex-col gap-1 text-sm text-ink-secondary sm:col-span-2">
            <span className="pl-field-inset font-medium text-ink">Why they left</span>
            <input
              name="prevReasonForLeaving"
              defaultValue={job?.reasonForLeaving ?? ""}
              className={FIELD}
            />
          </label>
        </fieldset>
      ))}

      <div>
        <Button variant="secondary" onClick={add}>
          Add a job
        </Button>
      </div>
    </div>
  );
}

const FIELD = "min-h-touch rounded-control border border-hairline px-3";

/**
 * The jobs out of the form, in the order they are drawn.
 *
 * <p>Each block writes one value under each name, so the lists are the same length and the nth of
 * each belongs to the nth job. A block whose employer is blank is dropped here as the twin guard:
 * the box is `required`, so `Form` refuses the Save and says "Employer is required" beside it long
 * before this runs — but a reader that would happily send an empty job if that check ever moved is
 * not a reader worth having.
 */
export function readPreviousEmployment(f: FormData): PreviousEmploymentInput[] {
  const employers = f.getAll("prevEmployer").map(String);
  const titles = f.getAll("prevTheirTitle").map(String);
  const managers = f.getAll("prevManagerName").map(String);
  const phones = f.getAll("prevManagerPhone").map(String);
  const from = f.getAll("prevFromDate").map(String);
  const to = f.getAll("prevToDate").map(String);
  const reasons = f.getAll("prevReasonForLeaving").map(String);

  const jobs: PreviousEmploymentInput[] = [];
  for (let i = 0; i < employers.length; i++) {
    const employer = employers[i].trim();
    if (employer === "") continue;
    jobs.push({
      employer,
      theirTitle: blankToNull(titles[i]),
      managerName: blankToNull(managers[i]),
      // The same normalisation every other phone number here gets (T-157), so a number typed with
      // spaces is stored in the one shape the rest of the application uses.
      managerPhone: blankToNull(normalizePhone(phones[i] ?? "")),
      fromDate: blankToNull(from[i]),
      toDate: blankToNull(to[i]),
      reasonForLeaving: blankToNull(reasons[i]),
    });
  }
  return jobs;
}

function blankToNull(s: string | undefined): string | null {
  const t = (s ?? "").trim();
  return t === "" ? null : t;
}
