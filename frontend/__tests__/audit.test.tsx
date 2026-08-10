import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import AuditPage from "@/app/audit/page";

describe("audit log viewer", () => {
  it("explains the log is permanent and shows an inviting empty state", () => {
    // The first thing an admin sees before anything is recorded. It should set the expectation
    // that entries are immutable — that is the whole point of an audit log.
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

    // The action filter lists the human-readable actions, not the raw stored names.
    const action = within(filters).getByLabelText(/action/i);
    expect(within(action).getByRole("option", { name: /role changed/i })).toBeInTheDocument();
    expect(
      within(action).getByRole("option", { name: /log viewed by operator/i })
    ).toBeInTheDocument();
  });

  it("marks the audit log as the current page in navigation", () => {
    render(<AuditPage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    const current = within(nav).getByRole("link", { current: "page" });

    expect(current).toHaveTextContent(/audit log/i);
  });
});
