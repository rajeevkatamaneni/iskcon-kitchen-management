import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { HealthStatus, NotificationMetrics } from "@/lib/api";

// Operations makes two authed queries (health, and the platform notification metrics). The api
// methods are replaced with sentinels so the useAuthedQuery mock can tell which query is which by
// the fetcher's identity.
const { healthFn, opsNotificationsFn, healthRef, metricsRef, authRef } = vi.hoisted(() => ({
  healthFn: () => {},
  opsNotificationsFn: () => {},
  healthRef: { current: { data: null as HealthStatus | null, error: null, loading: false } },
  metricsRef: {
    current: { data: null as NotificationMetrics | null, error: null as unknown, loading: false },
  },
  authRef: {
    current: { status: "signed-in", appUser: { role: "SUPER_ADMIN" } } as {
      status: string;
      appUser: { role: string } | null;
    },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, health: healthFn, opsNotifications: opsNotificationsFn },
  };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (fetcher === healthFn) return healthRef.current;
    return metricsRef.current;
  },
}));

import OperationsPage from "@/app/operations/page";

const HEALTHY: HealthStatus = { status: "UP", db: "UP", scheduler: "STANDBY", timestamp: "" };

// A day's twelve two-hour buckets with `n` sends parked in one slot — enough to assert totals.
const zeros = () => Array(12).fill(0);
const at = (slot: number, n: number) => {
  const a = zeros();
  a[slot] = n;
  return a;
};

const METRICS: NotificationMetrics = {
  sentToday: 12,
  failedToday: 1,
  days: [
    { date: "2026-08-05", sent: at(3, 4), failed: zeros() },
    { date: "2026-08-06", sent: at(3, 6), failed: at(1, 2) },
    { date: "2026-08-07", sent: at(3, 5), failed: zeros() },
    { date: "2026-08-08", sent: at(3, 8), failed: zeros() },
    { date: "2026-08-09", sent: at(3, 6), failed: zeros() },
    { date: "2026-08-10", sent: at(3, 9), failed: at(0, 3) },
    { date: "2026-08-11", sent: at(3, 12), failed: at(4, 1) },
  ],
};

describe("operations", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "SUPER_ADMIN" } };
    healthRef.current = { data: HEALTHY, error: null, loading: false };
    metricsRef.current = { data: METRICS, error: null, loading: false };
  });

  it("shows live system health", () => {
    render(<OperationsPage />);

    expect(screen.getByRole("heading", { name: /operations/i })).toBeInTheDocument();

    const health = screen.getByRole("region", { name: /system health/i });
    expect(within(health).getByText(/reachable/i)).toBeInTheDocument();
    expect(within(health).getByText(/on standby/i)).toBeInTheDocument();
  });

  it("shows today's platform sent/failed totals", () => {
    render(<OperationsPage />);

    const metrics = screen.getByRole("region", { name: /notification metrics/i });
    expect(within(metrics).getByText(/sent today/i)).toBeInTheDocument();
    expect(within(metrics).getByText("12")).toBeInTheDocument();
    expect(within(metrics).getByText(/failed today/i)).toBeInTheDocument();
    expect(within(metrics).getByText("1")).toBeInTheDocument();
  });

  it("renders a two-hour pulse for each metric, carrying the daily totals", () => {
    render(<OperationsPage />);

    expect(
      screen.getByRole("img", {
        name: /Sent today in 2-hour buckets over the last 7 days\. Daily totals: 4, 6, 5, 8, 6, 9, 12/i,
      })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("img", {
        name: /Failed today in 2-hour buckets over the last 7 days\. Daily totals: 0, 2, 0, 0, 0, 3, 1/i,
      })
    ).toBeInTheDocument();
  });

  it("labels the last column of each pulse as today", () => {
    render(<OperationsPage />);
    // One weekday axis per tile, each ending in "Today".
    expect(screen.getAllByText("Today")).toHaveLength(2);
  });

  it("no longer offers a per-temple drill-in", () => {
    render(<OperationsPage />);
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("says plainly when the database is unreachable", () => {
    healthRef.current = {
      data: { status: "DOWN", db: "DOWN", scheduler: "STANDBY", timestamp: "" },
      error: null,
      loading: false,
    };
    render(<OperationsPage />);

    const health = screen.getByRole("region", { name: /system health/i });
    expect(within(health).getByText(/unreachable/i)).toBeInTheDocument();
  });

  it("refuses a temple role — operations is platform-only", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" } };
    render(<OperationsPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /operations/i })).not.toBeInTheDocument();
  });

  it("marks operations as the current page in navigation", () => {
    render(<OperationsPage />);
    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("link", { current: "page" })).toHaveTextContent(/operations/i);
  });
});
