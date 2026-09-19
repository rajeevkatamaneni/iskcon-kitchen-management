import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, PreferredVendorView, VendorDetailView, VendorSupplyView, VendorView } from "@/lib/api";

/**
 * One preferred vendor per ingredient, said before saving (R-VEN-2, T-258).
 *
 * <p><strong>The acceptance criterion, as written:</strong> ticking Preferred on vendor B for an
 * ingredient that is preferred at vendor A makes the row say "Preferred (replaces A)" before saving.
 * Asserted in both places a tick can be made: a supply row opened for editing, and an Other
 * ingredients row. "Before saving" is checked literally: no write has been called when the words are
 * on the screen.
 *
 * <p>Around it: nothing is said when nobody else holds the preference, or when this vendor already
 * does; the words go when the tick does; the words are neutral and wrap rather than being cut; and a
 * save rereads who holds what, since it has just moved.
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
  listPreferredVendors: vi.fn(),
  setVendorSupply: vi.fn(),
  addVendorSupplies: vi.fn(),
  addPackSize: vi.fn(),
  removeVendorSupply: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "v-b" }),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import VendorDetailPage from "@/app/vendors/[id]/page";

function vendor(): VendorView {
  return {
    id: "v-b",
    name: "Bhavani Traders",
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

const RICE = ingredient({ id: "rice", name: "Rice" });
const DAL = ingredient({ id: "dal", name: "Toor dal", category: "Pulses" });
const SALT = ingredient({ id: "salt", name: "Salt", category: "Spices" });

function supply(o: Partial<VendorSupplyView> & { ingredientId: string; ingredientName: string }): VendorSupplyView {
  return {
    lastPrice: 58,
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

const held = (ingredientId: string, vendorId: string, vendorName: string): PreferredVendorView => ({
  ingredientId,
  vendorId,
  vendorName,
});

const other = () => screen.getByRole("form", { name: "Other ingredients" });
const otherRow = (name: string) => within(other()).getByLabelText(name).closest("tr") as HTMLElement;
const suppliesTable = () => screen.getAllByRole("table").find((t) => !other().contains(t))!;
const supplyRow = (name: string) => within(suppliesTable()).getByText(name).closest("tr") as HTMLElement;

async function openEditor(name: string): Promise<HTMLElement> {
  fireEvent.click(within(supplyRow(name)).getByRole("button", { name: "Edit" }));
  await within(supplyRow(name)).findByRole("button", { name: "Save" });
  return supplyRow(name);
}

const REPLACES_ANAND = "Preferred (replaces Anand Stores)";

beforeEach(() => {
  mocks.getVendor.mockReset().mockResolvedValue(detail([supply({ ingredientId: "rice", ingredientName: "Rice" })]));
  mocks.listIngredients.mockReset().mockResolvedValue([RICE, DAL, SALT]);
  // Anand Stores (vendor A) holds rice and dal; nobody holds salt.
  mocks.listPreferredVendors
    .mockReset()
    .mockResolvedValue([held("rice", "v-a", "Anand Stores"), held("dal", "v-a", "Anand Stores")]);
  mocks.setVendorSupply.mockReset().mockResolvedValue(undefined);
  mocks.addVendorSupplies.mockReset().mockResolvedValue(undefined);
  mocks.addPackSize.mockReset().mockResolvedValue({ id: "pk-new" });
  mocks.removeVendorSupply.mockReset().mockResolvedValue(undefined);
});

describe("a supply row being edited (R-VEN-2)", () => {
  it("AC: ticking Preferred for rice held by Anand Stores says so before saving", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Rice");
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalled());

    const row = await openEditor("Rice");
    expect(within(row).queryByText(REPLACES_ANAND)).toBeNull();

    fireEvent.click(within(row).getByRole("checkbox"));

    // The words, in the row, and nothing written yet.
    expect(within(row).getByText(REPLACES_ANAND)).toBeTruthy();
    expect((within(row).getByLabelText(REPLACES_ANAND) as HTMLInputElement).checked).toBe(true);
    expect(mocks.setVendorSupply).not.toHaveBeenCalled();

    // Untick: the words go and the label is plain Preferred again.
    fireEvent.click(within(row).getByRole("checkbox"));
    expect(within(row).queryByText(REPLACES_ANAND)).toBeNull();
    expect(within(row).getByLabelText("Preferred")).toBeTruthy();
  });

  it("says nothing when this vendor already holds the preference", async () => {
    mocks.getVendor.mockResolvedValue(detail([supply({ ingredientId: "rice", ingredientName: "Rice", preferred: true })]));
    mocks.listPreferredVendors.mockResolvedValue([held("rice", "v-b", "Bhavani Traders")]);
    render(<VendorDetailPage />);
    await screen.findByText("Rice");
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalled());

    const row = await openEditor("Rice");
    expect((within(row).getByLabelText("Preferred") as HTMLInputElement).checked).toBe(true);
    expect(within(row).queryByText(/replaces/)).toBeNull();
  });

  it("rereads who holds what after a save, because the save has just moved it", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Rice");
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalledTimes(1));

    const row = await openEditor("Rice");
    fireEvent.click(within(row).getByRole("checkbox"));
    fireEvent.click(within(row).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(mocks.setVendorSupply).toHaveBeenCalled());
    expect((mocks.setVendorSupply.mock.calls[0][1] as { preferred: boolean }).preferred).toBe(true);
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalledTimes(2));
  });
});

describe("an Other ingredients row (R-VEN-2)", () => {
  it("AC: ticking Preferred for dal held by Anand Stores says so before saving, and describes the box", async () => {
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Toor dal");
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalled());

    const row = otherRow("Toor dal");
    expect(within(row).queryByText(REPLACES_ANAND)).toBeNull();

    const box = within(row).getByLabelText("Preferred for Toor dal");
    fireEvent.click(box);

    const words = within(row).getByText(REPLACES_ANAND);
    expect(words).toBeTruthy();
    // A screen reader hears it with the box, not only somebody looking at the cell.
    expect(box.getAttribute("aria-describedby")).toBe(words.id);
    expect(mocks.addVendorSupplies).not.toHaveBeenCalled();

    fireEvent.click(box);
    expect(within(row).queryByText(REPLACES_ANAND)).toBeNull();
    expect(box.getAttribute("aria-describedby")).toBeNull();
  });

  it("says nothing for an ingredient nobody holds", async () => {
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Salt");
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalled());

    const row = otherRow("Salt");
    fireEvent.click(within(row).getByLabelText("Preferred for Salt"));
    expect(within(row).queryByText(/replaces/)).toBeNull();
  });

  it("still ticks and saves when the list of preferred vendors could not be read", async () => {
    mocks.listPreferredVendors.mockRejectedValue(new Error("down"));
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Toor dal");

    const row = otherRow("Toor dal");
    fireEvent.click(within(row).getByLabelText("Preferred for Toor dal"));
    expect(within(row).queryByText(/replaces/)).toBeNull();
    fireEvent.click(within(other()).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(mocks.addVendorSupplies).toHaveBeenCalled());
    const rows = mocks.addVendorSupplies.mock.calls[0][1] as { ingredientId: string; preferred: boolean }[];
    expect(rows).toEqual([expect.objectContaining({ ingredientId: "dal", preferred: true })]);
  });
});

describe("how the words look", () => {
  it("are neutral, not a warning, and wrap rather than being cut", async () => {
    mocks.listPreferredVendors.mockResolvedValue([
      held("dal", "v-a", "Sri Lakshmi Venkateswara Wholesale Provisions"),
    ]);
    render(<VendorDetailPage />);
    await within(await screen.findByRole("form", { name: "Other ingredients" })).findByLabelText("Toor dal");
    await waitFor(() => expect(mocks.listPreferredVendors).toHaveBeenCalled());

    fireEvent.click(within(otherRow("Toor dal")).getByLabelText("Preferred for Toor dal"));
    const words = screen.getByText("Preferred (replaces Sri Lakshmi Venkateswara Wholesale Provisions)");
    const cls = words.className;
    expect(cls).toContain("text-ink-secondary");
    expect(cls).not.toMatch(/warning|danger|amber|red|success/);
    expect(cls).toContain("whitespace-normal");
    expect(cls).not.toMatch(/truncate|ellipsis|line-clamp|overflow-hidden/);
  });
});
