import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { HealthStatus, OpsTenant, TenantOps } from "@/lib/api";

// Operations makes three separate authed queries (health, the tenant list, the drill-in). The
// api methods are replaced with sentinels so the useAuthedQuery mock can tell which query is which
// by the fetcher's identity — the drill-in uses a closure, matching neither sentinel.
const { healthFn, opsTenantsFn, tenantOpsFn, healthRef, tenantsRef, opsRef, authRef } = vi.hoisted(
  () => ({
    healthFn: () => {},
    opsTenantsFn: () => {},
    tenantOpsFn: () => {},
    healthRef: { current: { data: null as HealthStatus | null, error: null, loading: false } },
    tenantsRef: { current: { data: [] as OpsTenant[] | null, error: null, loading: false } },
    opsRef: { current: { data: null as TenantOps | null, error: null as unknown, loading: false } },
    authRef: {
      current: { status: "signed-in", appUser: { role: "SUPER_ADMIN" } } as {
        status: string;
        appUser: { role: string } | null;
      },
    },
  })
);

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, health: healthFn, opsTenants: opsTenantsFn, tenantOps: tenantOpsFn },
  };
});
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fetcher: unknown) => {
    if (fetcher === healthFn) return healthRef.current;
    if (fetcher === opsTenantsFn) return tenantsRef.current;
    return opsRef.current;
  },
}));

import OperationsPage from "@/app/operations/page";

const HEALTHY: HealthStatus = { status: "UP", db: "UP", scheduler: "STANDBY", timestamp: "" };

describe("operations", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "SUPER_ADMIN" } };
    healthRef.current = { data: HEALTHY, error: null, loading: false };
    tenantsRef.current = { data: [], error: null, loading: false };
    opsRef.current = { data: null, error: null, loading: false };
  });

  it("shows live system health and the per-temple drill-in", () => {
    render(<OperationsPage />);

    expect(screen.getByRole("heading", { name: /operations/i })).toBeInTheDocument();

    const health = screen.getByRole("region", { name: /system health/i });
    expect(within(health).getByText(/reachable/i)).toBeInTheDocument();
    expect(within(health).getByText(/on standby/i)).toBeInTheDocument();

    expect(screen.getByText(/sent today/i)).toBeInTheDocument();
    expect(screen.getByText(/failed today/i)).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toBeInTheDocument();
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

  it("lists the temples in the selector", () => {
    tenantsRef.current = {
      data: [
        { id: "t1", name: "Radha Govinda", slug: "radha-govinda", status: "ACTIVE" },
        { id: "t2", name: "Krishna Balaram", slug: "krishna-balaram", status: "ACTIVE" },
      ],
      error: null,
      loading: false,
    };
    render(<OperationsPage />);

    const select = screen.getByRole("combobox");
    expect(within(select).getByRole("option", { name: /radha govinda/i })).toBeInTheDocument();
    expect(within(select).getByRole("option", { name: /krishna balaram/i })).toBeInTheDocument();
  });

  it("shows a chosen temple's counts and failed sends", () => {
    opsRef.current = {
      data: {
        tenantId: "t1",
        tenantName: "Radha Govinda",
        sentToday: 12,
        failedToday: 2,
        suppressedToday: 1,
        recentFailures: [
          { id: "f1", recipientLabel: "+9198…10", template: "shift_reminder", failedAt: "2026-08-10T08:00:00Z" },
        ],
        lastCalendarPrecompute: null,
      },
      error: null,
      loading: false,
    };
    render(<OperationsPage />);

    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getByText(/shift_reminder/)).toBeInTheDocument();
  });

  it("is honest that calendar precompute isn't available until Epic 4", () => {
    render(<OperationsPage />);
    expect(screen.getByText(/calendar precompute:/i)).toHaveTextContent(/not available yet/i);
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
