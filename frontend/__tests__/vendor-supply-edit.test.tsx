import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { VendorDetailView, VendorSupplyView, VendorView } from "@/lib/api";

/**
 * Editing a supply a vendor already has (T-131).
 *
 * <p><strong>What was wrong.</strong> T-090 put a lead time on the supply row and put the box for it
 * on the Add supply form. That form's ingredient picker deliberately offers only ingredients this
 * vendor does *not* already supply, so the box could never be reached for a supply that exists — and
 * on a real temple's data every supply exists. The only route left was Remove and add again, which
 * starts from an empty form and so throws away the last price and the preferred flag to change a
 * lead time. The escalation T-090 built could not fire on anything.
 *
 * <p><strong>What is under test.</strong> Four things, and only the first is the obvious one.
 *
 * <ol>
 *   <li>A row can be edited in place, and the controls open holding what the row already says —
 *       because the server writes the whole row on every save, so a control that opened empty would
 *       erase the value it was showing a moment earlier.</li>
 *   <li>Changing one fact sends the other two back unchanged. This is the whole defect: a lead time
 *       recorded at the cost of the price is not a fix.</li>
 *   <li>Clearing a lead time posts an explicit <em>null</em>, never a 0. `Number("")` is 0, and 0
 *       means the goods arrive the same day — the planner counts back from the two differently, and
 *       the wrong one tells a cook there is time to order rice that can no longer be got. Asserted
 *       through `Object.keys` first, because `objectContaining` reads an absent key and an explicit
 *       null identically and those are different requests to a server that writes what it is given.</li>
 *   <li>A real 0 seeds the box as "0" rather than as blank, which is the same distinction from the
 *       other end: a cash-and-carry supply opened for editing must not come back as unknown.</li>
 * </ol>
 */

const { authRef } = vi.hoisted(() => ({
  // A stable object, not a fresh one per render: useAuthedQuery depends on getToken's identity.
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me" },
      getToken: async () => "test-token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; userId: string } | null;
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
}));

const { getVendorMock, listIngredientsMock, setVendorSupplyMock, removeVendorSupplyMock } = vi.hoisted(() => ({
  getVendorMock: vi.fn(),
  listIngredientsMock: vi.fn(),
  setVendorSupplyMock: vi.fn(),
  removeVendorSupplyMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "v1" }),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getVendor: getVendorMock,
      listIngredients: listIngredientsMock,
      setVendorSupply: setVendorSupplyMock,
      removeVendorSupply: removeVendorSupplyMock,
    },
  };
});

import VendorDetailPage from "@/app/vendors/[id]/page";

function vendor(): VendorView {
  return {
    id: "v1",
    name: "Heritage Fresh Dairy",
    contactPerson: null,
    phone: "+919845012303",
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "en",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active: true,
    whatsappReachable: true,
    createdAt: "2026-08-01T00:00:00Z",
  };
}

function supply(o: Partial<VendorSupplyView> = {}): VendorSupplyView {
  return {
    ingredientId: "ing-curd",
    ingredientName: "Curd",
    lastPrice: 58,
    leadTimeDays: null,
    preferred: true,
    ...o,
  };
}

function detail(supplies: VendorSupplyView[]): VendorDetailView {
  return { vendor: vendor(), supplies, statusHistory: [] };
}

/** The `<tr>` an ingredient's row is in, view or editing — the name cell is in both. */
function rowFor(name: string): HTMLElement {
  return screen.getByText(name).closest("tr") as HTMLElement;
}

/** Open a row for editing and hand back the row, now holding controls. */
async function openEditor(name: string): Promise<HTMLElement> {
  fireEvent.click(within(rowFor(name)).getByRole("button", { name: "Edit" }));
  await screen.findByRole("button", { name: "Save" });
  return rowFor(name);
}

const leadBox = (row: HTMLElement) =>
  within(row).getByLabelText("Lead time (days)", { selector: "input" });
const priceBox = (row: HTMLElement) =>
  within(row).getByLabelText("Last price (₹)", { selector: "input" });
const preferredBox = (row: HTMLElement) => within(row).getByLabelText("Preferred");

beforeEach(() => {
  authRef.current = {
    status: "signed-in",
    appUser: { role: "TEMPLE_ADMIN", userId: "me" },
    getToken: async () => "test-token",
    refresh: () => {},
  };
  getVendorMock.mockReset().mockResolvedValue(detail([supply()]));
  listIngredientsMock.mockReset().mockResolvedValue([
    { id: "ing-curd", name: "Curd", unit: "L", category: "Dairy" },
    { id: "ing-ghee", name: "Ghee", unit: "L", category: "Dairy" },
  ]);
  setVendorSupplyMock.mockReset().mockResolvedValue(undefined);
  removeVendorSupplyMock.mockReset().mockResolvedValue(undefined);
});

describe("the Add supply form still cannot reach a supply that exists", () => {
  /*
    Not a change — this is the behaviour that made editing necessary, asserted so that the reason
    the edit row exists is written down somewhere a change would trip over. If this ever starts
    offering Curd, somebody has made "add" a second way of editing and the two will disagree.
  */
  it("offers only ingredients this vendor does not already supply", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const picker = screen.getByLabelText("Ingredient") as HTMLSelectElement;
    const offered = Array.from(picker.options).map((o) => o.textContent);
    expect(offered).toContain("Ghee");
    expect(offered).not.toContain("Curd");
  });
});

describe("editing a supply in place", () => {
  it("offers Edit beside Remove on every row", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = rowFor("Curd");
    expect(within(row).getByRole("button", { name: "Edit" })).toBeTruthy();
    expect(within(row).getByRole("button", { name: "Remove" })).toBeTruthy();
  });

  it("opens the row holding what it already says", async () => {
    getVendorMock.mockResolvedValue(detail([supply({ lastPrice: 58, leadTimeDays: 4, preferred: true })]));
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    expect((priceBox(row) as HTMLInputElement).value).toBe("58");
    expect((leadBox(row) as HTMLInputElement).value).toBe("4");
    expect((preferredBox(row) as HTMLInputElement).checked).toBe(true);
  });

  /*
    The defect, in one assertion. Before T-131 the only route to this was Remove-and-add, and adding
    again starts from an empty form — so the 58 and the preference went in the bin to record a 3.
  */
  it("records a lead time on a supply that already exists, and keeps the price and the preference", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    fireEvent.change(leadBox(row), { target: { value: "3" } });
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setVendorSupplyMock).toHaveBeenCalled());
    const body = setVendorSupplyMock.mock.calls[0][1] as Record<string, unknown>;
    expect(body).toEqual({
      ingredientId: "ing-curd",
      lastPrice: 58,
      leadTimeDays: 3,
      preferred: true,
    });
  });

  it("changes a price without disturbing the lead time", async () => {
    getVendorMock.mockResolvedValue(detail([supply({ leadTimeDays: 2 })]));
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    fireEvent.change(priceBox(row), { target: { value: "62" } });
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setVendorSupplyMock).toHaveBeenCalled());
    const body = setVendorSupplyMock.mock.calls[0][1] as Record<string, unknown>;
    expect(body.lastPrice).toBe(62);
    expect(body.leadTimeDays).toBe(2);
  });

  // The assertion this file exists for on the clearing side. A lead time somebody recorded from a
  // guess has to be removable back to "nobody has said", and blank must not arrive as same-day.
  it("clears a lead time to an explicit null, never a zero", async () => {
    getVendorMock.mockResolvedValue(detail([supply({ leadTimeDays: 5 })]));
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    fireEvent.change(leadBox(row), { target: { value: "" } });
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setVendorSupplyMock).toHaveBeenCalled());
    const body = setVendorSupplyMock.mock.calls[0][1] as Record<string, unknown>;
    expect(Object.keys(body)).toContain("leadTimeDays");
    expect(body.leadTimeDays).toBeNull();
  });

  // And the same distinction from the other end: a real zero must survive being opened for editing.
  it("seeds a cash-and-carry zero as 0 rather than as blank, and saves it back as 0", async () => {
    getVendorMock.mockResolvedValue(detail([supply({ leadTimeDays: 0 })]));
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    expect((leadBox(row) as HTMLInputElement).value).toBe("0");

    fireEvent.click(within(row).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(setVendorSupplyMock).toHaveBeenCalled());
    const body = setVendorSupplyMock.mock.calls[0][1] as Record<string, unknown>;
    expect(body.leadTimeDays).toBe(0);
  });

  // A price can be cleared the same way, and for the same reason: "we have never paid for this"
  // and "we paid nothing for it" are different facts, and the table already prints them apart.
  it("clears a price to an explicit null too", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    fireEvent.change(priceBox(row), { target: { value: "" } });
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setVendorSupplyMock).toHaveBeenCalled());
    const body = setVendorSupplyMock.mock.calls[0][1] as Record<string, unknown>;
    expect(Object.keys(body)).toContain("lastPrice");
    expect(body.lastPrice).toBeNull();
  });

  it("moves the preference when the box is ticked, and says on the screen that it will", async () => {
    getVendorMock.mockResolvedValue(detail([supply({ preferred: false })]));
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    expect(
      screen.getByText(/only one vendor can be preferred for an ingredient/i)
    ).toBeTruthy();

    const row = await openEditor("Curd");
    fireEvent.click(preferredBox(row));
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(setVendorSupplyMock).toHaveBeenCalled());
    const body = setVendorSupplyMock.mock.calls[0][1] as Record<string, unknown>;
    expect(body.preferred).toBe(true);
  });

  it("sends nothing at all on Cancel, and puts the row back", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const row = await openEditor("Curd");
    fireEvent.change(leadBox(row), { target: { value: "9" } });
    fireEvent.click(within(row).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("button", { name: "Save" })).toBeNull());
    expect(setVendorSupplyMock).not.toHaveBeenCalled();
    expect(within(rowFor("Curd")).getByRole("button", { name: "Edit" })).toBeTruthy();
  });

  /*
    The editing row has to be exactly as wide as the header, or every cell below it is under the
    wrong column. Counted rather than written down as a number, which is the guard `/ingredients`
    settled on after a note claiming six columns went stale against a table of five.
  */
  it("keeps the editing row the same width as the header", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Curd");

    const headers = screen.getAllByRole("columnheader").length;
    const row = await openEditor("Curd");
    expect(row.querySelectorAll("td").length).toBe(headers);
  });
});
