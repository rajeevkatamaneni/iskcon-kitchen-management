"use client";

import { useCallback, useMemo } from "react";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, type ApiError, type StaffPayView, type StaffProfileView } from "@/lib/api";

/**
 * One member of staff, for the four screens that are about one person: their record, updating it,
 * terminating them, and paying them.
 *
 * <p><b>The register, not `getStaffProfile`.</b> That endpoint sits behind `MANAGE_STAFF_SCHEDULE`
 * and would make every one of these screens depend on a second permission it has no other use for —
 * a temple administrator holds both today, but the split exists so that a kitchen manager can be
 * given the roster without being given hiring. The register is also what tells us that an id belongs
 * to nobody at all, which is a thing these screens have to be able to say.
 *
 * <p>Pay is fetched beside it because three of the four screens need it — the salary when you update
 * somebody, what they owe when you terminate them, and the whole history when you pay them — and one
 * query serving all of them is one fewer place for the figures to disagree.
 */
export function useStaffRecord(id: string): {
  staff: StaffProfileView | null;
  /** True when this temple has a standing record against them (B9). False for current staff. */
  banned: boolean;
  pay: StaffPayView | null;
  loading: boolean;
  error: ApiError | null;
  /** Re-reads both, for after something has been written. */
  reload: () => void;
} {
  const register = useAuthedQuery(useCallback((t: string | undefined) => api.staffRegister(t), []));
  const pay = useAuthedQuery(useCallback((t: string | undefined) => api.staffPay(id, t), [id]));

  const staff = useMemo(() => {
    const everyone = [
      ...(register.data?.current ?? []),
      ...(register.data?.former ?? []).map((f) => f.profile),
    ];
    return everyone.find((s) => s.id === id) ?? null;
  }, [register.data, id]);

  const banned = useMemo(
    () => (register.data?.former ?? []).some((f) => f.profile.id === id && f.banned),
    [register.data, id]
  );

  const reload = useCallback(() => {
    register.reload();
    pay.reload();
  }, [register, pay]);

  return {
    staff,
    banned,
    pay: pay.data,
    // Only the first load is a wait. A reload after a payment leaves the figures on screen rather
    // than replacing the whole record with a spinner.
    loading: (register.loading && !register.data) || (pay.loading && !pay.data),
    error: pay.error ?? register.error,
    reload,
  };
}
