import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, within } from "@testing-library/react";
import type { IngredientView, PurchaseOrderView, VendorView } from "@/lib/api";

/**
 * CHANGED AT T-263 (R-PO-1): the two screens this file was written for — the vendor on its own, then
 * the lines — are one form now, "Create a purchase order", and that form is tested in
 * `po-create-form.test.tsx`. What stays here is the way in from the list, the confirmation the list
 * shows afterwards, and the old step-two address redirecting to the form. The history below is kept
 * because it explains why the list's confirmation is built the way it is.
 *
 * Raising a one-off purchase order by hand, vendor first (T-026, D-7).
 *
 * <p>`POST /api/v1/purchase-orders` has existed since E5-S3 with no caller at all: an order could
 * only be generated from the shopping list, which never suggests the thing this is for — a stool
 * from a furniture shop, a cylinder from a gas dealer.
 *
 * <p>Two screens, because the vendor is asked first and on its own: routing out to `/vendors/new`
 * for a supplier that is not in the list yet can then lose nothing, since nothing has been entered.
 * Most of what is asserted below is about that shape holding — the vendor question carrying no
 * other field, the picker refusing to offer a dropped supplier, and the confirmation on the list
 * firing once rather than for ever.
 *
 * <p>`useAuthedQuery` is deliberately NOT mocked here. The screens depend on its real behaviour in
 * two ways that a stub would paper over: it lists its fetcher in the effect's dependencies, so a
 * fetcher that is not stable re-fetches for ever, and it is what actually calls `api.listVendors`
 * with the flag whose inversion is the trap this task was warned about. What is mocked is the API
 * itself, which is where the network would be.
 */

const {
  authRef, pushMock, replaceMock, redirectMock, paramsRef,
  listVendors, listIngredients, listPurchaseOrders, createPurchaseOrder,
} = vi.hoisted(() => ({
  // One object, replaced only when the role changes. `useAuthedQuery` lists the auth object's
  // members in its effect dependencies, so a mock building a fresh `getToken` per render re-fetches
  // for ever and every assertion times out on a screen that never settles.
  authRef: {
    current: {
      status: "signed-in",
      appUser: {
        userId: "u1",
        fullName: "Gopal Das",
        role: "KITCHEN_STAFF",
        tenantName: "Bengaluru Temple",
        tenantSlug: "bengaluru",
        temples: [],
      },
      getToken: async () => "test-token",
      refresh: () => {},
      signOut: vi.fn(),
      switchTemple: vi.fn(),
    } as Record<string, unknown>,
  },
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  redirectMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
  listVendors: vi.fn(),
  listIngredients: vi.fn(),
  listPurchaseOrders: vi.fn(),
  createPurchaseOrder: vi.fn(),
}));

// A NEW router object on every call, on purpose. That is what the real `useRouter` does, and it is
// the condition the flash effect's ref guard exists to survive — an effect that lists `router` in
// its dependencies re-runs on every render without it.
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({}),
  usePathname: () => "/orders",
  redirect: redirectMock,
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, listVendors, listIngredients, listPurchaseOrders, createPurchaseOrder },
  };
});

import PurchaseOrdersPage from "@/app/orders/page";
import NewPurchaseOrderPage from "@/app/orders/new/page";
import NewPurchaseOrderLinesPage from "@/app/orders/new/lines/page";

function vendor(o: Partial<VendorView>): VendorView {
  return {
    id: "v1",
    name: "Govind Wholesale",
    contactPerson: null,
    phone: "+919812345678",
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "hi",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active: true,
    whatsappReachable: true,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

const RICE: IngredientView = {
  id: "ing1", name: "Rice", category: "Grains", unit: "KG",
  packSizes: [], marketRate: null, marketRateOn: null, marketRateSource: null,
  ekadashiProhibited: false, supply: false, notBought: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  paramsRef.current = new URLSearchParams();
  pushMock.mockReset();
  replaceMock.mockReset();
  redirectMock.mockReset();
  listVendors.mockReset().mockResolvedValue([vendor({})]);
  listIngredients.mockReset().mockResolvedValue([RICE]);
  listPurchaseOrders.mockReset().mockResolvedValue([] as PurchaseOrderView[]);
  // The endpoint answers with the order's number as well as its id since T-134, because the
  // shopping list's vendor tiles confirm a created order by name. This screen still uses only the
  // id — it hands it to /orders, which offers a link to the new draft — but the stub answers the
  // way the server does, so a screen that started reading the number would not find undefined here.
  createPurchaseOrder.mockReset().mockResolvedValue({ id: "po-new", poNumber: "PO-2026-0044" });
});

describe("the way in, from the purchase-order list", () => {
  it("offers “Create a purchase order”, straight to the form (R-PO-1)", async () => {
    render(<PurchaseOrdersPage />);

    const create = await screen.findByRole("link", { name: "Create a purchase order" });
    expect(create).toHaveAttribute("href", "/orders/new");
    expect(screen.queryByRole("link", { name: /raise an order/i })).toBeNull();
  });

  it("puts something behind the empty state’s offer to create one directly", async () => {
    render(<PurchaseOrdersPage />);

    // The sentence said "or create one directly" with nothing behind those words at all.
    // "raise one by hand" until R-PO-1 renamed the act (T-263).
    const byHand = await screen.findByRole("link", { name: "create a purchase order" });
    expect(byHand).toHaveAttribute("href", "/orders/new");
  });
});

describe("the confirmation a newly raised order comes back with", () => {
  it("shows it, offers the order itself, and strips the parameters", async () => {
    paramsRef.current = new URLSearchParams("added=Govind%20Wholesale&po=po9");
    render(<PurchaseOrdersPage />);

    expect(
      await screen.findByText(/A purchase order for Govind Wholesale was created\./i)
    ).toBeInTheDocument();
    // A draft is about to be read and sent, and finding one row among fifty is not a thing to make
    // somebody do straight after raising it.
    expect(screen.getByRole("link", { name: /open the order/i })).toHaveAttribute(
      "href",
      "/orders/po9"
    );
    // …and the URL is cleaned, so a refresh does not flash it a second time.
    expect(replaceMock).toHaveBeenCalledWith("/orders");
  });

  it("fires once, however many times the screen renders", async () => {
    // THE REGRESSION THIS FILE EXISTS FOR. The effect depends on `router`, which is a new object on
    // every render — so without the ref guard it re-runs on each one, sets a fresh flash object,
    // causes another render, and the screen never settles. This codebase has had exactly that bug
    // once already, on /vendors, and vitest ran out of memory rather than failing.
    //
    // Asserted on the call count rather than on the banner, because the banner looks identical
    // either way: what breaks is that it never stops being re-shown.
    paramsRef.current = new URLSearchParams("added=Govind%20Wholesale&po=po9");
    const { rerender } = render(<PurchaseOrdersPage />);
    await screen.findByText(/A purchase order for Govind Wholesale was created\./i);

    for (let i = 0; i < 4; i++) {
      await act(async () => {
        rerender(<PurchaseOrdersPage />);
      });
    }

    expect(replaceMock).toHaveBeenCalledTimes(1);
    expect(replaceMock).toHaveBeenCalledWith("/orders");
  });
});

describe("the retired two-step route", () => {
  // R-PO-1 (T-263): the button goes straight to the one create form, and the screen that asked only
  // for the vendor is gone. /orders/new/lines — the old step two — is kept as a redirect so an old
  // bookmark lands on the form, with the vendor it carried.
  it("sends /orders/new/lines to the form, keeping the vendor", () => {
    NewPurchaseOrderLinesPage({ searchParams: { vendor: "v1" } });
    expect(redirectMock).toHaveBeenCalledWith("/orders/new?vendor=v1");
  });

  it("sends it to the bare form when no vendor came with it", () => {
    NewPurchaseOrderLinesPage({ searchParams: {} });
    expect(redirectMock).toHaveBeenCalledWith("/orders/new");
  });

  it("the form itself asks for the vendor alongside everything else, not on a screen of its own", async () => {
    render(<NewPurchaseOrderPage />);
    const form = await screen.findByRole("form", { name: "Create a purchase order" });
    expect(within(form).getByRole("combobox", { name: "Vendor" })).toBeInTheDocument();
    expect(within(form).getByLabelText("Needed by")).toBeInTheDocument();
    expect(within(form).getByRole("combobox", { name: "Add an item" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /continue/i })).toBeNull();
  });
});
