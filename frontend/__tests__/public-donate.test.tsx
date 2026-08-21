import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

vi.mock("next/navigation", () => ({ useParams: () => ({ slug: "radha-govinda" }) }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      donationPage: async () => ({
        templeName: "Bengaluru Temple",
        is80gApproved: true,
        presets: [51, 501, 1001],
        platesToday: 1240,
        costPerPlateInr: 32,
        spendShares: [{ label: "Grains", percent: 60 }],
      }),
      publicWishlist: async () => [],
    },
  };
});

import PublicDonatePage from "@/app/t/[slug]/donate/page";

describe("public donation page", () => {
  it("opens on the temple's own figures, and on money rather than equipment", async () => {
    render(<PublicDonatePage />);

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: /no one leaves this temple hungry/i })).toBeInTheDocument()
    );
    // The temple's name and what it is cooking today, both from its own records.
    expect(screen.getByText(/Bengaluru Temple kitchen/)).toBeInTheDocument();
    expect(screen.getByText(/1,240 plates of prasadam today/)).toBeInTheDocument();
    // A plate has a price because this temple has spent and served enough to know it.
    expect(screen.getByText(/₹32 covers one plate/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "₹501" })).toBeInTheDocument();
    // Reachable without login — no RequireRole gate, no "not your page".
    expect(screen.queryByText(/not your page/i)).not.toBeInTheDocument();
  });

  it("offers the two ways to give, money first", async () => {
    render(<PublicDonatePage />);
    await waitFor(() => expect(screen.getByRole("tab", { name: "Donate money" })).toBeInTheDocument());

    expect(screen.getByRole("tab", { name: "Donate money" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /equipment the kitchen wants/i })).toBeInTheDocument();
  });
});
