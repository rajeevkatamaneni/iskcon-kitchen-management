import { describe, expect, it, vi } from "vitest";

// The Payments page is gone (R-PAY-4, T-275), and this file, which tested it, now tests the redirect
// left in its place. `redirect()` from next/navigation throws to stop rendering; the stub throws too,
// so the test proves both that it was called with the right address and that nothing rendered after.
const { redirectMock } = vi.hoisted(() => ({
  redirectMock: vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT ${to}`);
  }),
}));
vi.mock("next/navigation", () => ({ redirect: redirectMock }));

import PaymentsMovedPage, { dynamic } from "@/app/money/page";

describe("/money", () => {
  it("redirects to the Invoices list with Unpaid selected", () => {
    expect(() => PaymentsMovedPage()).toThrow("NEXT_REDIRECT /invoices?filter=unpaid");
    expect(redirectMock).toHaveBeenCalledTimes(1);
    expect(redirectMock).toHaveBeenCalledWith("/invoices?filter=unpaid");
  });

  it("answers on every request rather than from a prerendered page", () => {
    expect(dynamic).toBe("force-dynamic");
  });
});
