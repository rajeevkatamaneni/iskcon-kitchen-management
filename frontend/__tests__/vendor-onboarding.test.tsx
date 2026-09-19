import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, VendorDetailView, VendorSupplyView, VendorView } from "@/lib/api";

/**
 * Taking a vendor on, and what the supplies table shows about packs (T-256, R-VEN-1, R-VEN-4).
 *
 * <p><strong>The acceptance criterion, as written:</strong> tick 3 ingredients, fill two prices,
 * press Save, and all 3 are in the top table and gone from the bottom one. Asserted end to end
 * against a mocked API that answers the reload with the three new supplies, the way the server does.
 *
 * <p>Around it, the things that would pass a read-through and fail at the temple:
 * <ul>
 *   <li>A price typed per pack is sent as `pricePerPack` with `lastPrice` null. The server refuses a
 *       per-unit price beside a pack (KMS-400163), so the other way round is a refused save.</li>
 *   <li>A price typed with no pack is the per-unit list price, and the pack pair goes as null.</li>
 *   <li>A row hidden by the search is still saved if it was ticked: the count says so.</li>
 *   <li>A new pack size is saved on the ingredient, then chosen in the row.</li>
 * </ul>
 */

const { authRef } = vi.hoisted(() => ({
  // A stable object: useAuthedQuery depends on getToken's identity.
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me" },
      getToken: async () => "test-token",
      refresh: () => {},
    },
  },
}));

const mocks = vi.hoisted(() => ({
  getVendor: vi.fn(),
  listIngredients: vi.fn(),
  // Who holds each ingredient's preference (R-VEN-2, T-258). Nobody, here: this file is about
  // onboarding, and preferred-replaces.test.tsx is about the "replaces" words.
  listPreferredVendors: vi.fn(),
  setVendorSupply: vi.fn(),
  addVendorSupplies: vi.fn(),
  addPackSize: vi.fn(),
  removeVendorSupply: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "v1" }),
  usePathname: () => "/vendors/v1",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import VendorDetailPage from "@/app/vendors/[id]/page";

function vendor(): VendorView {
  return {
    id: "v1",
    name: "Kalasipalya Wholesale",
    contactPerson: null,
    phone: null,
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "en",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active: true,
    whatsappReachable: false,
    createdAt: "2026-08-01T00:00:00Z",
  };
}

const BAG = { id: "pk-bag", name: "Bag", quantity: 25, unit: "KG", baseQuantity: 25, label: "Bag = 25 Kg" };

function ingredient(o: Partial<IngredientView> & { id: string; name: string }): IngredientView {
  return {
    category: "Grains",
    unit: "KG",
    packSizes: [],
    marketRate: null,
    marketRateOn: null,
    marketRateSource: null,
    ekadashiProhibited: false,
    ...o,
  } as IngredientView;
}

const RICE = ingredient({ id: "rice", name: "Rice", packSizes: [BAG] });
const DAL = ingredient({ id: "dal", name: "Toor dal", category: "Pulses" });
const JAGGERY = ingredient({ id: "jag", name: "Jaggery", category: "Sweeteners" });
const SALT = ingredient({ id: "salt", name: "Salt", category: "Spices" });

function supply(o: Partial<VendorSupplyView> & { ingredientId: string; ingredientName: string }): VendorSupplyView {
  return {
    lastPrice: null,
    unit: "KG",
    packSizeId: null,
    packLabel: null,
    pricePerPack: null,
    previousPrice: null,
    previousPriceOn: null,
    leadTimeDays: null,
    preferred: false,
    ...o,
  };
}

function detail(supplies: VendorSupplyView[]): VendorDetailView {
  return { vendor: vendor(), supplies, statusHistory: [] };
}

const other = () => screen.getByRole("form", { name: "Other ingredients" });
/** The supplies table: the first table on the page, the one outside the Other ingredients form. */
const suppliesTable = () => screen.getAllByRole("table").find((t) => !other().contains(t))!;
const save = () => fireEvent.click(within(other()).getByRole("button", { name: "Save" }));
const sentRows = () => mocks.addVendorSupplies.mock.calls[0][1] as Record<string, unknown>[];

beforeEach(() => {
  mocks.getVendor.mockReset().mockResolvedValue(detail([]));
  mocks.listIngredients.mockReset().mockResolvedValue([RICE, DAL, JAGGERY, SALT]);
  mocks.listPreferredVendors.mockReset().mockResolvedValue([]);
  mocks.setVendorSupply.mockReset().mockResolvedValue(undefined);
  mocks.addVendorSupplies.mockReset().mockResolvedValue(undefined);
  mocks.addPackSize.mockReset().mockResolvedValue({ id: "pk-new" });
  mocks.removeVendorSupply.mockReset().mockResolvedValue(undefined);
});

describe("Other ingredients (R-VEN-1)", () => {
  it("AC: tick 3, fill two prices, Save — all 3 are in the top table and gone from the bottom one", async () => {
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Rice");

    // Three ticked; Rice priced per bag, Toor dal per Kg, Jaggery left without a price.
    fireEvent.click(within(other()).getByLabelText("Rice"));
    fireEvent.change(within(other()).getByLabelText("Sells it as, for Rice"), { target: { value: "pk-bag" } });
    fireEvent.change(within(other()).getByLabelText("List price (₹) for Rice"), { target: { value: "1500" } });
    fireEvent.click(within(other()).getByLabelText("Toor dal"));
    fireEvent.change(within(other()).getByLabelText("List price (₹) for Toor dal"), { target: { value: "120" } });
    fireEvent.click(within(other()).getByLabelText("Jaggery"));
    expect(within(other()).getByText("3 ticked")).toBeTruthy();

    // What the server answers the reload with once the three are saved.
    mocks.getVendor.mockResolvedValue(
      detail([
        supply({ ingredientId: "rice", ingredientName: "Rice", packSizeId: "pk-bag", packLabel: "Bag = 25 Kg", pricePerPack: 1500, lastPrice: 60 }),
        supply({ ingredientId: "dal", ingredientName: "Toor dal", lastPrice: 120 }),
        supply({ ingredientId: "jag", ingredientName: "Jaggery" }),
      ])
    );
    save();

    await waitFor(() => expect(mocks.addVendorSupplies).toHaveBeenCalledTimes(1));
    expect(mocks.addVendorSupplies.mock.calls[0][0]).toBe("v1");
    expect(sentRows()).toEqual([
      { ingredientId: "rice", lastPrice: null, leadTimeDays: null, preferred: false, packSizeId: "pk-bag", pricePerPack: 1500 },
      { ingredientId: "dal", lastPrice: 120, leadTimeDays: null, preferred: false, packSizeId: null, pricePerPack: null },
      { ingredientId: "jag", lastPrice: null, leadTimeDays: null, preferred: false, packSizeId: null, pricePerPack: null },
    ]);

    await waitFor(() => expect(within(suppliesTable()).getByText("Jaggery")).toBeTruthy());
    const top = suppliesTable();
    for (const name of ["Rice", "Toor dal", "Jaggery"]) {
      expect(within(top).getByText(name)).toBeTruthy();
      expect(within(other()).queryByLabelText(name)).toBeNull();
    }
    // Salt was not ticked and stays below.
    expect(within(other()).getByLabelText("Salt")).toBeTruthy();
    expect(screen.getByText("3 ingredients added to Supplies.")).toBeTruthy();
  });

  it("shows the per-Kg price worked out from a bag price as it is typed", async () => {
    render(<VendorDetailPage />);
    const box = await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("List price (₹) for Rice");
    fireEvent.change(within(other()).getByLabelText("Sells it as, for Rice"), { target: { value: "pk-bag" } });
    fireEvent.change(box, { target: { value: "1500" } });

    const cell = box.closest("td")!;
    expect(cell.textContent).toContain("/ bag");
    expect(cell.textContent).toContain("= ₹60 / Kg");
  });

  it("ticks a row when something is typed into it, so a price is never dropped for a missing tick", async () => {
    render(<VendorDetailPage />);
    const box = await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("List price (₹) for Salt");
    fireEvent.change(box, { target: { value: "20" } });
    expect((within(other()).getByLabelText("Salt") as HTMLInputElement).checked).toBe(true);
  });

  it("filters by name and by category, and still saves a ticked row the filter hides", async () => {
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Salt");
    fireEvent.click(within(other()).getByLabelText("Salt"));

    fireEvent.change(within(other()).getByRole("searchbox"), { target: { value: "dal" } });
    expect(within(other()).getByLabelText("Toor dal")).toBeTruthy();
    expect(within(other()).queryByLabelText("Salt")).toBeNull();
    fireEvent.click(within(other()).getByLabelText("Toor dal"));

    fireEvent.change(within(other()).getByRole("searchbox"), { target: { value: "" } });
    fireEvent.change(within(other()).getByLabelText("Category"), { target: { value: "Sweeteners" } });
    expect(within(other()).getByLabelText("Jaggery")).toBeTruthy();
    expect(within(other()).queryByLabelText("Rice")).toBeNull();

    expect(within(other()).getByText("2 ticked")).toBeTruthy();
    save();
    await waitFor(() => expect(mocks.addVendorSupplies).toHaveBeenCalled());
    expect(sentRows().map((r) => r.ingredientId).sort()).toEqual(["dal", "salt"]);
  });

  it("sends nothing when nothing is ticked, and says why", async () => {
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Salt");
    save();
    expect(await screen.findByText("Tick at least one ingredient to add.")).toBeTruthy();
    expect(mocks.addVendorSupplies).not.toHaveBeenCalled();
  });

  it("defines a new pack size from the row, saves it on the ingredient, and chooses it", async () => {
    render(<VendorDetailPage />);
    const sells = await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Sells it as, for Toor dal");
    expect(Array.from((sells as HTMLSelectElement).options).map((o) => o.textContent)).toEqual(["Kg", "Add a pack size…"]);

    // The reload after the pack is saved brings it back on the ingredient.
    const withPack = ingredient({
      id: "dal", name: "Toor dal", category: "Pulses",
      packSizes: [{ id: "pk-new", name: "Bag", quantity: 30, unit: "KG", baseQuantity: 30, label: "Bag = 30 Kg" }],
    });
    mocks.listIngredients.mockResolvedValue([RICE, withPack, JAGGERY, SALT]);

    fireEvent.change(sells, { target: { value: "__add-pack" } });
    const group = await screen.findByRole("group", { name: "New pack size for Toor dal" });
    fireEvent.change(within(group).getByLabelText("Pack name (optional)"), { target: { value: "Bag" } });
    fireEvent.change(within(group).getByLabelText("Size"), { target: { value: "30" } });
    // Only the ingredient's own family is offered.
    expect(Array.from((within(group).getByLabelText("Unit") as HTMLSelectElement).options).map((o) => o.value)).toEqual(["KG", "GM"]);
    fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));

    await waitFor(() => expect(mocks.addPackSize).toHaveBeenCalledWith("dal", { name: "Bag", quantity: 30, unit: "KG" }, "test-token"));
    await waitFor(() => expect((within(other()).getByLabelText("Sells it as, for Toor dal") as HTMLSelectElement).value).toBe("pk-new"));
    expect(screen.queryByRole("group", { name: "New pack size for Toor dal" })).toBeNull();
    // Choosing a pack ticks the row, like typing into it.
    expect((within(other()).getByLabelText("Toor dal") as HTMLInputElement).checked).toBe(true);
  });

  it("refuses a pack size with no size before calling the server", async () => {
    render(<VendorDetailPage />);
    const sells = await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Sells it as, for Salt");
    fireEvent.change(sells, { target: { value: "__add-pack" } });
    const group = await screen.findByRole("group", { name: "New pack size for Salt" });
    fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));
    expect(await screen.findByText("Size must be more than 0")).toBeTruthy();
    expect(mocks.addPackSize).not.toHaveBeenCalled();
  });
});

describe("the supplies table (R-VEN-1, R-VEN-3)", () => {
  it("heads the price List price, not Last price, and has no 'Edit a row' sentence", async () => {
    mocks.getVendor.mockResolvedValue(detail([supply({ ingredientId: "rice", ingredientName: "Rice", lastPrice: 60 })]));
    render(<VendorDetailPage />);
    await screen.findByText("Rice", { selector: "td" });
    const heads = within(suppliesTable()).getAllByRole("columnheader").map((h) => h.textContent);
    expect(heads.slice(0, 5)).toEqual(["Ingredient", "Sells it as", "List price", "Lead time", "Preferred"]);
    expect(screen.queryByText(/last price/i)).toBeNull();
    expect(screen.queryByText(/edit a row to change/i)).toBeNull();
  });

  it("shows a bag supply as '₹1,500 / bag · ₹60 / Kg', sold as the bag", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([supply({ ingredientId: "rice", ingredientName: "Rice", packSizeId: "pk-bag", packLabel: "Bag = 25 Kg", pricePerPack: 1500, lastPrice: 60 })])
    );
    render(<VendorDetailPage />);
    const row = (await screen.findByText("Rice", { selector: "td" })).closest("tr")!;
    const cells = Array.from(row.querySelectorAll("td")).map((c) => c.textContent);
    expect(cells[1]).toBe("Bag = 25 Kg");
    expect(cells[2]).toBe("₹1,500 / bag · ₹60 / Kg");
  });

  it("says a price kept per gram per Kg, so two close prices do not look identical", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([supply({ ingredientId: "car", ingredientName: "Cardamom", unit: "GM", lastPrice: 0.068, previousPrice: 0.065, previousPriceOn: "2026-09-12" })])
    );
    render(<VendorDetailPage />);
    const row = (await screen.findByText("Cardamom", { selector: "td" })).closest("tr")!;
    expect(row.querySelectorAll("td")[2].textContent).toBe("₹68 / Kg");
    expect(within(row).getByRole("img", { name: "Up from ₹65 on 12 Sept" })).toBeTruthy();
  });

  it("puts the up arrow beside a risen price, with the old price and date on focus", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([supply({ ingredientId: "rice", ingredientName: "Rice", lastPrice: 64, previousPrice: 60, previousPriceOn: "2026-09-12" })])
    );
    render(<VendorDetailPage />);
    const row = (await screen.findByText("Rice", { selector: "td" })).closest("tr")!;
    const marker = within(row).getByRole("img", { name: "Up from ₹60 on 12 Sept" });
    fireEvent.focus(marker);
    expect((await screen.findByRole("tooltip")).textContent).toBe("₹60 on 12 Sept");
  });

  it("edits a bag supply: opens holding the bag price, and sends price per pack only", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([supply({ ingredientId: "rice", ingredientName: "Rice", packSizeId: "pk-bag", packLabel: "Bag = 25 Kg", pricePerPack: 1500, lastPrice: 60, leadTimeDays: 2, preferred: true })])
    );
    render(<VendorDetailPage />);
    const row0 = (await screen.findByText("Rice", { selector: "td" })).closest("tr")!;
    fireEvent.click(within(row0).getByRole("button", { name: "Edit" }));
    const row = (await within(suppliesTable()).findByRole("button", { name: "Save" })).closest("tr")!;

    const price = within(row).getByLabelText("List price (₹)", { selector: "input" }) as HTMLInputElement;
    expect(price.value).toBe("1500");
    expect((within(row).getByLabelText("Sells it as") as HTMLSelectElement).value).toBe("pk-bag");
    fireEvent.change(price, { target: { value: "1600" } });
    expect(row.textContent).toContain("= ₹64 / Kg");
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(mocks.setVendorSupply).toHaveBeenCalled());
    expect(mocks.setVendorSupply.mock.calls[0][1]).toEqual({
      ingredientId: "rice",
      lastPrice: null,
      leadTimeDays: 2,
      preferred: true,
      packSizeId: "pk-bag",
      pricePerPack: 1600,
    });
  });

  it("moves a bag supply back to loose Kg: the pack pair goes as null and the price is per Kg", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([supply({ ingredientId: "rice", ingredientName: "Rice", packSizeId: "pk-bag", packLabel: "Bag = 25 Kg", pricePerPack: 1500, lastPrice: 60 })])
    );
    render(<VendorDetailPage />);
    const row0 = (await screen.findByText("Rice", { selector: "td" })).closest("tr")!;
    fireEvent.click(within(row0).getByRole("button", { name: "Edit" }));
    const row = (await within(suppliesTable()).findByRole("button", { name: "Save" })).closest("tr")!;
    fireEvent.change(within(row).getByLabelText("Sells it as"), { target: { value: "" } });
    fireEvent.change(within(row).getByLabelText("List price (₹)", { selector: "input" }), { target: { value: "62" } });
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(mocks.setVendorSupply).toHaveBeenCalled());
    expect(mocks.setVendorSupply.mock.calls[0][1]).toMatchObject({ lastPrice: 62, packSizeId: null, pricePerPack: null });
  });
});
