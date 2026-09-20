import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  IngredientSupplyView,
  IngredientView,
  PreferredVendorView,
  VendorView,
} from "@/lib/api";

/**
 * An ingredient's own page (Q-10, T-286): the facts no other screen shows, pack sizes (R-ING-1), the
 * market rate (R-ING-3) and the ingredient's vendors with the link dropdown (R-ING-2), including
 * "Preferred (replaces A)" (R-VEN-2) and the trend arrows (R-VEN-3).
 *
 * <p>The vendor link is checked against the vendor page's own body, not against a copy of it written
 * here: the same six keys the vendor page's Other ingredients → Save sends, to the same endpoint, and
 * an edit sends what the vendor page's Edit sends.
 */

const { authRef } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me" } as { role: string; userId: string },
      getToken: async () => "test-token",
      refresh: () => {},
    },
  },
}));

const mocks = vi.hoisted(() => ({
  getIngredient: vi.fn(),
  listIngredientSupplies: vi.fn(),
  listVendors: vi.fn(),
  listPreferredVendors: vi.fn(),
  addPackSize: vi.fn(),
  removePackSize: vi.fn(),
  setMarketRate: vi.fn(),
  setIngredientNotBought: vi.fn(),
  setVendorSupply: vi.fn(),
  addVendorSupplies: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "rice" }),
  usePathname: () => "/ingredients/rice",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import IngredientDetailPage from "@/app/ingredients/[id]/page";
import { PACK_SIZES_HINT } from "@/components/ingredient/PackSizes";
import { MarketRate } from "@/components/ingredient/MarketRate";
import { can, holdersOf } from "@/components/ingredient/access";

function rice(o: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "rice",
    name: "Sona Masoori rice",
    category: "Grains",
    unit: "KG",
    packSizes: [
      { id: "p250", name: null, quantity: 250, unit: "GM", baseQuantity: 0.25, label: "250 gm" },
      { id: "p500", name: null, quantity: 500, unit: "GM", baseQuantity: 0.5, label: "500 gm" },
      { id: "p1", name: null, quantity: 1, unit: "KG", baseQuantity: 1, label: "1 Kg" },
      { id: "bag", name: "Bag", quantity: 25, unit: "KG", baseQuantity: 25, label: "Bag = 25 Kg" },
    ],
    marketRate: 60,
    marketRateOn: "2026-09-12",
    marketRateSource: "MANUAL",
    ekadashiProhibited: true,
    supply: false,
    notBought: false,
    libraryDerived: true,
    aliases: ["Sona masuri", "Ponni rice"],
    createdAt: "2026-08-20T09:00:00Z",
    ...o,
  };
}

function vendor(id: string, name: string, active = true): VendorView {
  return {
    id,
    name,
    contactPerson: null,
    phone: null,
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "en",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active,
    whatsappReachable: false,
    createdAt: "2026-08-01T00:00:00Z",
  };
}

function supply(o: Partial<IngredientSupplyView> & { vendorId: string; vendorName: string }): IngredientSupplyView {
  return {
    ingredientId: "rice",
    ingredientName: "Sona Masoori rice",
    lastPrice: 60,
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

const ANAND = vendor("v-a", "Anand Stores");
const BHAVANI = vendor("v-b", "Bhavani Traders");
const CHETAN = vendor("v-c", "Chetan Wholesale");

const held = (vendorId: string, vendorName: string): PreferredVendorView => ({ ingredientId: "rice", vendorId, vendorName });

function setRole(role: string) {
  authRef.current = { ...authRef.current, appUser: { role, userId: "me" } };
}

beforeEach(() => {
  setRole("TEMPLE_ADMIN");
  mocks.getIngredient.mockReset().mockResolvedValue(rice());
  mocks.listIngredientSupplies.mockReset().mockResolvedValue([
    // Anand sells it by the bag, preferred, and put the price up from ₹56 / Kg.
    supply({
      vendorId: "v-a",
      vendorName: "Anand Stores",
      packSizeId: "bag",
      packLabel: "Bag = 25 Kg",
      pricePerPack: 1500,
      lastPrice: 60,
      previousPrice: 56,
      previousPriceOn: "2026-09-01",
      leadTimeDays: 2,
      preferred: true,
    }),
  ]);
  mocks.listVendors.mockReset().mockResolvedValue([ANAND, BHAVANI, CHETAN, vendor("v-z", "Zed Closed", false)]);
  mocks.listPreferredVendors.mockReset().mockResolvedValue([held("v-a", "Anand Stores")]);
  mocks.addPackSize.mockReset().mockResolvedValue({ id: "new" });
  mocks.removePackSize.mockReset().mockResolvedValue(undefined);
  mocks.setMarketRate.mockReset().mockResolvedValue(undefined);
  mocks.setIngredientNotBought.mockReset().mockResolvedValue(undefined);
  mocks.setVendorSupply.mockReset().mockResolvedValue(undefined);
  mocks.addVendorSupplies.mockReset().mockResolvedValue(undefined);
});

async function renderPage() {
  render(<IngredientDetailPage />);
  await screen.findByRole("heading", { level: 1, name: "Sona Masoori rice" });
}

const section = (name: string) => screen.getByRole("region", { name });

async function apiError(code: string, message: string) {
  const { ApiError } = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return new ApiError({ code, message, action: "Check it and try again.", fieldErrors: [] }, 400);
}

describe("the page", () => {
  it("fetches the ingredient by the id in the address", async () => {
    await renderPage();
    expect(mocks.getIngredient).toHaveBeenCalledWith("rice", "test-token");
  });

  it("shows the facts no other screen shows: other names (merged names included) and when it was added", async () => {
    await renderPage();
    const facts = section("About this ingredient");
    expect(within(facts).getByText("Sona masuri, Ponni rice")).toBeInTheDocument();
    expect(within(facts).getByText("20 Aug 2026")).toBeInTheDocument();
    expect(within(facts).getByText("Grains")).toBeInTheDocument();
    expect(within(facts).getByText("Prohibited")).toBeInTheDocument();
    // The import label, in the list's words.
    expect(screen.getByText("Added by a Recipe Import")).toBeInTheDocument();
  });

  it("does not print the food/supply type (Rajeev, 2026-09-10)", async () => {
    await renderPage();
    expect(screen.queryByText(/^(Supply|Food)$/)).not.toBeInTheDocument();
  });

  /*
    T-402. Printed, unlike the food/supply type above, and the difference is not a reversal of that
    ruling: which half of the catalogue a row belongs to shows itself in the menu item it lives
    under, so printing it repeats what the screen already says. Whether the temple buys the thing
    shows itself nowhere — the consequence is a shopping-list line that is simply absent — so this
    page is the only place a person can find out.
  */
  it("prints that the temple buys it, when it does", async () => {
    await renderPage();
    expect(within(section("About this ingredient")).getByText("Bought when needed")).toBeInTheDocument();
  });

  it("prints the mark when the temple never buys it, in the two words every screen uses", async () => {
    mocks.getIngredient.mockResolvedValue(rice({ notBought: true }));
    await renderPage();
    expect(within(section("About this ingredient")).getByText("Not bought")).toBeInTheDocument();
  });

  it("lets a Temple Admin set the mark, through the route of its own", async () => {
    await renderPage();
    fireEvent.click(within(section("About this ingredient")).getByLabelText(/not bought/i));
    await waitFor(() =>
      expect(mocks.setIngredientNotBought).toHaveBeenCalledWith("rice", true, "test-token")
    );
  });

  it("offers a Kitchen Manager no control, and still tells them the answer", async () => {
    // MANAGE_BUYING_POLICY is the Temple Admin's alone, and it is the only key in the page's
    // permission map that is not held by all three roles that can open the page.
    setRole("KITCHEN_MANAGER");
    mocks.getIngredient.mockResolvedValue(rice({ notBought: true }));
    await renderPage();
    const facts = section("About this ingredient");
    expect(within(facts).getByText("Not bought")).toBeInTheDocument();
    expect(within(facts).queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("draws no price history: the chart is after UAT", async () => {
    await renderPage();
    expect(screen.queryByText(/price history/i)).not.toBeInTheDocument();
  });
});

describe("pack sizes (R-ING-1)", () => {
  it("renders the server's chip labels, grams as gm", async () => {
    await renderPage();
    const chips = within(section("Pack sizes")).getByRole("list", { name: "Pack sizes" });
    expect(within(chips).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "250 gm",
      "500 gm",
      "1 Kg",
      "Bag = 25 Kg",
    ]);
  });

  it("has the hint, word for word", async () => {
    await renderPage();
    expect(PACK_SIZES_HINT).toBe("The pack sizes vendors sell this in. The shopping list suggests whole packs.");
    fireEvent.focus(screen.getByRole("button", { name: "More about Pack sizes" }));
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "The pack sizes vendors sell this in. The shopping list suggests whole packs."
    );
  });

  it("adds a pack with a name, a size and a unit of the same family, then rereads the ingredient", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Add a pack size" });
    fireEvent.change(within(form).getByLabelText("Pack name (optional)"), { target: { value: "Sack" } });
    fireEvent.change(within(form).getByLabelText("Size"), { target: { value: "50" } });
    // Only the mass family is offered for rice.
    const unit = within(form).getByLabelText("Unit") as HTMLSelectElement;
    expect(Array.from(unit.options).map((o) => o.textContent)).toEqual(["Kg", "gm"]);
    fireEvent.click(within(form).getByRole("button", { name: "Add pack size" }));
    await waitFor(() =>
      expect(mocks.addPackSize).toHaveBeenCalledWith("rice", { name: "Sack", quantity: 50, unit: "KG" }, "test-token")
    );
    await waitFor(() => expect(mocks.getIngredient).toHaveBeenCalledTimes(2));
  });

  it("refuses a blank or nought size without asking the server", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Add a pack size" });
    fireEvent.click(within(form).getByRole("button", { name: "Add pack size" }));
    expect(await screen.findByText("Size is required")).toBeInTheDocument();
    fireEvent.change(within(form).getByLabelText("Size"), { target: { value: "0" } });
    fireEvent.click(within(form).getByRole("button", { name: "Add pack size" }));
    expect(await screen.findByText("Size must be more than 0")).toBeInTheDocument();
    expect(mocks.addPackSize).not.toHaveBeenCalled();
  });

  it("shows the server's refusal (a duplicate) in its own words", async () => {
    mocks.addPackSize.mockRejectedValue(await apiError("KMS-400157", "Rice already has a 1 Kg pack."));
    await renderPage();
    const form = screen.getByRole("form", { name: "Add a pack size" });
    fireEvent.change(within(form).getByLabelText("Size"), { target: { value: "1" } });
    fireEvent.click(within(form).getByRole("button", { name: "Add pack size" }));
    expect(await within(section("Pack sizes")).findByRole("alert")).toHaveTextContent("Rice already has a 1 Kg pack.");
  });

  it("removes a pack, and shows the refusal when one is in use", async () => {
    await renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Remove 500 gm" }));
    await waitFor(() => expect(mocks.removePackSize).toHaveBeenCalledWith("rice", "p500", "test-token"));

    mocks.removePackSize.mockRejectedValue(await apiError("KMS-400159", "A vendor sells rice in this pack."));
    fireEvent.click(screen.getByRole("button", { name: "Remove Bag = 25 Kg" }));
    expect(await within(section("Pack sizes")).findByRole("alert")).toHaveTextContent("A vendor sells rice in this pack.");
  });
});

describe("market rate (R-ING-3)", () => {
  it("reads 'Market rate · ₹60 / Kg · 12 Sept', and says where it came from", async () => {
    await renderPage();
    expect(screen.getByTestId("market-rate-line")).toHaveTextContent(/^Market rate · ₹60 \/ Kg · 12 Sept$/);
    expect(screen.getByText("Typed in by hand")).toBeInTheDocument();
  });

  it("says a rate kept per gram per Kg", async () => {
    mocks.getIngredient.mockResolvedValue(rice({ unit: "GM", marketRate: 0.06, packSizes: [] }));
    await renderPage();
    expect(screen.getByTestId("market-rate-line")).toHaveTextContent("Market rate · ₹60 / Kg · 12 Sept");
  });

  it("is editable by a holder of MANAGE_INVENTORY and sends the rate per stock unit", async () => {
    mocks.getIngredient.mockResolvedValue(rice({ unit: "GM", marketRate: 0.06, packSizes: [] }));
    await renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Change market rate" }));
    const box = screen.getByLabelText("Market rate (₹ per Kg)");
    expect(box).toHaveValue(60);
    fireEvent.change(box, { target: { value: "64" } });
    fireEvent.click(within(screen.getByRole("form", { name: "Change market rate" })).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(mocks.setMarketRate).toHaveBeenCalledWith("rice", 0.064, "test-token"));
  });

  it("refuses a nought rate here, and shows the server's refusal when it comes", async () => {
    await renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Change market rate" }));
    const form = screen.getByRole("form", { name: "Change market rate" });
    fireEvent.change(within(form).getByLabelText("Market rate (₹ per Kg)"), { target: { value: "0" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Market rate must be more than 0")).toBeInTheDocument();
    expect(mocks.setMarketRate).not.toHaveBeenCalled();

    mocks.setMarketRate.mockRejectedValue(await apiError("KMS-400999", "That rate couldn’t be saved."));
    fireEvent.change(within(form).getByLabelText("Market rate (₹ per Kg)"), { target: { value: "61" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save" }));
    expect(await within(section("Price")).findByRole("alert")).toHaveTextContent("That rate couldn’t be saved.");
  });

  it("offers 'Set market rate' when there is none", async () => {
    mocks.getIngredient.mockResolvedValue(rice({ marketRate: null, marketRateOn: null, marketRateSource: null }));
    await renderPage();
    expect(screen.getByTestId("market-rate-line")).toHaveTextContent("Market rate · not set");
    expect(screen.getByRole("button", { name: "Set market rate" })).toBeInTheDocument();
  });

  it("offers no editing control without MANAGE_INVENTORY, but still shows the rate", () => {
    // Every role that can open the page holds MANAGE_INVENTORY today, so the gate is exercised on
    // the section itself, as the page passes it.
    render(<MarketRate ingredient={rice()} canEdit={false} onChanged={() => {}} />);
    expect(screen.getByTestId("market-rate-line")).toHaveTextContent("Market rate · ₹60 / Kg · 12 Sept");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("the page's gate: the market rate follows MANAGE_INVENTORY, the page MANAGE_RECIPES", () => {
    expect(can("MANAGE_INVENTORY", "KITCHEN_STAFF")).toBe(true);
    expect(can("MANAGE_INVENTORY", "VOLUNTEER")).toBe(false);
    expect(can("MANAGE_INVENTORY", undefined)).toBe(false);
    expect(holdersOf("MANAGE_RECIPES")).toEqual(["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]);
    // T-402: the first one-role entry in the map, so the two helpers are exercised on it —
    // `holdersOf` hands `RequireRole` a one-element array and `can` is still a plain includes.
    expect(holdersOf("MANAGE_BUYING_POLICY")).toEqual(["TEMPLE_ADMIN"]);
    expect(can("MANAGE_BUYING_POLICY", "TEMPLE_ADMIN")).toBe(true);
    expect(can("MANAGE_BUYING_POLICY", "KITCHEN_MANAGER")).toBe(false);
    expect(can("MANAGE_BUYING_POLICY", "KITCHEN_STAFF")).toBe(false);
    expect(can("MANAGE_BUYING_POLICY", null)).toBe(false);
    expect(can("MANAGE_BUYING_POLICY", undefined)).toBe(false);
  });

  it("a volunteer is refused the page and nothing is fetched", async () => {
    setRole("VOLUNTEER");
    render(<IngredientDetailPage />);
    await waitFor(() => expect(mocks.getIngredient).not.toHaveBeenCalled());
    expect(screen.queryByRole("heading", { level: 1, name: "Sona Masoori rice" })).not.toBeInTheDocument();
  });
});

describe("vendors (R-ING-2, R-VEN-2, R-VEN-3)", () => {
  it("lists the ingredient's vendors with list prices as the vendor page writes them, and the arrow", async () => {
    await renderPage();
    const vendors = section("Vendors");
    const row = within(vendors).getByRole("link", { name: "Anand Stores" }).closest("tr") as HTMLElement;
    expect(row).toHaveTextContent("Bag = 25 Kg");
    expect(row).toHaveTextContent("₹1,500 / bag · ₹60 / Kg");
    expect(row).toHaveTextContent("2 days");
    const arrow = within(row).getByRole("img", { name: "Up from ₹1,400 on 1 Sept" });
    expect(arrow).toHaveClass("text-danger");
    expect(arrow.getAttribute("data-direction")).toBe("up");
  });

  it("puts a green down arrow on a price that fell, and nothing where there is no earlier price", async () => {
    mocks.listIngredientSupplies.mockResolvedValue([
      supply({ vendorId: "v-a", vendorName: "Anand Stores", lastPrice: 58, previousPrice: 60, previousPriceOn: "2026-09-01" }),
      supply({ vendorId: "v-b", vendorName: "Bhavani Traders", lastPrice: 62 }),
    ]);
    await renderPage();
    const vendors = section("Vendors");
    expect(within(vendors).getByRole("img", { name: "Down from ₹60 on 1 Sept" })).toHaveClass("text-success");
    const bhavani = within(vendors).getByRole("link", { name: "Bhavani Traders" }).closest("tr") as HTMLElement;
    expect(within(bhavani).queryByRole("img")).not.toBeInTheDocument();
  });

  it("offers only active vendors who don't already sell it", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    const select = within(form).getByLabelText("Vendor") as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual([
      "Choose a vendor",
      "Bhavani Traders",
      "Chetan Wholesale",
    ]);
    expect(mocks.listVendors).toHaveBeenCalledWith(true, "test-token");
  });

  it("links a vendor with the same body the vendor page's Other ingredients sends, to the same endpoint", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    fireEvent.change(within(form).getByLabelText("Vendor"), { target: { value: "v-b" } });
    fireEvent.change(within(form).getByLabelText("Sells it as"), { target: { value: "bag" } });
    fireEvent.change(within(form).getByLabelText("List price (₹)"), { target: { value: "1450" } });
    // The per-Kg figure is worked out, never a second box.
    expect(within(form).getByText("= ₹58 / Kg")).toBeInTheDocument();
    fireEvent.change(within(form).getByLabelText("Lead time (days)", { selector: "input" }), { target: { value: "0" } });
    fireEvent.click(within(form).getByRole("button", { name: "Link vendor" }));
    await waitFor(() => expect(mocks.addVendorSupplies).toHaveBeenCalledTimes(1));
    const [vendorId, rows, token] = mocks.addVendorSupplies.mock.calls[0];
    expect(vendorId).toBe("v-b");
    expect(token).toBe("test-token");
    expect(rows).toEqual([
      { ingredientId: "rice", lastPrice: null, leadTimeDays: 0, preferred: false, packSizeId: "bag", pricePerPack: 1450 },
    ]);
    // All six keys, present even when null, as the vendor page sends them.
    expect(Object.keys(rows[0]).sort()).toEqual(
      ["ingredientId", "lastPrice", "leadTimeDays", "packSizeId", "preferred", "pricePerPack"].sort()
    );
    // The rows and the preference are reread.
    await waitFor(() => expect(mocks.listIngredientSupplies).toHaveBeenCalledTimes(2));
    expect(mocks.listPreferredVendors).toHaveBeenCalledTimes(2);
  });

  it("sends a loose price as lastPrice with both pack keys null, and a blank lead time as null", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    fireEvent.change(within(form).getByLabelText("Vendor"), { target: { value: "v-c" } });
    fireEvent.change(within(form).getByLabelText("List price (₹)"), { target: { value: "59" } });
    fireEvent.click(within(form).getByRole("button", { name: "Link vendor" }));
    await waitFor(() =>
      expect(mocks.addVendorSupplies).toHaveBeenCalledWith(
        "v-c",
        [{ ingredientId: "rice", lastPrice: 59, leadTimeDays: null, preferred: false, packSizeId: null, pricePerPack: null }],
        "test-token"
      )
    );
  });

  it("asks for a vendor rather than sending nothing", async () => {
    await renderPage();
    fireEvent.click(within(screen.getByRole("form", { name: "Link a vendor" })).getByRole("button", { name: "Link vendor" }));
    expect(await screen.findByText("Choose a vendor to link.")).toBeInTheDocument();
    expect(mocks.addVendorSupplies).not.toHaveBeenCalled();
  });

  it("says 'Preferred (replaces Anand Stores)' before saving, when another vendor holds it", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    fireEvent.change(within(form).getByLabelText("Vendor"), { target: { value: "v-b" } });
    expect(within(form).queryByText("Preferred (replaces Anand Stores)")).not.toBeInTheDocument();
    fireEvent.click(within(form).getByRole("checkbox"));
    const words = within(form).getByText("Preferred (replaces Anand Stores)");
    // Neutral, never amber (colour means something).
    expect(words).toHaveClass("text-ink-secondary");
    expect(words.className).not.toMatch(/warning/);
    expect(mocks.addVendorSupplies).not.toHaveBeenCalled();
  });

  it("names nobody when no vendor holds the preference", async () => {
    mocks.listPreferredVendors.mockResolvedValue([]);
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    fireEvent.change(within(form).getByLabelText("Vendor"), { target: { value: "v-b" } });
    fireEvent.click(within(form).getByRole("checkbox"));
    expect(within(form).queryByText(/replaces/)).not.toBeInTheDocument();
    expect(within(form).getByText("Preferred")).toBeInTheDocument();
  });

  it("edits an existing link with the vendor page's Edit body, and says whom a tick replaces", async () => {
    mocks.listIngredientSupplies.mockResolvedValue([
      supply({ vendorId: "v-a", vendorName: "Anand Stores", preferred: true, lastPrice: 60 }),
      supply({ vendorId: "v-b", vendorName: "Bhavani Traders", lastPrice: 62, leadTimeDays: 3 }),
    ]);
    await renderPage();
    const vendors = section("Vendors");
    const row = () => within(vendors).getByText("Bhavani Traders").closest("tr") as HTMLElement;
    fireEvent.click(within(row()).getByRole("button", { name: "Edit" }));
    fireEvent.click(within(row()).getByRole("checkbox"));
    expect(within(row()).getByText("Preferred (replaces Anand Stores)")).toBeInTheDocument();
    fireEvent.click(within(row()).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(mocks.setVendorSupply).toHaveBeenCalledWith(
        "v-b",
        { ingredientId: "rice", lastPrice: 62, leadTimeDays: 3, preferred: true, packSizeId: null, pricePerPack: null },
        "test-token"
      )
    );
  });

  it("shows the server's refusal of a link", async () => {
    mocks.addVendorSupplies.mockRejectedValue(await apiError("KMS-400163", "Give the price per pack or per unit, not both."));
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    fireEvent.change(within(form).getByLabelText("Vendor"), { target: { value: "v-b" } });
    fireEvent.click(within(form).getByRole("button", { name: "Link vendor" }));
    expect(await within(section("Vendors")).findByRole("alert")).toHaveTextContent(
      "Give the price per pack or per unit, not both."
    );
  });
});
