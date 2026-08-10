import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import OperationsPage from "@/app/operations/page";

describe("operations", () => {
  it("shows system health and the per-temple drill-in", () => {
    render(<OperationsPage />);

    expect(screen.getByRole("heading", { name: /operations/i })).toBeInTheDocument();

    const health = screen.getByRole("region", { name: /system health/i });
    expect(within(health).getByText(/database/i)).toBeInTheDocument();
    expect(within(health).getByText(/job scheduler/i)).toBeInTheDocument();

    // The per-temple section, with the today tiles and a temple selector.
    expect(screen.getByText(/sent today/i)).toBeInTheDocument();
    expect(screen.getByText(/failed today/i)).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("is honest that calendar precompute isn't available until Epic 4", () => {
    render(<OperationsPage />);
    expect(screen.getByText(/calendar precompute: not available yet/i)).toBeInTheDocument();
  });

  it("marks operations as the current page in navigation", () => {
    render(<OperationsPage />);
    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("link", { current: "page" })).toHaveTextContent(/operations/i);
  });
});
