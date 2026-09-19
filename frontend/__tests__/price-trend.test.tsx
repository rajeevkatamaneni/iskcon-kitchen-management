import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { PriceTrend, priceDirection } from "@/components/PriceTrend";
import { Tooltip } from "@/components/ds/Tooltip";
import { InfoHint } from "@/components/ds/InfoHint";

/**
 * The arrow beside a List price (T-256, R-VEN-3), and the shared tooltip it opens (R-INV-5).
 *
 * <p>The direction is the part that matters most and is easiest to get backwards: a red up arrow on
 * a price that fell would tell the temple a vendor had put prices up when they had come down. The
 * negative control for this task inverts the comparison in `priceDirection` and this file must fail.
 *
 * <p>The tooltip's placement is tested with a stubbed `getBoundingClientRect`, because jsdom lays
 * nothing out: the stub says where the browser would have put the tip, and the test reads where the
 * component then moved it. The real geometry is measured in the browser (see the proof).
 */

afterEach(() => {
  vi.restoreAllMocks();
});

describe("priceDirection", () => {
  it("is up when the price rose, down when it fell, same when unchanged", () => {
    expect(priceDirection(64, 60)).toBe("up");
    expect(priceDirection(60, 64)).toBe("down");
    expect(priceDirection(60, 60)).toBe("same");
  });

  it("treats float dust below the history's four places as unchanged", () => {
    expect(priceDirection(0.068, 0.06800000001)).toBe("same");
    expect(priceDirection(0.068, 0.065)).toBe("up");
  });

  it("is nothing when there is no previous price, or no current one", () => {
    expect(priceDirection(60, null)).toBeNull();
    expect(priceDirection(null, 60)).toBeNull();
    expect(priceDirection(undefined, undefined)).toBeNull();
  });
});

describe("the marker", () => {
  it("rose: a red up arrow, named in words", () => {
    render(<PriceTrend current={64} previous={60} previousOn="2026-09-12" />);
    const m = screen.getByRole("img", { name: "Up from ₹60 on 12 Sept" });
    expect(m).toHaveClass("text-danger");
    expect(m.querySelector("i")).toHaveClass("ti-arrow-up");
    expect(m.getAttribute("data-direction")).toBe("up");
  });

  it("fell: a green down arrow", () => {
    render(<PriceTrend current={60} previous={64} previousOn="2026-09-12" />);
    const m = screen.getByRole("img", { name: "Down from ₹64 on 12 Sept" });
    expect(m).toHaveClass("text-success");
    expect(m.querySelector("i")).toHaveClass("ti-arrow-down");
  });

  it("unchanged: a flat dash in the neutral colour", () => {
    render(<PriceTrend current={60} previous={60} previousOn="2026-09-12" />);
    const m = screen.getByRole("img", { name: "Unchanged from ₹60 on 12 Sept" });
    expect(m).toHaveClass("text-ink-secondary");
    expect(m).not.toHaveClass("text-danger");
    expect(m).not.toHaveClass("text-success");
    expect(m.querySelector("i")).toHaveClass("ti-minus");
  });

  it("no previous price: nothing at all", () => {
    const { container } = render(<PriceTrend current={60} previous={null} previousOn={null} />);
    expect(container.innerHTML).toBe("");
  });

  it("shows the previous price and its date on hover, and on keyboard focus", () => {
    render(<PriceTrend current={64} previous={60} previousOn="2026-09-12" />);
    const m = screen.getByRole("img");
    expect(m).toHaveAttribute("tabindex", "0");

    fireEvent.mouseEnter(m);
    expect(screen.getByRole("tooltip").textContent).toBe("₹60 on 12 Sept");
    fireEvent.keyDown(m, { key: "Escape" });
    expect(screen.queryByRole("tooltip")).toBeNull();

    fireEvent.focus(m);
    expect(screen.getByRole("tooltip").textContent).toBe("₹60 on 12 Sept");
  });
});

/**
 * Stubs where the browser would lay the tip out (centred under its control) and the window width.
 * Anything that is not the tip is placed at the given trigger rectangle.
 */
function layout(tip: { left: number; width: number; top: number; height: number }, trigger: { top: number }, vw: number, vh = 800) {
  Object.defineProperty(document.documentElement, "clientWidth", { configurable: true, value: vw });
  Object.defineProperty(window, "innerHeight", { configurable: true, value: vh });
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    if (this.getAttribute("role") === "tooltip") {
      return { left: tip.left, right: tip.left + tip.width, top: tip.top, bottom: tip.top + tip.height, width: tip.width, height: tip.height, x: tip.left, y: tip.top, toJSON: () => ({}) } as DOMRect;
    }
    return { left: 0, right: 24, top: trigger.top, bottom: trigger.top + 24, width: 24, height: 24, x: 0, y: trigger.top, toJSON: () => ({}) } as DOMRect;
  });
}

describe("the shared tooltip stays on screen (R-INV-5)", () => {
  it("slides left when centring would run it past the right edge at 390", () => {
    // Centred, it would span 300..500 on a 390 window: 118px over the 8px margin.
    layout({ left: 300, width: 200, top: 100, height: 40 }, { top: 60 }, 390);
    render(<Tooltip text="Adds up to the grand total"><button type="button">✓</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    expect(screen.getByRole("tooltip").style.transform).toBe("translateX(calc(-50% + -118px))");
  });

  it("slides right when it would run past the left edge", () => {
    layout({ left: -40, width: 200, top: 100, height: 40 }, { top: 60 }, 390);
    render(<Tooltip text="x"><button type="button">i</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    expect(screen.getByRole("tooltip").style.transform).toBe("translateX(calc(-50% + 48px))");
  });

  it("stays centred when it fits", () => {
    layout({ left: 100, width: 200, top: 100, height: 40 }, { top: 60 }, 390);
    render(<Tooltip text="x"><button type="button">i</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    expect(screen.getByRole("tooltip").style.transform).toBe("translateX(calc(-50% + 0px))");
    expect(screen.getByRole("tooltip").style.top).toBe("100%");
  });

  it("opens above when there is no room below and there is room above", () => {
    layout({ left: 100, width: 200, top: 780, height: 40 }, { top: 740 }, 390, 800);
    render(<Tooltip text="x"><button type="button">i</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    const tip = screen.getByRole("tooltip");
    expect(tip.style.bottom).toBe("100%");
    expect(tip.style.top).toBe("auto");
  });

  it("is never wider than the window less its margins", () => {
    layout({ left: 100, width: 200, top: 100, height: 40 }, { top: 60 }, 390);
    render(<Tooltip text="x"><button type="button">i</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    expect(screen.getByRole("tooltip").className).toContain("max-w-[min(20rem,calc(100vw-16px))]");
  });

  it("follows its control when the page moves it while it is open (T-310)", async () => {
    // The repro: open beside the tick at one layout, then the page re-renders and moves the tick
    // 16px right without any resize event. Before T-310 the tip kept its first slide and ran off.
    const at = { trigger: 309, tip: 230 };
    Object.defineProperty(document.documentElement, "clientWidth", { configurable: true, value: 390 });
    Object.defineProperty(window, "innerHeight", { configurable: true, value: 844 });
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
      const [left, width] = this.getAttribute("role") === "tooltip" ? [at.tip, 178] : [at.trigger, 24];
      return { left, right: left + width, top: 100, bottom: 124, width, height: 24, x: left, y: 100, toJSON: () => ({}) } as DOMRect;
    });
    render(<Tooltip text="Adds up to the grand total"><button type="button">✓</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    // 230 + 178 = 408, 26px past 390 − 8.
    expect(screen.getByRole("tooltip").style.transform).toBe("translateX(calc(-50% + -26px))");

    at.trigger += 16;
    at.tip += 16;
    await waitFor(() =>
      expect(screen.getByRole("tooltip").style.transform).toBe("translateX(calc(-50% + -42px))"),
    );
  });

  it("does not shout inside a table heading", () => {
    render(<Tooltip text="x"><button type="button">i</button></Tooltip>);
    fireEvent.focus(screen.getByRole("button"));
    expect(screen.getByRole("tooltip")).toHaveClass("normal-case");
  });
});

describe("InfoHint, the tooltip's other user, still behaves", () => {
  it("opens its hint on focus and on hover, names its field, and closes on Escape", () => {
    layout({ left: 300, width: 200, top: 100, height: 40 }, { top: 60 }, 390);
    render(<InfoHint text="How long this vendor takes." label="Lead time (days)" />);
    const i = screen.getByRole("button", { name: "More about Lead time (days)" });
    expect(i).toHaveAttribute("type", "button");

    fireEvent.focus(i);
    const tip = screen.getByRole("tooltip");
    expect(tip.textContent).toBe("How long this vendor takes.");
    expect(tip.style.transform).toBe("translateX(calc(-50% + -118px))");

    fireEvent.keyDown(i, { key: "Escape" });
    expect(screen.queryByRole("tooltip")).toBeNull();

    fireEvent.mouseEnter(i);
    expect(screen.getByRole("tooltip")).toBeTruthy();
  });
});
