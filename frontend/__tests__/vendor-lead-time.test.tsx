import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { VendorDetailView, VendorSupplyView, VendorView } from "@/lib/api";

/**
 * How long a vendor takes to deliver a particular thing (T-090), on the one screen where it is
 * entered.
 *
 * <p>Two facts are under test and only the first is obvious.
 *
 * <p><strong>The field exists and round-trips.</strong> Rajeev's order-by rule needs a lead time and
 * there was none anywhere in the product; somebody has to be able to type it, and this is the screen
 * where a vendor's supplies are already edited.
 *
 * <p><strong>Blank means unknown and posts a null, never a zero.</strong> This is the one that would
 * pass a read-through and fail in the kitchen. `Number("")` is 0, so a blank box coerced the
 * ordinary way says "this vendor delivers the same day" — and every screen downstream would then
 * count back zero days and tell a cook there was time to order when the rice could no longer be got.
 * Zero is a real and different answer (cash-and-carry), which is exactly why the two must not
 * collapse into one another.
 *
 * <p>The payload assertions inspect `Object.keys(...)` before the value, for the reason
 * `vendor-without-phone.test.tsx` gives: `objectContaining` reads a missing property and an explicit
 * null identically, and those are different requests to a server that writes the column it is given.
 */

const { authRef } = vi.hoisted(() => ({
  // A stable object, not a fresh one per render: useAuthedQuery depends on getToken's identity.
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "me" },
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

const { getVendorMock, listIngredientsMock, setVendorSupplyMock, addVendorSuppliesMock } = vi.hoisted(() => ({
  getVendorMock: vi.fn(),
  listIngredientsMock: vi.fn(),
  setVendorSupplyMock: vi.fn(),
  addVendorSuppliesMock: vi.fn(),
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
      addVendorSupplies: addVendorSuppliesMock,
    },
  };
});

import VendorDetailPage from "@/app/vendors/[id]/page";

function vendor(): VendorView {
  return {
    id: "v1",
    name: "Govind Wholesale",
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
    ingredientId: "ing1",
    ingredientName: "Rice",
    lastPrice: 58,
    unit: "KG",
    packSizeId: null,
    packLabel: null,
    pricePerPack: null,
    previousPrice: null,
    previousPriceOn: null,
    leadTimeDays: 1,
    preferred: true,
    ...o,
  };
}

function detail(supplies: VendorSupplyView[]): VendorDetailView {
  return { vendor: vendor(), supplies, statusHistory: [] };
}

/**
 * The cells of the row for an ingredient: Ingredient, Sells it as, List price, Lead time, Preferred,
 * and the actions. Lead time is index 3 since T-256 put "Sells it as" before it.
 */
const LEAD = 3;

function cellsFor(name: string): (string | null)[] {
  const cell = screen.getByText(name);
  return Array.from(cell.closest("tr")!.querySelectorAll("td")).map((c) => c.textContent);
}

beforeEach(() => {
  authRef.current = {
    status: "signed-in",
    appUser: { role: "KITCHEN_STAFF", userId: "me" },
    getToken: async () => "test-token",
    refresh: () => {},
  };
  getVendorMock.mockReset().mockResolvedValue(detail([supply()]));
  listIngredientsMock.mockReset().mockResolvedValue([
    { id: "ing2", name: "Jaggery", unit: "KG", category: "Sweeteners", packSizes: [] },
  ]);
  setVendorSupplyMock.mockReset().mockResolvedValue(undefined);
  addVendorSuppliesMock.mockReset().mockResolvedValue(undefined);
});

describe("a vendor's lead time", () => {
  it("shows how long each supply takes", async () => {
    getVendorMock.mockResolvedValue(detail([supply({ leadTimeDays: 4 })]));
    render(<VendorDetailPage />);

    await screen.findByText("Rice");
    expect(cellsFor("Rice")[LEAD]).toBe("4 days");
  });

  it("says one day, not one days", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Rice");
    expect(cellsFor("Rice")[LEAD]).toBe("1 day");
  });

  // An em dash, never a nought. Nobody having recorded how long this vendor takes and this vendor
  // delivering the same day are different facts, and "0 days" here would read as the second.
  it("prints an em dash where nobody has recorded one, and keeps a real zero visible", async () => {
    getVendorMock.mockResolvedValue(detail([
      supply({ ingredientId: "a", ingredientName: "Rice", leadTimeDays: null }),
      supply({ ingredientId: "b", ingredientName: "Brooms", leadTimeDays: 0, preferred: false }),
    ]));
    render(<VendorDetailPage />);

    await screen.findByText("Rice");
    expect(cellsFor("Rice")[LEAD]).toBe("—");
    expect(cellsFor("Brooms")[LEAD]).toBe("0 days");
  });

  /*
    Until T-256 these three typed into the one-at-a-time "Add supply" form. That form is gone (the
    conductor's call, 2026-09-19: "Other ingredients" replaces it), and the lead time is typed into a
    row of that table instead. The assertions are the same three: a typed figure, a blank, and a zero.
  */
  const leadFor = (name: string) => screen.getByLabelText(`Lead time (days) for ${name}`);
  const save = () => fireEvent.click(screen.getByRole("button", { name: "Save" }));
  const firstRow = () => (addVendorSuppliesMock.mock.calls[0][1] as Record<string, unknown>[])[0];

  it("records a lead time somebody types", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Jaggery");

    fireEvent.change(leadFor("Jaggery"), { target: { value: "7" } });
    save();

    await waitFor(() => expect(addVendorSuppliesMock).toHaveBeenCalled());
    expect(firstRow().leadTimeDays).toBe(7);
  });

  // The assertion this file exists for. `Number("")` is 0, so a blank box coerced the ordinary way
  // would post a same-day delivery and quietly promise a cook time that is not there.
  it("posts an explicit null when the box is left blank, and never a zero", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Jaggery");

    fireEvent.click(screen.getByLabelText("Jaggery"));
    save();

    await waitFor(() => expect(addVendorSuppliesMock).toHaveBeenCalled());
    expect(Object.keys(firstRow())).toContain("leadTimeDays");
    expect(firstRow().leadTimeDays).toBeNull();
  });

  // And zero survives as zero, which is what makes the null above mean something.
  it("posts a real zero for a shop you walk into", async () => {
    render(<VendorDetailPage />);
    await screen.findByText("Jaggery");

    fireEvent.change(leadFor("Jaggery"), { target: { value: "0" } });
    save();

    await waitFor(() => expect(addVendorSuppliesMock).toHaveBeenCalled());
    expect(firstRow().leadTimeDays).toBe(0);
  });
});
