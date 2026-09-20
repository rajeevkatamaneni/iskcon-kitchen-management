"use client";

import { jobSpan } from "./labels";
import type { PreviousEmploymentView } from "@/lib/api";

/**
 * Where somebody worked before this temple, read (T-428).
 *
 * <p>One block per job, in the order the admin entered them — not sorted by date, because most of
 * these rows will have no dates at all and a sort that silently reorders half the list is worse than
 * no sort.
 *
 * <p>Each block leads with the employer and what they were called there, because that is what
 * somebody scanning the record is looking for. The manager and the number are under it: they are
 * what you read when you have decided to telephone, not before.
 *
 * <p>Nothing here is verified and the heading of the section does not pretend it is. It is what the
 * person said at their interview.
 */
export function PreviousEmployment({ jobs }: { jobs: PreviousEmploymentView[] }) {
  if (jobs.length === 0) {
    return <p className="text-sm text-ink-muted">No previous jobs recorded.</p>;
  }

  return (
    <ol className="grid gap-4">
      {jobs.map((job) => {
        const span = jobSpan(job);
        return (
          <li key={job.id} className="grid gap-1 border-l-2 border-hairline pl-4 text-sm">
            <p className="font-medium text-ink [overflow-wrap:anywhere]">
              {job.employer}
              {job.theirTitle && <span className="font-normal text-ink-secondary"> · {job.theirTitle}</span>}
            </p>
            {span && <p className="text-ink-secondary">{span}</p>}
            {job.managerName && (
              <p className="text-ink-secondary [overflow-wrap:anywhere]">
                Manager: {job.managerName}
                {job.managerPhone && <span className="tabular-nums"> · {job.managerPhone}</span>}
              </p>
            )}
            {/* A manager's number with no name still belongs on the record: it is the thing you
                would actually dial, and dropping it because the name is missing would lose it. */}
            {!job.managerName && job.managerPhone && (
              <p className="text-ink-secondary">
                Manager: <span className="tabular-nums">{job.managerPhone}</span>
              </p>
            )}
            {job.reasonForLeaving && (
              <p className="text-ink-secondary [overflow-wrap:anywhere]">Left: {job.reasonForLeaving}</p>
            )}
          </li>
        );
      })}
    </ol>
  );
}
