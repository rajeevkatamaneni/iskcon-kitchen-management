"use client";

import { useCallback } from "react";
import { useAuthedQuery } from "@/lib/use-authed-query";
import {
  api,
  type ApiError,
  type PreviousEmploymentView,
  type StaffDocumentView,
  type StaffPayView,
  type StaffProfileView,
} from "@/lib/api";

/**
 * One member of staff, for the four screens that are about one person: their record, updating it,
 * terminating them, and paying them.
 *
 * <p><b>One record, not the whole register.</b> Until T-428 this read every staff record the temple
 * has and picked one out in the browser, because there was no single-record read behind
 * `MANAGE_STAFF` — the one that existed, `getStaffProfile`, sits behind `MANAGE_STAFF_SCHEDULE`, and
 * leaning on it would have made these four screens depend on the roster permission. That split is
 * deliberate: a kitchen manager can be given the roster without being given hiring.
 *
 * <p>So `GET /api/v1/staff/members/{id}` was added behind `MANAGE_STAFF` instead, and it is what this
 * reads. It carries the profile, whether a record stands against them (B9), their documents and
 * their previous jobs — three of which have no business on `StaffProfileView`, which is also served
 * to the roster and is deliberately lean.
 *
 * <p><b>A person who is not there is not an error.</b> The server answers an unknown id with
 * `KMS-400030`, and these screens want to say "there is nobody here" rather than show a red notice.
 * So that one code becomes `staff: null` and everything else stays an error.
 *
 * <p>Pay is fetched beside it because three of the four screens need it — the salary when you update
 * somebody, what they owe when you terminate them, and the whole history when you pay them — and one
 * query serving all of them is one fewer place for the figures to disagree.
 */
export function useStaffRecord(id: string): {
  staff: StaffProfileView | null;
  /** True when this temple has a standing record against them (B9). False for current staff. */
  banned: boolean;
  /** Their photograph and identity documents (T-428). At most one of each kind. */
  documents: StaffDocumentView[];
  /** Where they worked before, in the order the admin entered them (T-428). */
  previousEmployment: PreviousEmploymentView[];
  pay: StaffPayView | null;
  loading: boolean;
  error: ApiError | null;
  /** Re-reads both, for after something has been written. */
  reload: () => void;
} {
  const record = useAuthedQuery(useCallback((t: string | undefined) => api.staffMember(id, t), [id]));
  const pay = useAuthedQuery(useCallback((t: string | undefined) => api.staffPay(id, t), [id]));

  const missing = record.error?.code === NOT_FOUND;

  const reload = useCallback(() => {
    record.reload();
    pay.reload();
  }, [record, pay]);

  return {
    staff: record.data?.profile ?? null,
    banned: record.data?.banned ?? false,
    documents: record.data?.documents ?? [],
    previousEmployment: record.data?.previousEmployment ?? [],
    pay: pay.data,
    // Only the first load is a wait. A reload after a payment leaves the figures on screen rather
    // than replacing the whole record with a spinner.
    loading: (record.loading && !record.data) || (pay.loading && !pay.data),
    // The pay request answers the same KMS-400030 for the same missing person, so it is suppressed
    // with the record's: one unknown id must not put a red notice under "There is nobody here".
    error: missing || pay.error?.code === NOT_FOUND ? null : (record.error ?? pay.error),
    reload,
  };
}

/** `RESOURCE_NOT_FOUND`. Nobody of that id here — which these screens say in words, not in red. */
const NOT_FOUND = "KMS-400030";
