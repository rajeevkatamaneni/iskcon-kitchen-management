import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, AuditPage as AuditPageData } from "@/lib/api";

const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" } } as {
      status: string;
      appUser: { role: string } | null;
    },
  },
  queryRef: {
    current: {
      data: { events: [], nextCursor: null } as AuditPageData | null,
      error: null as ApiError | null,
      loading: false,
    },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import AuditPage from "@/app/audit/page";

describe("audit log viewer", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" } };
    queryRef.current = { data: { events: [], nextCursor: null }, error: null, loading: false };
  });

  it("explains the log is permanent and shows an inviting empty state", () => {
    render(<AuditPage />);

    expect(screen.getByRole("heading", { name: /audit log/i })).toBeInTheDocument();
    expect(screen.getByText(/never be edited or removed/i)).toBeInTheDocument();
    expect(screen.getByText(/nothing recorded yet/i)).toBeInTheDocument();
  });

  it("offers the filters the backend accepts: a date range and an action", () => {
    render(<AuditPage />);

    const filters = screen.getByRole("form", { name: /filter the audit log/i });
    expect(within(filters).getByLabelText(/from/i)).toBeInTheDocument();
    expect(within(filters).getByLabelText(/to/i)).toBeInTheDocument();

    const action = within(filters).getByLabelText(/action/i);
    expect(within(action).getByRole("option", { name: /role changed/i })).toBeInTheDocument();
    expect(
      within(action).getByRole("option", { name: /log viewed by operator/i })
    ).toBeInTheDocument();
  });

  it("renders recorded events with a readable action and actor", () => {
    queryRef.current = {
      data: {
        events: [
          {
            id: "e1",
            action: "ROLE_CHANGED",
            entityType: "user",
            entityId: "u9",
            actorUserId: "u1",
            actorLabel: "Radha Devi",
            before: { role: "VOLUNTEER" },
            after: { role: "KITCHEN_STAFF" },
            reason: null,
            createdAt: "2026-08-10T09:00:00Z",
          },
        ],
        nextCursor: null,
      },
      error: null,
      loading: false,
    };
    render(<AuditPage />);

    expect(screen.getByRole("cell", { name: "Radha Devi" })).toBeInTheDocument();
    // "Role changed" is also a filter option; the cell is the rendered event.
    expect(screen.getByRole("cell", { name: "Role changed" })).toBeInTheDocument();
    expect(screen.queryByText(/nothing recorded yet/i)).not.toBeInTheDocument();
  });

  it("shows the error contract when the log can't be loaded", () => {
    queryRef.current = {
      data: null,
      loading: false,
      error: { code: "KMS-0000", message: "We couldn't load this.", action: "Try again." } as ApiError,
    };
    render(<AuditPage />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-0000")).toBeInTheDocument();
  });

  it("refuses a role without VIEW_AUDIT_LOG", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF" } };
    render(<AuditPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /audit log/i })).not.toBeInTheDocument();
  });

  it("marks the audit log as the current page in navigation", () => {
    render(<AuditPage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("link", { current: "page" })).toHaveTextContent(/audit log/i);
  });
});
