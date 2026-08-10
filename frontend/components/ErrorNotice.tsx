import type { ApiError } from "@/lib/api";

/**
 * How every failure is shown to a user.
 *
 * <p>What happened, what to do about it, and the reference code — in that order of prominence.
 * The code is deliberately quiet and monospaced: it matters enormously to whoever is helping,
 * and not at all to the person reading, so it should be findable without competing with the
 * sentence that tells them what to do.
 */
export function ErrorNotice({ error }: { error: ApiError }) {
  return (
    <div
      role="alert"
      className="rounded border border-danger/20 bg-danger-bg p-4 text-danger"
    >
      <p className="font-medium">{error.message}</p>
      <p className="mt-1 text-sm">{error.action}</p>

      <p className="mt-3 text-xs text-danger/70">
        If you need help, quote{" "}
        <span className="font-mono font-medium">{error.code}</span>
      </p>
    </div>
  );
}
