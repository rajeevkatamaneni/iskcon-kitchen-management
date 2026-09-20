"use client";

import { useCallback, useState } from "react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Button } from "@/components/ds/Button";
import { Card } from "@/components/ds/Card";
import { useAuth } from "@/lib/auth-context";
import { useAuthedQuery } from "@/lib/use-authed-query";
import { api, toApiError, type ApiError, type Kitchen } from "@/lib/api";

/**
 * "Check these kitchen assignments" (Epic 12): the Temple Admin's one look at the kitchens the
 * migration guessed.
 *
 * <p>Rajeev, 2026-09-19: "existing staff are put in the main kitchen when the change ships, and the
 * Temple Admin gets a 'check these kitchen assignments' list to move anyone." The server's list is
 * exactly the current staff whose kitchen was filled in that way and has not been looked at since.
 * Moving somebody here, confirming the rest with "These are right", or saving their record anywhere
 * else all mark it looked at, and the row does not come back.
 *
 * <p>So this draws nothing at all unless the server returns rows: a temple that never had staff
 * before Epic 12, or an admin who has already been through them, has nothing to check and is shown
 * no heading over an empty list. For the same reason a failure to <em>load</em> the list draws
 * nothing — it is a one-off prompt beside the register, not the register, and a red box on every
 * visit to Staff would outweigh what it is asking. A failure to <em>save</em> a move or a
 * confirmation is different: the admin just pressed something, so it is said, in the application's
 * usual {@link ErrorNotice}.
 *
 * <p>A row leaves as soon as its move is saved, rather than after the list is fetched again: the
 * answer is already known, and the next row should be where the eye already is. `onChanged` lets the
 * register beside it repaint the moved person's kitchen.
 *
 * <p>Mounted by `/staff` for the Temple Admin only, which is also the only role that page admits.
 */
export function KitchenChecks({ kitchens, onChanged }: { kitchens: Kitchen[]; onChanged: () => void }) {
  const { getToken } = useAuth();
  const checks = useAuthedQuery(useCallback((t: string | undefined) => api.staffKitchenChecks(t), []));

  // The people dealt with on this visit, by staff id. Filtering by them is what makes a row leave.
  const [settled, setSettled] = useState<ReadonlySet<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const rows = (checks.data ?? []).filter((r) => !settled.has(r.staffId));
  const active = kitchens.filter((k) => k.status === "ACTIVE");

  if (rows.length === 0) return null;

  async function settle(ids: string[], act: (token: string | undefined) => Promise<void>, failure: string) {
    setBusy(true);
    setError(null);
    try {
      await act(await getToken());
      setSettled((before) => new Set([...Array.from(before), ...ids]));
      onChanged();
    } catch (e) {
      setError(toApiError(e, failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Check these kitchen assignments" className="mb-8">
      <p className="text-sm text-ink-secondary">
        When staff were given kitchens, everyone was put in the main kitchen. Move anyone who works in
        another kitchen.
      </p>

      {error && (
        <div className="mt-4">
          <ErrorNotice error={error} />
        </div>
      )}

      {/* A list and not a table: two facts and one control per person, and on a phone each row simply
          wraps its select under the name rather than becoming a card. */}
      <ul className="mt-4 divide-y divide-hairline border-y border-hairline">
        {rows.map((r) => (
          <li key={r.staffId} className="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 py-3">
            <div className="min-w-0">
              <p className="font-medium text-ink">{r.fullName}</p>
              <p className="text-sm text-ink-secondary">{r.jobTitleLabel}</p>
            </div>
            <select
              aria-label={`Kitchen for ${r.fullName}`}
              value={r.kitchenId}
              disabled={busy}
              onChange={(e) => {
                const kitchenId = e.target.value;
                void settle(
                  [r.staffId],
                  (t) => api.setStaffKitchen(r.staffId, kitchenId, t),
                  "We couldn’t move them to that kitchen."
                );
              }}
              className="min-h-touch rounded-control border border-hairline px-3"
            >
              {/* Their current kitchen stays offered even if it has been archived since, so the
                  select shows the truth rather than whichever kitchen happens to be first. */}
              {!active.some((k) => k.id === r.kitchenId) && <option value={r.kitchenId}>{r.kitchenName}</option>}
              {active.map((k) => (
                <option key={k.id} value={k.id}>
                  {k.name}
                </option>
              ))}
            </select>
          </li>
        ))}
      </ul>

      <div className="mt-4">
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => {
            const ids = rows.map((r) => r.staffId);
            void settle(ids, (t) => api.confirmStaffKitchens(ids, t), "We couldn’t save that.");
          }}
        >
          These are right
        </Button>
      </div>
    </Card>
  );
}
