import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import DesignReferencePage from "@/app/design-reference/page";

describe("design reference", () => {
  it("renders the reference screen", () => {
    render(<DesignReferencePage />);
    expect(
      screen.getByRole("heading", { name: /design reference/i })
    ).toBeInTheDocument();
  });

  it("states status in text, never colour alone", () => {
    // The accessibility rule from DESIGN_SYSTEM.md, asserted rather than assumed:
    // a badge that conveys meaning only through its fill is unreadable in bright
    // kitchen light and to anyone with a colour vision deficiency.
    render(<DesignReferencePage />);

    expect(screen.getByText(/low stock/i)).toBeInTheDocument();
    expect(screen.getByText(/invoice overdue/i)).toBeInTheDocument();
    expect(screen.getByText(/shift fully staffed/i)).toBeInTheDocument();
  });

  it("keeps green for the reader's own action, and a fully staffed shift neutral", () => {
    // The colour rule (DESIGN_SYSTEM v1.10, Rajeev 2026-09-18): green only confirms that what the
    // reader did worked. A fully staffed shift is a standing state, so it is neutral here, the page
    // people copy from.
    render(<DesignReferencePage />);

    const staffed = screen.getByText(/shift fully staffed/i);
    expect(staffed.className).not.toMatch(/success/);
    expect(staffed.className).toContain("bg-sunken");
    expect(screen.getByText(/meal plan saved/i).className).toContain("text-success");
  });

  it("exercises Devanagari, Telugu and Tamil alongside Latin", () => {
    // Guards the font fallback chain. If this text ever stops rendering, translated
    // content is broken and we want to know from a test rather than from a temple.
    render(<DesignReferencePage />);
    expect(screen.getByText(/खिचड़ी/)).toBeInTheDocument();
    expect(screen.getByText(/కిచిడీ/)).toBeInTheDocument();
    expect(screen.getByText(/கிச்சடி/)).toBeInTheDocument();
  });
});
