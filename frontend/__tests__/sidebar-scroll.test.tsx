import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("next/navigation", () => ({ usePathname: () => "/wishlist" }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    appUser: {
      userId: "u1",
      fullName: "Admin One",
      role: "TEMPLE_ADMIN",
      tenantName: "ISKCON South Bengaluru",
      tenantSlug: "iskcon-south-bengaluru",
      temples: [],
    },
    signOut: vi.fn(),
    switchTemple: vi.fn(),
  }),
}));

import { Sidebar } from "@/components/Sidebar";

/**
 * Choosing a destination mounts the page's own copy of the menu, so without help the list of
 * destinations starts again at the top and throws away where the admin was.
 */
describe("the menu stays where it was left", () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it("remembers how far down the destinations were scrolled", () => {
    const { container } = render(<Sidebar activeHref="/wishlist" />);
    const list = container.querySelector(".overflow-y-auto") as HTMLElement;

    fireEvent.scroll(list, { target: { scrollTop: 240 } });

    expect(sessionStorage.getItem("kms.sidebar.scroll")).toBe("240");
  });

  it("puts itself back there when the next page mounts it again", () => {
    sessionStorage.setItem("kms.sidebar.scroll", "240");

    const { container } = render(<Sidebar activeHref="/recipes" />);
    const list = container.querySelector(".overflow-y-auto") as HTMLElement;

    // Restored during layout, before the browser paints — so no visible jump.
    expect(list.scrollTop).toBe(240);
  });

  it("starts at the top when nothing has been scrolled yet", () => {
    const { container } = render(<Sidebar activeHref="/today" />);
    const list = container.querySelector(".overflow-y-auto") as HTMLElement;

    expect(list.scrollTop).toBe(0);
    expect(screen.getByLabelText("Main")).toBeInTheDocument();
  });

  it("names the temple, not the software", () => {
    render(<Sidebar activeHref="/today" />);

    // The temple's own name is the heading of its own app. "Temple Kitchen" told everyone the name
    // of the thing they were already looking at, and demoted the one word that says where they are.
    expect(screen.getByText("ISKCON South Bengaluru")).toBeInTheDocument();
    expect(screen.queryByText("Temple Kitchen")).not.toBeInTheDocument();
  });
});


/**
 * jsdom does no layout, so this cannot prove the lockup fits — that was measured in a real browser
 * against Anek and a real temple's name (64px mark, 8px gap, 28px name: two lines, 70px tall, no
 * overflow). What it can do is stop the sizes being quietly reduced again, which is the way a
 * deliberate change like this normally gets lost.
 */
describe("the temple's mark and name", () => {
  it("keeps the enlarged sizes, on the published scales", () => {
    const { container } = render(<Sidebar activeHref="/today" />);

    const mark = container.querySelector('img[src*="iskcon"]') as HTMLElement;
    // 64px — on the design system's spacing scale, up from 36px.
    expect(mark.className).toContain("h-16");
    expect(mark.className).toContain("w-16");
    // Never allowed to be squeezed by the name beside it.
    expect(mark.className).toContain("flex-none");

    const name = screen.getByText("ISKCON South Bengaluru");
    // 28px — `2xl` on the type scale, exactly twice the 14px it was.
    expect(name.className).toContain("text-2xl");
    // A name of one long word must wrap rather than spill out of a 280px column.
    expect(name.className).toContain("break-words");
    expect(name.className).toContain("min-w-0");
  });
});
