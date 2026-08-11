import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";

vi.mock("next/navigation", () => ({ useParams: () => ({ slug: "radha-govinda" }) }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      donationPage: async () => ({ templeName: "Bengaluru Temple", is80gApproved: true, presets: [51, 501, 1001] }),
    },
  };
});

import PublicDonatePage from "@/app/t/[slug]/donate/page";

describe("public donation page", () => {
  it("renders the temple's branding, presets, and the 80G option", async () => {
    render(<PublicDonatePage />);
    await waitFor(() => expect(screen.getByRole("heading", { name: "Bengaluru Temple" })).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "₹501" })).toBeInTheDocument();
    expect(screen.getByText(/80G tax certificate/i)).toBeInTheDocument();
    // Reachable without login — no RequireRole gate, no "not your page".
    expect(screen.queryByText(/not your page/i)).not.toBeInTheDocument();
  });
});
