import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import type { DeliveryLineView, RecordDeliveryInput } from "@/lib/api";

/**
 * "Record a delivery" (R-DEL-3, T-266), on its own: the panel the Deliveries screen opens per vendor
 * and which the purchase order page may later open filtered to one order (Q-8).
 *
 * <p>The API is mocked and nothing else is: the panel's own arithmetic (bags to Kg, what is still to
 * come, what the confirmation says) is what these check, against the request it actually sends.
 */

const { api, auth } = vi.hoisted(() => ({
  api: { recordDelivery: vi.fn() },
  // One stable object, as role-refusals.test.tsx explains: a fresh getToken each render re-runs
  // anything keyed on it.
  auth: { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" }, getToken: async () => "test-token" },
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => auth }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});

import { RecordDeliveryPanel } from "@/components/RecordDeliveryPanel";

const TODAY = "2026-09-19";

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

const TOOR = line({});
/** Ordered as 4 × Bag (25 Kg): 100 Kg in stock, counted in bags at the gate. */
const RICE = line({
  poLineId: "l2",
  itemName: "Rice, Sona Masoori",
  orderedQty: 100,
  stillToCome: 100,
  packLabel: "Bag (25 Kg)",
  packQuantity: 25,
  packCount: 4,
});
/** Another order of the same vendor, part delivered: 30 of 50 kept, 2 refused, 20 still owed. */
const BASMATI = line({
  poLineId: "l3",
  poId: "p38",
  poNumber: "PO-0038",
  itemName: "Basmati rice",
  orderedQty: 50,
  receivedQty: 30,
  rejectedQty: 2,
  stillToCome: 20,
  neededBy: "2026-09-14",
  parts: [
    { receiptId: "r1", receivedOn: "2026-09-12", receivedQty: 30, rejectedQty: 2, rejectReason: "SPOILED", receivedByName: "Karuna Murti Das" },
  ],
});

function renderPanel(props: Partial<Parameters<typeof RecordDeliveryPanel>[0]> = {}) {
  const onSaved = vi.fn();
  const onCancel = vi.fn();
  render(
    <RecordDeliveryPanel
      vendorId="v1"
      vendorName="Sri Balaji Traders"
      lines={[TOOR, RICE, BASMATI]}
      today={TODAY}
      onCancel={onCancel}
      onSaved={onSaved}
      {...props}
    />,
  );
  return { onSaved, onCancel };
}

const box = (name: string) => screen.getByLabelText(name) as HTMLInputElement;
const type = (name: string, value: string) => fireEvent.change(box(name), { target: { value } });
const sent = (call = 0) => api.recordDelivery.mock.calls[call][0] as RecordDeliveryInput;

async function save() {
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Save delivery" }));
  });
}

describe("Record a delivery", () => {
  beforeEach(() => {
    api.recordDelivery.mockReset();
    api.recordDelivery.mockResolvedValue({ receiptIds: ["r9"] });
  });

  it("lists every line the vendor owes, across their orders, with the order number and what is still to come", () => {
    renderPanel();
    expect(screen.getByRole("heading", { name: "Record a delivery from Sri Balaji Traders" })).toBeInTheDocument();
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Toor dal");
    expect(rows[0]).toHaveTextContent("PO-0041");
    expect(rows[0]).toHaveTextContent("50 Kg");
    expect(rows[2]).toHaveTextContent("Basmati rice");
    expect(rows[2]).toHaveTextContent("PO-0038");
    // Why a vendor who sent 32 of 50 still owes 20: said where the 20 is.
    expect(rows[2]).toHaveTextContent("20 Kg");
    expect(rows[2]).toHaveTextContent("includes 2 Kg rejected 12 Sept");
  });

  it("starts with Received now blank, on the conductor's ruling, and Everything arrived fills it with what is still to come", () => {
    renderPanel();
    expect(box("Toor dal received now, in Kg").value).toBe("");
    expect(box("Rice, Sona Masoori received now, in Bag (25 Kg)").value).toBe("");
    expect(box("Basmati rice received now, in Kg").value).toBe("");

    fireEvent.click(screen.getByRole("button", { name: "Everything arrived" }));
    expect(box("Toor dal received now, in Kg").value).toBe("50");
    // In bags, the unit it was ordered in.
    expect(box("Rice, Sona Masoori received now, in Bag (25 Kg)").value).toBe("4");
    expect(box("Basmati rice received now, in Kg").value).toBe("20");
  });

  it("takes a pack line in bags and sends the stock-unit amount: 4 bags is 100 Kg", async () => {
    renderPanel();
    const rice = screen.getAllByRole("row")[2];
    // Still to come in the unit it was ordered in, with what it is in stock.
    expect(rice).toHaveTextContent("4 bags (100 Kg)");
    type("Rice, Sona Masoori received now, in Bag (25 Kg)", "4");
    // What the typed bags become, beside them, before anything is saved.
    expect(within(rice).getByText("(100 Kg)")).toBeInTheDocument();
    expect(within(rice).getAllByText("bags").length).toBeGreaterThan(0);
    await save();
    expect(api.recordDelivery).toHaveBeenCalledTimes(1);
    expect(sent().vendorId).toBe("v1");
    expect(sent().lines).toEqual([
      { poLineId: "l2", receivedQty: 100, rejectedQty: 0, rejectReason: null, expiryDate: null },
    ]);
  });

  it("accepts part of a pack: 2.8 bags still to come reads so, and sends 70 Kg", async () => {
    renderPanel({ lines: [{ ...RICE, receivedQty: 30, stillToCome: 70 }] });
    expect(screen.getAllByRole("row")[1]).toHaveTextContent("2.8 bags (70 Kg)");
    fireEvent.click(screen.getByRole("button", { name: "Everything arrived" }));
    expect(box("Rice, Sona Masoori received now, in Bag (25 Kg)").value).toBe("2.8");
    await save();
    expect(sent().lines[0].receivedQty).toBe(70);
  });

  it("counts a pack with no name as its size: 2 × 500 gm (1 Kg)", () => {
    renderPanel({
      lines: [line({ poLineId: "l9", itemName: "Cardamom", unit: "GM", orderedQty: 1000, stillToCome: 1000, packLabel: "500 gm", packQuantity: 500, packCount: 2 })],
    });
    expect(screen.getAllByRole("row")[1]).toHaveTextContent("2 × 500 gm (1 Kg)");
  });

  it("says one pack with no name once: 1 × 500 gm, not 1 × 500 gm (500 gm) (T-299)", () => {
    renderPanel({
      lines: [line({ poLineId: "l9", itemName: "Tea", unit: "GM", orderedQty: 500, stillToCome: 500, packLabel: "500 gm", packQuantity: 500, packCount: 1 })],
    });
    const row = screen.getAllByRole("row")[1];
    // Under Still to come.
    expect(row).toHaveTextContent("1 × 500 gm");
    expect(row).not.toHaveTextContent("(500 gm)");
    // Beside the box: one typed is one pack, which the unit beside the box already says.
    type("Tea received now, in 500 gm", "1");
    expect(row).toHaveTextContent("× 500 gm");
    expect(row).not.toHaveTextContent("(500 gm)");
    // Two typed is new information, and keeps its bracket as the ruling has it.
    type("Tea received now, in 500 gm", "2");
    expect(within(row).getByText("(1 Kg)")).toBeInTheDocument();
  });

  it("keeps a named pack's size in brackets even for one: 1 bag (25 Kg) (T-299)", () => {
    renderPanel({ lines: [{ ...RICE, stillToCome: 25 }] });
    const row = screen.getAllByRole("row")[1];
    expect(row).toHaveTextContent("1 bag (25 Kg)");
    type("Rice, Sona Masoori received now, in Bag (25 Kg)", "1");
    expect(within(row).getByText("(25 Kg)")).toBeInTheDocument();
  });

  it("sends rejections with their reason and an optional expiry, and nothing for a blank line", async () => {
    renderPanel();
    type("Basmati rice received now, in Kg", "18");
    type("Basmati rice rejected on delivery, in Kg", "2");
    fireEvent.change(screen.getByLabelText("Why Basmati rice was rejected"), { target: { value: "DAMAGED" } });
    type("Basmati rice expiry date", "2027-03-01");
    await save();
    expect(sent().lines).toEqual([
      { poLineId: "l3", receivedQty: 18, rejectedQty: 2, rejectReason: "DAMAGED", expiryDate: "2027-03-01" },
    ]);
  });

  it("offers an optional Expiry box on every line, on the conductor's ruling", () => {
    renderPanel();
    for (const item of ["Toor dal", "Rice, Sona Masoori", "Basmati rice"]) {
      const expiry = box(`${item} expiry date`);
      expect(expiry.type).toBe("date");
      expect(expiry.required).toBe(false);
    }
  });

  it("refuses to save more than is still to come, a rejection with no reason, or nothing at all", async () => {
    renderPanel();
    await save();
    expect(screen.getByText("Type what arrived on at least one line, or press Everything arrived.")).toBeInTheDocument();

    type("Toor dal received now, in Kg", "51");
    await save();
    expect(screen.getByText("Only 50 Kg is still to come")).toBeInTheDocument();
    expect(box("Toor dal received now, in Kg")).toHaveAttribute("aria-invalid", "true");

    type("Toor dal received now, in Kg", "49");
    type("Toor dal rejected on delivery, in Kg", "2");
    await save();
    expect(screen.getByText("Received and rejected add up to more than the 50 Kg still to come")).toBeInTheDocument();
    expect(screen.getByText("Choose why it was rejected")).toBeInTheDocument();
    expect(api.recordDelivery).not.toHaveBeenCalled();
  });

  it("keeps one idempotency key across a retry of the same attempt, and takes a new one when anything changes", async () => {
    api.recordDelivery.mockRejectedValueOnce(new Error("network"));
    renderPanel();
    type("Toor dal received now, in Kg", "30");
    await save();
    expect(screen.getByRole("alert")).toHaveTextContent("We couldn’t record that delivery.");

    api.recordDelivery.mockRejectedValueOnce(new Error("network"));
    await save();
    expect(sent(1).idempotencyKey).toBe(sent(0).idempotencyKey);

    type("Toor dal received now, in Kg", "31");
    await save();
    expect(api.recordDelivery).toHaveBeenCalledTimes(3);
    expect(sent(2).idempotencyKey).not.toBe(sent(0).idempotencyKey);
    expect(sent(2).idempotencyKey).toMatch(/[0-9a-f-]{36}/);
  });

  it("confirms in the user's words, naming a part delivery it completes", async () => {
    const { onSaved } = renderPanel();
    type("Basmati rice received now, in Kg", "20");
    await save();
    expect(onSaved).toHaveBeenCalledWith(
      "Delivery from Sri Balaji Traders recorded. 1 item went into stock. Basmati rice is complete: 50 of 50 Kg. 2 lines are still to come from them.",
    );
  });

  it("lists only one order's lines when opened for that order, and still counts what the vendor owes elsewhere", async () => {
    const { onSaved } = renderPanel({ orderId: "p38" });
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("Basmati rice");
    expect(screen.queryByText("Toor dal")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Everything arrived" }));
    expect(box("Basmati rice received now, in Kg").value).toBe("20");
    await save();
    expect(sent().lines.map((l) => l.poLineId)).toEqual(["l3"]);
    expect(onSaved.mock.calls[0][0]).toContain("2 lines are still to come from them.");
  });

  it("shows no price anywhere", () => {
    renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "Everything arrived" }));
    expect(document.body.textContent).not.toMatch(/price|₹|rate/i);
  });

  it("draws the mock's grid inside the panel's padding, with no spacing of its own (T-285, VERIFY-C defect 1)", () => {
    renderPanel();
    const table = screen.getByRole("table");
    // The shared entry grid's classes set the cell padding for every entry grid (T-278); the panel
    // no longer pulls the grid over its own padding or sets cell padding of its own.
    expect(table.className).toContain("kms-entry-grid");
    expect(table.className).not.toMatch(/-mx-5|w-\[calc|\[&_:is\(th,td\)\]:px-3|ps-5|pe-5/);
    // Too narrow for the table, it becomes the phone's cards, measured against the panel itself.
    const panel = screen.getByRole("heading", { name: /Record a delivery from/ }).closest(".bg-raised")!;
    expect(panel.className).toContain("[container-type:inline-size]");
    expect(table.className).toContain("[@container(max-width:713.98px)]:[&>tbody>tr]:grid");
    expect(table.className).toContain("[@container(max-width:713.98px)]:[&>thead]:sr-only");
  });

  it("keeps each word of an item's name whole, a hyphenated one too, and the order number in one piece (VERIFY-C defect 3)", () => {
    renderPanel({ lines: [line({ poLineId: "x1", itemName: "VERIFY-C Milk", poNumber: "PO-2026-0049" })] });
    const row = screen.getAllByRole("row")[1];
    const name = within(row).getByText("VERIFY-C");
    expect(name.className).toContain("whitespace-nowrap");
    expect(within(row).getByText("Milk").className).toContain("whitespace-nowrap");
    expect(name.parentElement!.parentElement!.textContent).toBe("VERIFY-C Milk");
    expect(within(row).getByText("PO-2026-0049").className).toContain("whitespace-nowrap");
  });

  it("hands Cancel back to the screen", () => {
    const { onCancel } = renderPanel();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalled();
  });
});
