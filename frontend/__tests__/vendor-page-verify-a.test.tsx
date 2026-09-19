import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type IngredientView, type VendorDetailView, type VendorSupplyView } from "@/lib/api";

/**
 * Three defects VERIFY-A found on the vendor page, each pinned here (T-292).
 *
 * <ul>
 *   <li><strong>Defect 2.</strong> A pack-size refusal from the server (KMS-400157, a size the
 *       ingredient already has) stayed on screen after the entry changed, and sat under the next
 *       message: "Size must be more than 0" above "This ingredient already has a pack of that size".
 *       It has to go when the entry changes and on every new press.</li>
 *   <li><strong>Defect 7.</strong> The Lead time "i" in the Other ingredients heading kept taking
 *       keyboard focus on a phone, where that heading row is hidden (1px, clipped), so focus landed
 *       on nothing anybody could see. jsdom applies no stylesheet or media query, so the width is
 *       given to the page through a `matchMedia` stub, the way `staff-schedule-stacked.test.tsx`
 *       does; the real browser at 390 and 1280 is measured in `docs/work/proof/T-292.md`.</li>
 *   <li><strong>Defect 8.</strong> A Supplies card on a phone read "Lead time — —": the second
 *       dash was Preferred, with no label. The label is the cell's `data-label`, which the card
 *       stylesheet prints in front of the value.</li>
 * </ul>
 */

const { authRef } = vi.hoisted(() => ({
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

const BAG = { id: "pk-bag", name: "Bag", quantity: 25, unit: "KG", baseQuantity: 25, label: "Bag = 25 Kg" };

function ingredient(o: Partial<IngredientView> & { id: string; name: string }): IngredientView {
  return {
    category: "Vegetables",
    unit: "KG",
    packSizes: [],
    marketRate: null,
    marketRateOn: null,
    marketRateSource: null,
    ekadashiProhibited: false,
    ...o,
  } as IngredientView;
}

const TOMATO = ingredient({ id: "tom", name: "Tomato", packSizes: [BAG] });
const GINGER = ingredient({ id: "gin", name: "Ginger", unit: "GM" });
const RICE = ingredient({ id: "rice", name: "Rice", category: "Grains" });

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
  return {
    vendor: {
      id: "v1",
      name: "VERIFY-A Onboarding Vendor",
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
    },
    supplies,
    statusHistory: [],
  };
}

const DUPLICATE = new ApiError({
  code: "KMS-400157",
  message: "This ingredient already has a pack of that size.",
  action: "Use the one that’s there, or enter a different size.",
  fieldErrors: [],
});

/** A window as wide as `width`, as far as `matchMedia` is concerned: the only query that matters is lg. */
function stubWidth(width: number) {
  window.matchMedia = ((query: string) => {
    const min = /min-width:\s*(\d+)px/.exec(query);
    const max = /max-width:\s*([\d.]+)px/.exec(query);
    const matches = (min ? width >= Number(min[1]) : true) && (max ? width <= Number(max[1]) : true);
    return {
      matches,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    };
  }) as unknown as typeof window.matchMedia;
}

const other = () => screen.getByRole("form", { name: "Other ingredients" });
const suppliesTable = () => screen.getAllByRole("table").find((t) => !other().contains(t))!;

beforeEach(() => {
  mocks.getVendor.mockReset().mockResolvedValue(
    detail([
      supply({ ingredientId: "gin", ingredientName: "Ginger", unit: "GM", lastPrice: 0.26 }),
      supply({
        ingredientId: "tom",
        ingredientName: "Tomato",
        packSizeId: "pk-bag",
        packLabel: "Bag = 25 Kg",
        pricePerPack: 1500,
        lastPrice: 60,
        preferred: true,
      }),
    ])
  );
  mocks.listIngredients.mockReset().mockResolvedValue([TOMATO, GINGER, RICE]);
  mocks.listPreferredVendors.mockReset().mockResolvedValue([]);
  mocks.setVendorSupply.mockReset().mockResolvedValue(undefined);
  mocks.addVendorSupplies.mockReset().mockResolvedValue(undefined);
  mocks.addPackSize.mockReset();
  mocks.removeVendorSupply.mockReset().mockResolvedValue(undefined);
});

afterEach(() => {
  // jsdom has no matchMedia of its own; put it back to absent so no other test inherits a width.
  delete (window as { matchMedia?: unknown }).matchMedia;
});

/** Opens the pack size fields on the Tomato supply's edit row, VERIFY-A's own route to the defect. */
async function openPackFieldsOnTomato() {
  render(<VendorDetailPage />);
  const row = (await within(await suppliesTableAsync()).findByText("Tomato")).closest("tr")!;
  fireEvent.click(within(row).getByRole("button", { name: "Edit" }));
  const sells = await screen.findByLabelText("Sells it as");
  fireEvent.change(sells, { target: { value: "__add-pack" } });
  return screen.findByRole("group", { name: "New pack size for Tomato" });
}

async function suppliesTableAsync() {
  await screen.findByRole("form", { name: "Other ingredients" });
  return suppliesTable();
}

describe("VERIFY-A defect 2: a pack-size refusal belongs to the entry that caused it", () => {
  it("the exact repro: a duplicate size, then Box 0 and Add — only the new message shows", async () => {
    mocks.addPackSize.mockRejectedValueOnce(DUPLICATE);
    const group = await openPackFieldsOnTomato();
    fireEvent.change(within(group).getByLabelText("Pack name (optional)"), { target: { value: "Sack" } });
    fireEvent.change(within(group).getByLabelText("Size"), { target: { value: "25" } });
    fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));
    expect(await screen.findByText(/already has a pack of that size/)).toBeTruthy();

    fireEvent.change(within(group).getByLabelText("Pack name (optional)"), { target: { value: "Box" } });
    fireEvent.change(within(group).getByLabelText("Size"), { target: { value: "0" } });
    fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));

    expect(await screen.findByText("Size must be more than 0")).toBeTruthy();
    expect(screen.queryByText(/already has a pack of that size/)).toBeNull();
    expect(screen.queryByText(/KMS-400157/)).toBeNull();
    expect(mocks.addPackSize).toHaveBeenCalledTimes(1);
  });

  it("goes the moment the entry changes, before anything is pressed — size, name or unit", async () => {
    mocks.addPackSize.mockRejectedValue(DUPLICATE);
    const group = await openPackFieldsOnTomato();
    const press = () => fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));
    fireEvent.change(within(group).getByLabelText("Size"), { target: { value: "25" } });

    for (const change of [
      () => fireEvent.change(within(group).getByLabelText("Size"), { target: { value: "26" } }),
      () => fireEvent.change(within(group).getByLabelText("Pack name (optional)"), { target: { value: "Sack" } }),
      () => fireEvent.change(within(group).getByLabelText("Unit"), { target: { value: "GM" } }),
    ]) {
      press();
      expect(await screen.findByText(/already has a pack of that size/)).toBeTruthy();
      change();
      expect(screen.queryByText(/already has a pack of that size/)).toBeNull();
    }
  });

  it("goes on a new press too, while that attempt is still with the server", async () => {
    mocks.addPackSize.mockRejectedValueOnce(DUPLICATE).mockReturnValueOnce(new Promise(() => {}));
    const group = await openPackFieldsOnTomato();
    fireEvent.change(within(group).getByLabelText("Size"), { target: { value: "25" } });
    fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));
    expect(await screen.findByText(/already has a pack of that size/)).toBeTruthy();

    // Pressed again with the same entry: the old refusal must not sit there while it is retried.
    fireEvent.click(within(group).getByRole("button", { name: "Add pack size" }));
    await waitFor(() => expect(mocks.addPackSize).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/already has a pack of that size/)).toBeNull();
  });
});

describe("VERIFY-A defect 7: no focus stop inside a hidden heading", () => {
  const headerHint = () =>
    within(other()).queryAllByRole("button", { name: "More about Lead time (days)" }).filter((b) => b.closest("thead"));
  const phoneHint = () =>
    within(other()).queryAllByRole("button", { name: "More about Lead time (days)" }).filter((b) => !b.closest("thead"));

  it("at 390 the heading's i is not rendered, so it can neither take focus nor be read", async () => {
    stubWidth(390);
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Rice");
    await waitFor(() => expect(headerHint()).toHaveLength(0));
    // Nothing focusable is left anywhere in that heading row.
    const thead = other().querySelector("thead")!;
    expect(thead.querySelectorAll("button, a[href], input, select, textarea, [tabindex]")).toHaveLength(0);
  });

  it("at 390 the hint is still there, once, on the line above the cards, and takes focus", async () => {
    stubWidth(390);
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Rice");
    const [hint] = phoneHint();
    expect(phoneHint()).toHaveLength(1);
    const line = hint.closest("p")!;
    expect(line.textContent).toContain("Lead time (days)");
    // Hidden from 1024 up by the stylesheet, where the heading's own i is back.
    expect(line.className.split(" ")).toContain("lg:hidden");
    act(() => hint.focus());
    expect(document.activeElement).toBe(hint);
  });

  it("at 1280 the heading's i is there and reachable", async () => {
    stubWidth(1280);
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Rice");
    const [hint] = headerHint();
    expect(headerHint()).toHaveLength(1);
    expect(hint.getAttribute("tabindex")).toBeNull();
    act(() => hint.focus());
    expect(document.activeElement).toBe(hint);
  });

  it("an i outside a table heading is left alone at 390 (every form on the site has them)", async () => {
    stubWidth(390);
    render(<VendorDetailPage />);
    await screen.findByRole("form", { name: "Other ingredients" });
    // The Details form's Phone field carries one.
    expect(screen.getByRole("button", { name: "More about Phone (with country code)" })).toBeTruthy();
  });
});

describe("VERIFY-A defect 8: every value on a Supplies card is labelled", () => {
  it("a supply that is not preferred labels its dash; the Preferred badge needs no label", async () => {
    render(<VendorDetailPage />);
    const table = await suppliesTableAsync();
    await within(table).findByText("Ginger");
    const ginger = within(table).getByText("Ginger").closest("tr") as HTMLTableRowElement;
    const tomato = within(table).getByText("Tomato").closest("tr") as HTMLTableRowElement;

    const cells = (tr: HTMLTableRowElement) => Array.from(tr.cells).slice(1, -1);
    // Every short value on the card has a label, or says what it is itself.
    expect(cells(ginger).map((td) => td.getAttribute("data-label"))).toEqual([
      "Sells it as",
      "List price",
      "Lead time",
      "Preferred",
    ]);
    const preferredDash = cells(ginger)[3];
    expect(preferredDash.textContent).toBe("—");

    const badge = cells(tomato)[3];
    expect(badge.textContent).toBe("Preferred");
    expect(badge.getAttribute("data-label")).toBeNull();
  });
});

describe("VERIFY2-A N2 (T-296): the trend tip gives the old price in the unit the row leads with", () => {
  it("beside '₹1,600 / bag · ₹64 / Kg' the tip is the old bag price, '₹1,500 on 19 Sept', not the per-Kg ₹60", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([
        supply({
          ingredientId: "tom",
          ingredientName: "Tomato",
          packSizeId: "pk-bag",
          packLabel: "Bag = 25 Kg",
          pricePerPack: 1600,
          lastPrice: 64,
          previousPrice: 60,
          previousPriceOn: "2026-09-19",
        }),
      ])
    );
    render(<VendorDetailPage />);
    const table = await suppliesTableAsync();
    const row = (await within(table).findByText("Tomato")).closest("tr") as HTMLElement;
    expect(row).toHaveTextContent("₹1,600 / bag · ₹64 / Kg");
    const marker = within(row).getByRole("img", { name: "Up from ₹1,500 on 19 Sept" });
    fireEvent.focus(marker);
    expect((await screen.findByRole("tooltip")).textContent).toBe("₹1,500 on 19 Sept");
  });

  it("a supply sold by the Kg keeps the per-Kg figure, a gram price said per Kg", async () => {
    mocks.getVendor.mockResolvedValue(
      detail([supply({ ingredientId: "gin", ingredientName: "Ginger", unit: "GM", lastPrice: 0.26, previousPrice: 0.25, previousPriceOn: "2026-09-19" })])
    );
    render(<VendorDetailPage />);
    const table = await suppliesTableAsync();
    const row = (await within(table).findByText("Ginger")).closest("tr") as HTMLElement;
    expect(within(row).getByRole("img", { name: "Up from ₹250 on 19 Sept" })).toBeTruthy();
  });
});
