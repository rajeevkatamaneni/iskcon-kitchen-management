import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

vi.mock("next/navigation", () => ({ usePathname: () => "/today" }));

// Mutable so a test can give the temple a longer name than the one on staging.
const user = {
  userId: "u1",
  fullName: "Karuna Murti Das",
  role: "TEMPLE_ADMIN",
  tenantName: "ISKCON South Bengaluru",
  tenantSlug: "iskcon-south-bengaluru",
  temples: [] as { id: string; name: string }[],
};

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ appUser: user, signOut: vi.fn(), switchTemple: vi.fn() }),
}));

import { Sidebar } from "@/components/Sidebar";

/**
 * T-284: nothing in the menu is cut off — not the temple's name, not the signed-in person's.
 *
 * <p>A verifier signed in as Temple Admin at 1280 saw "ISKCON South Benga" at the top of the menu
 * and "Karuna Murti D…" at the foot. The person's name had `truncate` on it; the temple's name was
 * `whitespace-nowrap overflow-hidden`, relying on a script to shrink it, and when the script's one
 * measurement was taken against the wrong width the name was simply sliced off.
 *
 * <p>Two kinds of check. The class lists: none of the utilities that turn "does not fit" into
 * missing letters may come back, on any of the three places a name is shown. And the fit itself,
 * with the layout jsdom does not do faked from the font size, so the decision between one line and
 * two can be held in place. The real widths were measured in Chrome and are in the proof.
 */

const CUTTING = ["truncate", "text-ellipsis", "overflow-hidden", "whitespace-nowrap", "line-clamp"];

function expectNeverCut(el: HTMLElement) {
  for (const cls of CUTTING) {
    expect(el.className.split(/\s+/)).not.toContain(cls);
  }
  expect(el.className).not.toMatch(/line-clamp-/);
  expect(el.style.textOverflow).not.toBe("ellipsis");
  expect(el.style.overflow).not.toBe("hidden");
}

describe("names in the menu are never cut off", () => {
  beforeEach(() => {
    user.tenantName = "ISKCON South Bengaluru";
    user.fullName = "Karuna Murti Das";
  });

  it("does not truncate the signed-in person's name", () => {
    render(<Sidebar activeHref="/today" />);
    const name = within(screen.getByLabelText("Main")).getByText("Karuna Murti Das");
    expectNeverCut(name);
    // Every character is there, with no ellipsis added in the text either.
    expect(name.textContent).toBe("Karuna Murti Das");
  });

  it("does not truncate the person's name in the panel that opens above it either", () => {
    render(<Sidebar activeHref="/today" />);
    const nav = screen.getByLabelText("Main");
    fireEvent.click(within(nav).getAllByText("Karuna Murti Das")[0].closest("button")!);
    for (const el of within(nav).getAllByText("Karuna Murti Das")) {
      expectNeverCut(el);
    }
  });

  it("does not clip the temple's name in the column", () => {
    render(<Sidebar activeHref="/today" />);
    const name = within(screen.getByLabelText("Main")).getByText("ISKCON South Bengaluru");
    expectNeverCut(name);
    expect(name.textContent).toBe("ISKCON South Bengaluru");
  });

  it("does not clip the temple's name in the phone bar", () => {
    const { container } = render(<Sidebar activeHref="/today" />);
    const bar = container.querySelector(".app-topbar") as HTMLElement;
    const name = within(bar).getByText("ISKCON South Bengaluru");
    expectNeverCut(name);
  });
});

describe("the role under the person's name", () => {
  afterEach(() => {
    user.role = "TEMPLE_ADMIN";
    user.fullName = "Karuna Murti Das";
  });

  it("names a Kitchen Manager in the Staff screen's words, not the database's", () => {
    user.role = "KITCHEN_MANAGER";
    user.fullName = "Madhava Das";
    render(<Sidebar activeHref="/today" />);
    const nav = screen.getByLabelText("Main");
    expect(within(nav).getByText("Kitchen manager")).toBeInTheDocument();
    expect(within(nav).queryByText("KITCHEN_MANAGER")).not.toBeInTheDocument();
  });
});

/**
 * jsdom lays nothing out, so clientWidth and scrollWidth are faked: the column is a fixed width,
 * and a name's one-line width is its length times half its font size — close enough to a real
 * face for the arithmetic, and the arithmetic is what is under test.
 */
describe("fitting the temple's name", () => {
  let column = 231;
  const clientWidth = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "clientWidth");
  const scrollWidth = Object.getOwnPropertyDescriptor(Element.prototype, "scrollWidth");

  beforeEach(() => {
    column = 231;
    Object.defineProperty(HTMLElement.prototype, "clientWidth", {
      configurable: true,
      get() {
        return column;
      },
    });
    Object.defineProperty(Element.prototype, "scrollWidth", {
      configurable: true,
      get(this: HTMLElement) {
        const size = parseFloat(this.style?.fontSize || "16");
        return Math.ceil((this.textContent ?? "").length * size * 0.5);
      },
    });
  });

  afterEach(() => {
    if (clientWidth) Object.defineProperty(HTMLElement.prototype, "clientWidth", clientWidth);
    if (scrollWidth) Object.defineProperty(Element.prototype, "scrollWidth", scrollWidth);
    user.tenantName = "ISKCON South Bengaluru";
  });

  function columnName(text: string) {
    return within(screen.getByLabelText("Main")).getByText(text);
  }

  it("keeps a name that fits on one line, shrunk to the width it has", () => {
    render(<Sidebar activeHref="/today" />);
    const name = columnName("ISKCON South Bengaluru");
    // 22 characters: 308px wanted at 28px, 225 available → floor(28 × 225 / 308) = 20px.
    expect(name.style.whiteSpace).toBe("nowrap");
    expect(name.style.fontSize).toBe("20px");
  });

  it("wraps a name that would need to shrink below the floor, rather than shrinking or clipping it", () => {
    user.tenantName = "ISKCON Sri Sri Radha Krishnachandra Temple Bengaluru";
    render(<Sidebar activeHref="/today" />);
    const name = columnName("ISKCON Sri Sri Radha Krishnachandra Temple Bengaluru");
    // One line would need 12px. It stops at 18px and is allowed to wrap between words instead.
    expect(name.style.whiteSpace).toBe("");
    expect(name.style.fontSize).toBe("18px");
    expectNeverCut(name);
  });

  it("leaves the name to the class list, which wraps, when there is no width to measure", () => {
    column = 0;
    render(<Sidebar activeHref="/today" />);
    const name = columnName("ISKCON South Bengaluru");
    // A failed measurement costs a line at most, never letters: no nowrap is left behind.
    expect(name.style.whiteSpace).toBe("");
    expect(name.style.fontSize).toBe("");
  });
});
