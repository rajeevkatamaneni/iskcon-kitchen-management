import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  DeliveriesView,
  DeliveryLineView,
  DeliveryPartView,
  DeliveryReceiptView,
  DeliveryReturnView,
  OlderDeliveriesView,
  RecordDeliveryInput,
} from "@/lib/api";

/**
 * The Deliveries screen (R-DEL-1..5, T-266), against a mocked API.
 *
 * <p>The mock of `api.recordDelivery` is a small fake server rather than a stub: it applies what the
 * screen sends to the lines and receipts the next `getDeliveries` returns, the way the real one
 * does (kept goods come off what is owed, refused goods stay owed). That is what lets the document's
 * acceptance test run end to end here: record part of a delivery, then the rest, and read the
 * item's history.
 */

const TODAY = "2026-09-19";
const ME = "Govinda Das";

const { authRef, api, paramsRef, replaceMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Govinda Das" },
      getToken: async () => "test-token",
      refresh: () => {},
    } as { status: string; appUser: { role: string; userId: string; fullName: string } | null; getToken: () => Promise<string>; refresh: () => void },
  },
  api: { getDeliveries: vi.fn(), getOlderDeliveries: vi.fn(), recordDelivery: vi.fn() },
  paramsRef: { current: new URLSearchParams() },
  replaceMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => paramsRef.current,
  usePathname: () => "/deliveries",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});

import DeliveriesPage from "@/app/deliveries/page";

// ---- A small fake server ---------------------------------------------------------------------

function line(o: Partial<DeliveryLineView>): DeliveryLineView {
  return {
    poLineId: "l1",
    poId: "p41",
    poNumber: "PO-0041",
    vendorId: "v1",
    vendorName: "Sri Balaji Traders",
    ingredientId: "i1",
    itemName: "Toor dal",
    unit: "KG",
    orderedQty: 50,
    receivedQty: 0,
    rejectedQty: 0,
    returnedQty: 0,
    stillToCome: 50,
    neededBy: "2026-09-17",
    completedOn: null,
    packLabel: null,
    packQuantity: null,
    packCount: null,
    parts: [],
    ...o,
  };
}

function part(o: Partial<DeliveryPartView>): DeliveryPartView {
  return { receiptId: "r1", receivedOn: "2026-09-12", receivedQty: 0, rejectedQty: 0, rejectReason: null, receivedByName: ME, ...o };
}

let lines: DeliveryLineView[];
let received: DeliveryReceiptView[];
let receiptSeq: number;

function seed() {
  receiptSeq = 100;
  const basmatiPart = part({ receiptId: "r1", receivedOn: "2026-09-12", receivedQty: 30, rejectedQty: 2, rejectReason: "SPOILED", receivedByName: "Karuna Murti Das" });
  lines = [
    // Sri Balaji Traders: two orders, both late. Toor dal untouched, rice in bags, basmati part delivered.
    line({}),
    line({ poLineId: "l2", ingredientId: "i2", itemName: "Rice, Sona Masoori", orderedQty: 100, stillToCome: 100, packLabel: "Bag (25 Kg)", packQuantity: 25, packCount: 4 }),
    line({
      poLineId: "l3", poId: "p38", poNumber: "PO-0038", ingredientId: "i3", itemName: "Basmati rice", orderedQty: 50,
      receivedQty: 30, rejectedQty: 2, stillToCome: 20, neededBy: "2026-09-14", parts: [basmatiPart],
    }),
    // Heritage Fresh Dairy: due today.
    line({ poLineId: "l4", poId: "p45", poNumber: "PO-0045", vendorId: "v2", vendorName: "Heritage Fresh Dairy", ingredientId: "i4", itemName: "Milk, toned", unit: "L", orderedQty: 120, stillToCome: 120, neededBy: TODAY }),
    // Kalasipalya: finished on the 16th across two orders in one visit, one line with 1 Kg sent back.
    line({ poLineId: "k1", poId: "p37", poNumber: "PO-0037", vendorId: "v3", vendorName: "Kalasipalya Vegetable Mandi", ingredientId: "i5", itemName: "Tomato, ripe", orderedQty: 20, receivedQty: 20, returnedQty: 1, stillToCome: 0, neededBy: "2026-09-15", completedOn: "2026-09-16", parts: [part({ receiptId: "r2", receivedOn: "2026-09-16", receivedQty: 20, receivedByName: "Madhava Das" })] }),
    line({ poLineId: "k2", poId: "p36", poNumber: "PO-0036", vendorId: "v3", vendorName: "Kalasipalya Vegetable Mandi", ingredientId: "i6", itemName: "Potato", orderedQty: 40, receivedQty: 40, stillToCome: 0, neededBy: "2026-09-15", completedOn: "2026-09-16", parts: [part({ receiptId: "r3", receivedOn: "2026-09-16", receivedQty: 40, receivedByName: "Madhava Das" })] }),
  ];
  const receiptLine = (l: DeliveryLineView, p: DeliveryPartView, returns: DeliveryReturnView[] = []) => ({
    poLineId: l.poLineId, itemName: l.itemName, unit: l.unit, receivedQty: p.receivedQty, rejectedQty: p.rejectedQty, rejectReason: p.rejectReason,
    returnedQty: returns.reduce((sum, r) => sum + r.quantity, 0), returns,
  });
  const byId = (id: string) => lines.find((l) => l.poLineId === id)!;
  received = [
    { receiptId: "r2", poId: "p37", poNumber: "PO-0037", vendorId: "v3", vendorName: "Kalasipalya Vegetable Mandi", receivedOn: "2026-09-16", receivedByName: "Madhava Das", lines: [receiptLine(byId("k1"), byId("k1").parts[0], [{ quantity: 1, reason: "SPOILED", returnedOn: "2026-09-17" }])] },
    // Two returns of the potatoes, on two days for two reasons: two lines in the Returned column.
    { receiptId: "r3", poId: "p36", poNumber: "PO-0036", vendorId: "v3", vendorName: "Kalasipalya Vegetable Mandi", receivedOn: "2026-09-16", receivedByName: "Madhava Das", lines: [receiptLine(byId("k2"), byId("k2").parts[0], [{ quantity: 2, reason: "DAMAGED", returnedOn: "2026-09-17" }, { quantity: 0.5, reason: "NOT_DELIVERED", returnedOn: "2026-09-18" }])] },
    { receiptId: "r1", poId: "p38", poNumber: "PO-0038", vendorId: "v1", vendorName: "Sri Balaji Traders", receivedOn: "2026-09-12", receivedByName: "Karuna Murti Das", lines: [receiptLine(byId("l3"), basmatiPart)] },
  ];
}

function view(): DeliveriesView {
  const touched = new Set(received.flatMap((r) => r.lines.map((l) => l.poLineId)));
  return {
    today: TODAY,
    open: structuredClone(lines.filter((l) => l.stillToCome > 0)),
    received: structuredClone(received),
    receivedLines: structuredClone(lines.filter((l) => touched.has(l.poLineId))),
    receivedFrom: "2026-08-21",
    hasOlder: true,
  };
}

/** What the real server does with a recorded delivery, as far as this screen can see. */
function record(input: RecordDeliveryInput) {
  const byOrder = new Map<string, DeliveryReceiptView>();
  for (const i of input.lines) {
    const l = lines.find((x) => x.poLineId === i.poLineId)!;
    let r = byOrder.get(l.poId);
    if (!r) {
      r = { receiptId: `r${++receiptSeq}`, poId: l.poId, poNumber: l.poNumber, vendorId: l.vendorId, vendorName: l.vendorName, receivedOn: TODAY, receivedByName: ME, lines: [] };
      byOrder.set(l.poId, r);
    }
    r.lines.push({ poLineId: l.poLineId, itemName: l.itemName, unit: l.unit, receivedQty: i.receivedQty, rejectedQty: i.rejectedQty, rejectReason: i.rejectReason ?? null, returnedQty: 0, returns: [] });
    l.parts.push(part({ receiptId: r.receiptId, receivedOn: TODAY, receivedQty: i.receivedQty, rejectedQty: i.rejectedQty, rejectReason: i.rejectReason ?? null }));
    l.receivedQty += i.receivedQty;
    l.rejectedQty += i.rejectedQty;
    // Rejected goods stay owed: only what was kept comes off.
    l.stillToCome = Math.max(0, l.orderedQty - l.receivedQty);
    if (l.stillToCome === 0) l.completedOn = TODAY;
  }
  received = [...byOrder.values(), ...received];
  return { receiptIds: [...byOrder.values()].map((r) => r.receiptId) };
}

// ---- Helpers ---------------------------------------------------------------------------------

function signedInAs(role: string) {
  authRef.current = { status: "signed-in", appUser: { role, userId: "me", fullName: ME }, getToken: async () => "test-token", refresh: () => {} };
}

async function open() {
  render(<DeliveriesPage />);
  await screen.findByRole("tablist", { name: "Deliveries view" });
}

const tab = (name: string) => fireEvent.click(screen.getByRole("tab", { name }));
const vendor = (name: string) => screen.getByRole("region", { name });

describe("Deliveries", () => {
  beforeEach(() => {
    seed();
    signedInAs("KITCHEN_MANAGER");
    paramsRef.current = new URLSearchParams();
    replaceMock.mockReset();
    api.getDeliveries.mockReset().mockImplementation(async () => view());
    api.getOlderDeliveries.mockReset();
    api.recordDelivery.mockReset().mockImplementation(async (input: RecordDeliveryInput) => record(input));
  });

  it("admits Kitchen Staff, who hold RECEIVE_DELIVERIES since Rajeev answered Q-1 (2026-09-19)", async () => {
    signedInAs("KITCHEN_STAFF");
    await open();
    expect(screen.getByRole("heading", { name: "Deliveries", level: 1 })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Not your page" })).toBeNull();
    expect(api.getDeliveries).toHaveBeenCalled();
  });

  it("refuses a Volunteer, who does not hold RECEIVE_DELIVERIES, and asks the server nothing", async () => {
    signedInAs("VOLUNTEER");
    render(<DeliveriesPage />);
    expect(await screen.findByRole("heading", { name: "Not your page" })).toBeInTheDocument();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(api.getDeliveries).not.toHaveBeenCalled();
  });

  it("admits the Temple Admin", async () => {
    signedInAs("TEMPLE_ADMIN");
    await open();
    expect(screen.getByRole("heading", { name: "Deliveries", level: 1 })).toBeInTheDocument();
  });

  it("sums the day up in a small strip of tiles", async () => {
    await open();
    const tiles = screen.getByText("Due today").parentElement!.parentElement!;
    expect(tiles.textContent).toContain("Due today1 vendor");
    expect(tiles.textContent).toContain("Overdue1 vendor");
    expect(tiles.textContent).toContain("Partly delivered1 order");
    // The 16th is within the week; the 12th, seven days back, is not. Two receipts, one visit.
    expect(tiles.textContent).toContain("Received this week1 delivery");
  });

  it("Expected: groups what is still to come by vendor, most overdue first, with the needed-by date", async () => {
    await open();
    const cards = screen.getAllByRole("region");
    expect(cards.map((c) => c.getAttribute("aria-label"))).toEqual(["Sri Balaji Traders", "Heritage Fresh Dairy"]);
    const balaji = vendor("Sri Balaji Traders");
    expect(within(balaji).getByText("Overdue")).toBeInTheDocument();
    expect(balaji).toHaveTextContent("3 items on orders PO-0041, PO-0038");
    const toor = within(balaji).getByText("Toor dal").closest("tr")!;
    expect(toor).toHaveTextContent("PO-0041");
    expect(toor).toHaveTextContent("50 Kg");
    // A pack line reads in the unit it was ordered in.
    expect(within(balaji).getByText("Rice, Sona Masoori").closest("tr")).toHaveTextContent("4 bags (100 Kg)");
    // What already came of a part-delivered line.
    expect(within(balaji).getByText("Basmati rice").closest("tr")).toHaveTextContent("30 Kg came 12 Sept");
    // One Record a delivery per vendor.
    expect(screen.getAllByRole("button", { name: "Record a delivery" })).toHaveLength(2);
    // No prices anywhere on this screen.
    expect(document.body.textContent).not.toMatch(/₹|price/i);
  });

  it("writes every date as a date: a blue Today pill beside today's, an amber pill beside a late one", async () => {
    await open();
    const milk = within(vendor("Heritage Fresh Dairy")).getByText("Milk, toned").closest("tr")!;
    const dueCell = milk.querySelector('[data-label="Needed by"]')!;
    expect(dueCell).toHaveTextContent("19 Sept");
    const today = within(dueCell as HTMLElement).getByText("Today");
    expect(today.className).toContain("bg-info-bg");

    const toor = within(vendor("Sri Balaji Traders")).getByText("Toor dal").closest("tr")!;
    const lateCell = toor.querySelector('[data-label="Needed by"]')!;
    expect(lateCell).toHaveTextContent("17 Sept");
    const late = within(lateCell as HTMLElement).getByText("2 days late");
    expect(late.className).toContain("bg-warning-bg");

    // The same component at the same size: only the tone differs.
    const shape = (el: HTMLElement) => el.className.split(" ").filter((c) => !/^(bg|text)-(info|warning)/.test(c)).join(" ");
    expect(shape(today)).toBe(shape(late));
    // "Today" never stands in for the date.
    expect(screen.queryByText(/^Today$/, { selector: "td" })).toBeNull();
  });

  it("Partly delivered: what is still owed, and for how long, with the item's history", async () => {
    await open();
    tab("Partly delivered");
    expect(screen.getAllByRole("region").map((c) => c.getAttribute("aria-label"))).toEqual(["Sri Balaji Traders"]);
    const basmati = screen.getByText("Basmati rice").closest("tr")!;
    expect(basmati.querySelector('[data-label="Ordered"]')).toHaveTextContent("50 Kg");
    expect(basmati.querySelector('[data-label="Received"]')).toHaveTextContent("30 Kg");
    expect(basmati.querySelector('[data-label="Still owed"]')).toHaveTextContent("20 Kg");
    expect(basmati.querySelector('[data-label="Owed for"]')).toHaveTextContent("7 days, since 12 Sept");
    const history = within(basmati).getByRole("button", { name: /1 delivery/ });
    fireEvent.click(history);
    // In a row of its own under the item, spanning all six columns (VERIFY3 F-1, T-343): inside
    // the Item cell it was held to that column's 115px at 1024 beside a row of empty cells.
    const opened = basmati.nextElementSibling as HTMLTableRowElement;
    expect(opened.cells).toHaveLength(1);
    expect(opened.cells[0]).toHaveAttribute("colspan", "6");
    expect(within(opened).getByRole("list").id).toBe(history.getAttribute("aria-controls"));
    expect(opened).toHaveTextContent("12 Sept · 30 Kg received · 2 Kg rejected (spoiled) · Received by: Karuna Murti Das");
    expect(opened).toHaveTextContent("Received 30 of 50 Kg ordered");
    expect(within(basmati).queryByRole("list")).toBeNull();
    fireEvent.click(history);
    expect(basmati.nextElementSibling).not.toBe(opened);
    expect(screen.queryByText("Received 30 of 50 Kg ordered")).toBeNull();
  });

  it("Partly delivered: a bag line reads in bags on every amount, with the Kg beside it (T-310)", async () => {
    // The E2E repro: 4 bags ordered, 2 came. Ordered read "100 Kg" beside Still owed "2 bags (50 Kg)".
    const rice = lines.find((l) => l.poLineId === "l2")!;
    rice.receivedQty = 50;
    rice.stillToCome = 50;
    rice.parts = [part({ receiptId: "r9", receivedOn: "2026-09-12", receivedQty: 50, receivedByName: "Gopal Das" })];
    await open();
    tab("Partly delivered");
    const row = screen.getByText("Rice, Sona Masoori").closest("tr")!;
    expect(row.querySelector('[data-label="Ordered"]')).toHaveTextContent("4 bags (100 Kg)");
    expect(row.querySelector('[data-label="Received"]')).toHaveTextContent("2 bags (50 Kg)");
    expect(row.querySelector('[data-label="Still owed"]')).toHaveTextContent("2 bags (50 Kg)");
  });

  it("Partly delivered: the vendor's panel lists everything they owe, blank until Everything arrived", async () => {
    await open();
    tab("Partly delivered");
    fireEvent.click(screen.getByRole("button", { name: "Record a delivery" }));
    const panel = vendor("Sri Balaji Traders");
    // The document over the mock: all three lines, not only the part-delivered one.
    for (const item of ["Toor dal", "Rice, Sona Masoori", "Basmati rice"]) {
      expect((within(panel).getByLabelText(`${item} received now, in ${item.startsWith("Rice") ? "Bag (25 Kg)" : "Kg"}`) as HTMLInputElement).value).toBe("");
    }
  });

  it("Received: a dated history of date, vendor, items, received by, rejected and returned", async () => {
    await open();
    tab("Received");
    const rows = screen.getAllByRole("row").slice(1);
    // Two receipts for two orders on one visit are one row.
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("16 Sept");
    expect(rows[0]).toHaveTextContent("Kalasipalya Vegetable Mandi");
    expect(rows[0]).toHaveTextContent("Tomato, ripe 20 Kg");
    expect(rows[0]).toHaveTextContent("Potato 40 Kg");
    expect(rows[0].querySelector('[data-label="Received by"]')).toHaveTextContent("Madhava Das");
    expect(rows[0].querySelector('[data-label="Rejected"]')).toHaveTextContent("None");
    // The mock's words for a return: item and amount, reason, date. One return to a line.
    const returned = [...rows[0].querySelector('[data-label="Returned"]')!.querySelectorAll("span")].map((x) => x.textContent);
    expect(returned).toEqual([
      "Tomato, ripe 1 Kg, spoiled, 17 Sept",
      "Potato 2 Kg, damaged, 17 Sept",
      "Potato 500 gm, not delivered, 18 Sept",
    ]);
    expect(rows[1].querySelector('[data-label="Returned"]')).toHaveTextContent("None");
    expect(rows[1]).toHaveTextContent("12 Sept");
    expect(rows[1].querySelector('[data-label="Rejected"]')).toHaveTextContent("Basmati rice 2 Kg, spoiled");
    // The basmati line is still owed, so its history is offered; the tomatoes came whole in one go.
    expect(within(rows[1]).getByRole("button", { name: /1 delivery/ })).toBeInTheDocument();
    expect(within(rows[0]).queryByRole("button")).toBeNull();
  });

  it("Received: two histories opened on one visit sit under it, spanning the table, each headed with its item (T-343)", async () => {
    // The Kalasipalya visit holds two items; make both still owed so each offers its history.
    lines.find((l) => l.poLineId === "k1")!.stillToCome = 5;
    lines.find((l) => l.poLineId === "k2")!.stillToCome = 5;
    await open();
    tab("Received");
    const row = screen.getAllByRole("row").slice(1)[0];
    const tomato = within(row).getByRole("button", { name: "1 delivery of Tomato, ripe" });
    const potato = within(row).getByRole("button", { name: "1 delivery of Potato" });
    fireEvent.click(tomato);
    fireEvent.click(potato);
    const opened = row.nextElementSibling as HTMLTableRowElement;
    expect(opened.cells).toHaveLength(1);
    expect(opened.cells[0]).toHaveAttribute("colspan", "6");
    const lists = within(opened).getAllByRole("list");
    expect(lists.map((l) => l.id)).toEqual([tomato.getAttribute("aria-controls"), potato.getAttribute("aria-controls")]);
    expect(new Set(lists.map((l) => l.id)).size).toBe(2);
    // Under the row the history no longer sits beside its item, so each is named.
    expect(opened.textContent!.replace(/\s+/g, " ")).toMatch(/Tomato, ripe.*16 Sept · 20 Kg received.*Potato.*16 Sept · 40 Kg received/);
    expect(within(row).queryByRole("list")).toBeNull();
    // Closing one leaves the other; closing both takes the row away.
    fireEvent.click(tomato);
    expect(within(opened).getAllByRole("list")).toHaveLength(1);
    fireEvent.click(potato);
    expect(row.nextElementSibling).not.toBe(opened);
  });

  it("Received: a visit of one item opens its history without repeating the item's name", async () => {
    await open();
    tab("Received");
    const row = screen.getAllByRole("row").slice(1)[1];
    fireEvent.click(within(row).getByRole("button", { name: "1 delivery of Basmati rice" }));
    const opened = row.nextElementSibling as HTMLTableRowElement;
    expect(opened.cells[0]).toHaveAttribute("colspan", "6");
    expect(within(opened).queryByText("Basmati rice")).toBeNull();
    expect(opened).toHaveTextContent("Received 30 of 50 Kg ordered");
  });

  it("Received: Show older deliveries loads 30 days at a time, and goes when there is nothing older", async () => {
    const older = (receivedFrom: string, hasOlder: boolean, date: string, id: string): OlderDeliveriesView => ({
      received: [{ receiptId: id, poId: `po-${id}`, poNumber: `PO-${id}`, vendorId: "v2", vendorName: "Heritage Fresh Dairy", receivedOn: date, receivedByName: ME, lines: [{ poLineId: `x-${id}`, itemName: "Curd", unit: "KG", receivedQty: 20, rejectedQty: 0, rejectReason: null, returnedQty: 0, returns: [] }] }],
      receivedLines: [],
      receivedFrom,
      hasOlder,
    });
    api.getOlderDeliveries
      .mockResolvedValueOnce(older("2026-07-22", true, "2026-08-10", "a"))
      .mockResolvedValueOnce(older("2026-06-22", false, "2026-07-01", "b"));
    await open();
    tab("Received");
    expect(screen.getAllByRole("row")).toHaveLength(3);

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Show older deliveries" }));
    });
    expect(api.getOlderDeliveries).toHaveBeenLastCalledWith("2026-08-21", "test-token");
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(4));
    expect(screen.getAllByRole("row")[3]).toHaveTextContent("10 Aug");

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Show older deliveries" }));
    });
    expect(api.getOlderDeliveries).toHaveBeenLastCalledWith("2026-07-22", "test-token");
    await waitFor(() => expect(screen.getAllByRole("row")).toHaveLength(5));
    expect(screen.getAllByRole("row")[4]).toHaveTextContent("1 Jul");
    expect(screen.queryByRole("button", { name: "Show older deliveries" })).toBeNull();
  });

  it("opens ?order= on that order's vendor, with only that order's lines", async () => {
    paramsRef.current = new URLSearchParams("order=p38");
    await open();
    const panel = await screen.findByRole("heading", { name: "Record a delivery from Sri Balaji Traders" });
    const card = panel.closest("section")!;
    const rows = within(card).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("Basmati rice");
    expect(rows[0]).toHaveTextContent("PO-0038");
    expect(replaceMock).toHaveBeenCalledWith("/deliveries");
  });

  it("opens ?order= for a delivered order on its history and a neutral line, with no form", async () => {
    paramsRef.current = new URLSearchParams("order=p37");
    await open();
    const note = await screen.findByText("Everything on PO-0037 has been delivered.");
    expect(note.className).not.toMatch(/success|danger|warning/);
    const box = screen.getByRole("region", { name: "Order delivered" });
    expect(within(box).getByText("Tomato, ripe")).toBeInTheDocument();
    expect(within(box).getByRole("button", { name: /1 delivery/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save delivery" })).toBeNull();
    expect(screen.queryByText("Potato")).toBeNull();
  });

  it("records part of a delivery, then the rest, and the item completes with both parts in its history", async () => {
    await open();
    // Part one, from Expected: 30 of the 50 Kg of toor dal.
    fireEvent.click(within(vendor("Sri Balaji Traders")).getByRole("button", { name: "Record a delivery" }));
    fireEvent.change(screen.getByLabelText("Toor dal received now, in Kg"), { target: { value: "30" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save delivery" }));
    });
    expect(api.recordDelivery).toHaveBeenCalledTimes(1);
    const first = api.recordDelivery.mock.calls[0][0] as RecordDeliveryInput;
    expect(first.lines).toEqual([{ poLineId: "l1", receivedQty: 30, rejectedQty: 0, rejectReason: null, expiryDate: null }]);
    // Green, because it is the user's own action that worked.
    const done = await screen.findByText(/Delivery from Sri Balaji Traders recorded\. 1 item went into stock\./);
    expect(done.closest("[class*='success']")).not.toBeNull();

    // Part two, the same way, from Partly delivered.
    tab("Partly delivered");
    await waitFor(() => expect(screen.getByText("Toor dal").closest("tr")).toHaveTextContent("20 Kg"));
    fireEvent.click(screen.getByRole("button", { name: "Record a delivery" }));
    fireEvent.change(screen.getByLabelText("Toor dal received now, in Kg"), { target: { value: "20" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save delivery" }));
    });
    const second = api.recordDelivery.mock.calls[1][0] as RecordDeliveryInput;
    expect(second.idempotencyKey).not.toBe(first.idempotencyKey);
    expect(await screen.findByText(/Toor dal is complete: 50 of 50 Kg\./)).toBeInTheDocument();

    // It is no longer expected, and its history holds both parts in the document's words.
    tab("Expected");
    await waitFor(() => expect(within(vendor("Sri Balaji Traders")).queryByText("Toor dal")).toBeNull());
    tab("Received");
    const rows = screen.getAllByRole("row").slice(1).filter((r) => r.textContent!.includes("Toor dal"));
    expect(rows).toHaveLength(2);
    const history = within(rows[0]).getByRole("button", { name: /2 deliveries/ });
    expect(history).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(history);
    expect(history).toHaveAttribute("aria-expanded", "true");
    // The history opens in the row under the visit's, spanning the table (T-343).
    const opened = rows[0].nextElementSibling as HTMLTableRowElement;
    expect(opened.cells[0]).toHaveAttribute("colspan", "6");
    const items = within(opened).getAllByRole("listitem").map((li) => li.textContent!.replace("Today", "").trim());
    expect(items).toEqual([
      "19 Sept · 30 Kg received · Received by: Govinda Das",
      "19 Sept · 20 Kg received · Received by: Govinda Das",
      "Received 50 of 50 Kg ordered · complete 19 Sept",
    ]);
  });
});
