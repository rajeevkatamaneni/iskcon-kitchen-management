import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { standardMenu } from "@/lib/nav";
import type { Kitchen } from "@/lib/api";

/**
 * The heading on `/kitchens` says the same words as the menu row that opens it (T-435).
 *
 * <p>Rajeev's standing rule is that a thing is called the same thing in every view, and he has had
 * to repeat it. The menu row is deliberately "All kitchens" rather than "Kitchens" — `nav.ts` gives
 * the reason: the row sits inside a group already headed "Kitchens", so the plain word would be the
 * group's own heading said twice. The page used to answer that click with "Kitchens", which reads
 * like a different destination from the one that was pressed.
 *
 * <p>This test is deliberately a <i>coupling</i> and not a second copy of the string: it reads the
 * label out of `nav.ts` at run time and asserts the rendered `h1` matches it, so renaming either
 * side alone fails. The words themselves are pinned separately — `nav.test.ts` spells the whole
 * temple-admin menu out label by label, so a rename of the pair is still a failing test somebody has
 * to justify, and this one only holds the two ends together.
 */

// The page is role-guarded and fetches live data, so stand in for auth, the router and the query the
// same way `kitchens.test.tsx` does. Only the heading is under test, so one kitchen is enough.
const { authRef, queryRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
    },
  },
  queryRef: { current: { data: [] as Kitchen[] | null, error: null, loading: false, reload: () => {} } },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import KitchensPage from "@/app/kitchens/page";

describe("the kitchens page heading", () => {
  it("is word for word the label of the menu item that opens it", () => {
    const item = standardMenu()
      .flatMap((group) => group.items)
      .find((i) => i.href === "/kitchens");

    // If the row ever moves or is renamed by id, fail here saying so rather than in a confusing
    // comparison against `undefined`.
    expect(item, "no menu item points at /kitchens any more").toBeDefined();

    render(<KitchensPage />);
    const heading = screen.getByRole("heading", { level: 1 });

    expect(heading).toHaveTextContent(new RegExp(`^${item!.label}$`));
  });
});
