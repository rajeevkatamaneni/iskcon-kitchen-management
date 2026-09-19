import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { DeliveryPartView } from "@/lib/api";
import { useState } from "react";
import { DeliveryHistory, DeliveryHistoryList, DeliveryHistoryToggle } from "@/components/DeliveryHistory";

/**
 * The per-item delivery history (R-DEL-4, T-262): one component for Partly delivered, Received and
 * the purchase order page, so the words asserted here are the words all three screens say.
 *
 * <p>The wording is the document's, character for character — "12 Sept · 30 Kg received · 2 Kg
 * rejected (spoiled) · Received by: Karuna Murti Das" — because Rajeev tests against it. The mock
 * wrote the rejection as "rejected, spoiled"; the document wins where they differ.
 */

function part(over: Partial<DeliveryPartView> = {}): DeliveryPartView {
  return {
    receiptId: "r1",
    receivedOn: "2026-09-12",
    receivedQty: 30,
    rejectedQty: 0,
    rejectReason: null,
    receivedByName: "Karuna Murti Das",
    ...over,
  };
}

/** Text of an element with the whitespace a DOM tree leaves between spans collapsed. */
const text = (el: Element | null) => (el?.textContent ?? "").replace(/\s+/g, " ").trim();

afterEach(() => {
  vi.useRealTimers();
});

describe("DeliveryHistory", () => {
  it("is collapsed by default and says how many deliveries, singular for one", () => {
    const { rerender } = render(
      <DeliveryHistory parts={[part(), part({ receiptId: "r2" })]} unit="KG" orderedQty={50} completedOn={null} />,
    );
    // The arrow is aria-hidden and spaced by CSS gap, so the name is the words alone.
    const button = screen.getByRole("button", { name: "2 deliveries" });
    expect(button.querySelector("[aria-hidden]")?.textContent).toBe("▸");
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("list")).toBeNull();

    rerender(<DeliveryHistory parts={[part()]} unit="KG" orderedQty={50} completedOn={null} />);
    expect(screen.getByRole("button", { name: "1 delivery" })).toBeInTheDocument();
  });

  it("renders nothing when there are no deliveries", () => {
    const { container } = render(<DeliveryHistory parts={[]} unit="KG" orderedQty={50} completedOn={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("names the item to a screen reader only", () => {
    render(<DeliveryHistory parts={[part()]} unit="KG" orderedQty={50} completedOn={null} itemName="Sona Masoori rice" />);
    expect(screen.getByRole("button", { name: "1 delivery of Sona Masoori rice" })).toBeInTheDocument();
  });

  it("opens and closes with the mouse, toggling aria-expanded and pointing at the list", () => {
    render(<DeliveryHistory parts={[part()]} unit="KG" orderedQty={50} completedOn={null} />);
    const button = screen.getByRole("button");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    const list = screen.getByRole("list");
    expect(button.getAttribute("aria-controls")).toBe(list.id);
    expect(button.querySelector("[aria-hidden]")?.textContent).toBe("▾");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("list")).toBeNull();
  });

  // @testing-library/user-event is not installed here, and jsdom does not turn a key press on a
  // button into a click the way a browser does, so pressing Enter or Space cannot be simulated
  // honestly in this file. What makes those keys work is the element being a native, enabled
  // <button> with no tabindex games: that is asserted here, and the keys themselves were pressed
  // for real in Chrome (docs/work/proof/T-262.md).
  it("is a native button that takes focus, so Tab reaches it and Enter and Space press it", () => {
    render(<DeliveryHistory parts={[part()]} unit="KG" orderedQty={50} completedOn={null} />);
    const button = screen.getByRole("button");
    expect(button.tagName).toBe("BUTTON");
    expect(button).toHaveAttribute("type", "button");
    expect(button).toBeEnabled();
    expect(button).not.toHaveAttribute("tabindex");
    button.focus();
    expect(button).toHaveFocus();
  });

  it("writes each part in date order with the document's exact wording", () => {
    render(
      <DeliveryHistory
        // Given newest first on purpose: the component puts them in date order itself.
        parts={[
          part({ receiptId: "r2", receivedOn: "2026-09-15", receivedQty: 20, receivedByName: "Govinda Das" }),
          part({ receiptId: "r1", receivedOn: "2026-09-12", receivedQty: 30, rejectedQty: 2, rejectReason: "SPOILED" }),
        ]}
        unit="KG"
        orderedQty={50}
        completedOn="2026-09-15"
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    const items = screen.getAllByRole("listitem").map(text);
    expect(items).toEqual([
      "12 Sept · 30 Kg received · 2 Kg rejected (spoiled) · Received by: Karuna Murti Das",
      "15 Sept · 20 Kg received · Received by: Govinda Das",
      "Received 50 of 50 Kg ordered · complete 15 Sept",
    ]);
  });

  it("says each reason in words, and leaves out a receiver the server has no name for", () => {
    render(
      <DeliveryHistory
        parts={[
          part({ receiptId: "r1", rejectedQty: 1, rejectReason: "WRONG_ITEM", receivedByName: null }),
          part({ receiptId: "r2", receivedOn: "2026-09-13", receivedQty: 5, rejectedQty: 3, rejectReason: "DAMAGED" }),
        ]}
        unit="KG"
        orderedQty={50}
        completedOn={null}
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    const items = screen.getAllByRole("listitem").map(text);
    expect(items[0]).toBe("12 Sept · 30 Kg received · 1 Kg rejected (wrong item)");
    expect(items[1]).toBe("13 Sept · 5 Kg received · 3 Kg rejected (damaged) · Received by: Karuna Murti Das");
    expect(items[0]).not.toContain("Received by");
  });

  it("writes a rejection with no recorded reason without brackets (conductor's ruling, 2026-09-19)", () => {
    render(<DeliveryHistory parts={[part({ rejectedQty: 2 })]} unit="KG" orderedQty={50} completedOn={null} />);
    fireEvent.click(screen.getByRole("button"));
    expect(text(screen.getAllByRole("listitem")[0])).toBe(
      "12 Sept · 30 Kg received · 2 Kg rejected · Received by: Karuna Murti Das",
    );
  });

  it("closes with what is still owed while the line is open", () => {
    render(<DeliveryHistory parts={[part()]} unit="KG" orderedQty={50} completedOn={null} />);
    fireEvent.click(screen.getByRole("button"));
    const items = screen.getAllByRole("listitem").map(text);
    expect(items[items.length - 1]).toBe("Received 30 of 50 Kg ordered");
  });

  it("shows a gram line of 1,000 or more in Kg", () => {
    render(
      <DeliveryHistory
        parts={[part({ receivedQty: 1500, rejectedQty: 250, rejectReason: "SPOILED" })]}
        unit="GM"
        orderedQty={2000}
        completedOn={null}
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    const items = screen.getAllByRole("listitem").map(text);
    expect(items[0]).toBe("12 Sept · 1.5 Kg received · 250 gm rejected (spoiled) · Received by: Karuna Murti Das");
    expect(items[1]).toBe("Received 1.5 of 2 Kg ordered");
  });

  it("says both units when the received and ordered amounts land in different ones", () => {
    render(<DeliveryHistory parts={[part({ receivedQty: 0.5 })]} unit="KG" orderedQty={1} completedOn={null} />);
    fireEvent.click(screen.getByRole("button"));
    expect(text(screen.getAllByRole("listitem")[1])).toBe("Received 500 gm of 1 Kg ordered");
  });

  it("prints no order number anywhere", () => {
    const { container } = render(
      <DeliveryHistory
        parts={[part({ receiptId: "PO-2026-0042" }), part({ receiptId: "PO-2026-0043", receivedOn: "2026-09-15", receivedQty: 20 })]}
        unit="KG"
        orderedQty={50}
        completedOn="2026-09-15"
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(container.textContent).not.toMatch(/PO|order no|#/i);
  });

  it("keeps the date and adds the blue Today pill beside today's", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    // Midday UTC is the same calendar day in the temple's zone.
    vi.setSystemTime(new Date("2026-09-19T06:30:00Z"));
    render(
      <DeliveryHistory
        parts={[part({ receivedOn: "2026-09-19", receivedQty: 50 })]}
        unit="KG"
        orderedQty={50}
        completedOn="2026-09-19"
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    // The pill sits beside the date, spaced by CSS gap; read the line without it, then the pill.
    const items = screen.getAllByRole("listitem").map((li) => {
      const copy = li.cloneNode(true) as Element;
      copy.querySelectorAll(".bg-info-bg").forEach((pill) => pill.replaceWith(" [Today]"));
      return text(copy);
    });
    expect(items[0]).toBe("19 Sept [Today] · 50 Kg received · Received by: Karuna Murti Das");
    expect(items[1]).toBe("Received 50 of 50 Kg ordered · complete 19 Sept [Today]");
    expect(screen.getAllByText("Today")).toHaveLength(2);
    for (const pill of screen.getAllByText("Today")) expect(pill.className).toContain("bg-info-bg");
  });

  it("takes today from its caller when given one, not the browser's clock (VERIFY-C minor 6)", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    // The tablet says the 19th; the server says the 12th. The pill follows the server.
    vi.setSystemTime(new Date("2026-09-19T06:30:00Z"));
    render(
      <DeliveryHistory
        parts={[part({ receivedOn: "2026-09-12" }), part({ receiptId: "r2", receivedOn: "2026-09-19", receivedQty: 20 })]}
        unit="KG"
        orderedQty={50}
        completedOn={null}
        today="2026-09-12"
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    const [first, second] = screen.getAllByRole("listitem");
    expect(first.querySelector(".bg-info-bg")?.textContent).toBe("Today");
    expect(second.querySelector(".bg-info-bg")).toBeNull();
    expect(screen.getAllByText("Today")).toHaveLength(1);
  });

  it("uses no status colour of its own", () => {
    const { container } = render(
      <DeliveryHistory
        parts={[part({ rejectedQty: 2, rejectReason: "SPOILED" })]}
        unit="KG"
        orderedQty={30}
        completedOn="2026-09-12"
      />,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(container.innerHTML).not.toMatch(/success|danger|warning|green|red-/);
  });

  it("keeps each piece whole at every width, now that an opened history spans its table (T-343)", () => {
    // T-299 let the pieces wrap from 1024 up while the history lived in the Item column. Every
    // caller now gives it the table's whole width, so a whole piece holds no column open.
    render(<DeliveryHistory parts={[part({ receivedByName: "Lalita Devi Dasi" })]} unit="KG" orderedQty={50} completedOn={null} />);
    fireEvent.click(screen.getByRole("button"));
    const piece = screen.getByText("Received by: Lalita Devi Dasi");
    expect(piece.className.split(" ")).toContain("whitespace-nowrap");
    expect(piece.className).not.toMatch(/lg:whitespace-normal/);
  });

  it("keeps the toggle's words on one line, so '2 deliveries' never splits in a narrow column", () => {
    render(<DeliveryHistory parts={[part(), part({ receiptId: "r2" })]} unit="KG" orderedQty={50} completedOn={null} />);
    expect(screen.getByRole("button", { name: "2 deliveries" }).className.split(" ")).toContain("whitespace-nowrap");
  });
});

describe("DeliveryHistoryToggle and DeliveryHistoryList, for a history shown in a row of its own (T-343)", () => {
  function Split() {
    const [open, setOpen] = useState(false);
    return (
      <table>
        <tbody>
          <tr>
            <td>
              Rice
              <DeliveryHistoryToggle count={2} open={open} onToggle={() => setOpen((o) => !o)} controls="h1" itemName="Rice" />
            </td>
            <td>PO-2026-0054</td>
          </tr>
          {open && (
            <tr>
              <td colSpan={2}>
                <DeliveryHistoryList id="h1" parts={[part(), part({ receiptId: "r2", receivedOn: "2026-09-15", receivedQty: 20 })]} unit="KG" orderedQty={50} completedOn="2026-09-15" />
              </td>
            </tr>
          )}
        </tbody>
      </table>
    );
  }

  it("is one control across two rows: the button in the item's cell opens the list in the row under it", () => {
    render(<Split />);
    const button = screen.getByRole("button", { name: "2 deliveries of Rice" });
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("list")).toBeNull();
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    const list = screen.getByRole("list");
    expect(list.id).toBe("h1");
    expect(button.getAttribute("aria-controls")).toBe("h1");
    // Not inside the button's own cell: in the next row, spanning the table.
    expect(button.closest("td")!.contains(list)).toBe(false);
    expect(list.closest("td")).toHaveAttribute("colspan", "2");
    expect(screen.getAllByRole("listitem").map(text)).toEqual([
      "12 Sept · 30 Kg received · Received by: Karuna Murti Das",
      "15 Sept · 20 Kg received · Received by: Karuna Murti Das",
      "Received 50 of 50 Kg ordered · complete 15 Sept",
    ]);
    fireEvent.click(button);
    expect(screen.queryByRole("list")).toBeNull();
  });
});
