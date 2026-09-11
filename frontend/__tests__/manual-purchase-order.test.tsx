import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, PurchaseOrderView, VendorView } from "@/lib/api";
import { todayIso } from "@/lib/format";

/**
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
  authRef, pushMock, replaceMock, paramsRef,
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
  ekadashiProhibited: false, supply: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z",
};

/** Tomorrow in the temple's own day, which is the clock the server measures needed-by against. */
function tomorrow(): string {
  const d = new Date(`${todayIso()}T00:00:00`);
  d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}

function yesterday(): string {
  const d = new Date(`${todayIso()}T00:00:00`);
  d.setDate(d.getDate() - 1);
  return d.toISOString().slice(0, 10);
}

/**
 * Chooses Rice out of the catalogue picker — once the picker has Rice to offer.
 *
 * <p>The wait is the whole point of the helper. The lines screen gates its form on the *vendor*
 * query alone (`loadingVendors`) and fills this picker from a second, ungated one,
 * `useAuthedQuery(allIngredients)`. So `findByLabelText` resolves on a paint where the
 * {@code <select>} is present and carries nothing but "Choose…", and the two never have to arrive
 * in that order.
 *
 * <p>Worth spelling out because of how it fails. Firing a change at a value no {@code <option>}
 * carries neither throws nor warns — the DOM simply declines it, the select keeps its empty value,
 * "Add line" stays disabled, and the run dies a dozen lines later on
 * {@code getByLabelText("Quantity of Rice")}, a field that was never created, pointing at a screen
 * with nothing wrong with it. That is a different fault from the `within(...)` races T-075 fixed
 * elsewhere: there an *assertion* reads too early and names the thing it could not find, here an
 * *action* lands too early and an innocent later line takes the blame.
 */
async function chooseRiceFromTheCatalogue() {
  const picker = await screen.findByLabelText(/add an ingredient/i);
  await waitFor(() =>
    expect(within(picker).getByRole("option", { name: "Rice" })).toBeInTheDocument()
  );
  fireEvent.change(picker, { target: { value: "ing1" } });
}

beforeEach(() => {
  paramsRef.current = new URLSearchParams();
  pushMock.mockReset();
  replaceMock.mockReset();
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
  it("offers raising one by hand, which the screen has never done before", async () => {
    render(<PurchaseOrdersPage />);

    const raise = await screen.findByRole("link", { name: /raise an order/i });
    expect(raise).toHaveAttribute("href", "/orders/new");
  });

  it("puts something behind the empty state’s offer to create one directly", async () => {
    render(<PurchaseOrdersPage />);

    // The sentence said "or create one directly" with nothing behind those words at all.
    const byHand = await screen.findByRole("link", { name: /raise one by hand/i });
    expect(byHand).toHaveAttribute("href", "/orders/new");
  });
});

describe("the confirmation a newly raised order comes back with", () => {
  it("shows it, offers the order itself, and strips the parameters", async () => {
    paramsRef.current = new URLSearchParams("added=Govind%20Wholesale&po=po9");
    render(<PurchaseOrdersPage />);

    expect(
      await screen.findByText(/A purchase order for Govind Wholesale was raised\./i)
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
    await screen.findByText(/A purchase order for Govind Wholesale was raised\./i);

    for (let i = 0; i < 4; i++) {
      await act(async () => {
        rerender(<PurchaseOrdersPage />);
      });
    }

    expect(replaceMock).toHaveBeenCalledTimes(1);
    expect(replaceMock).toHaveBeenCalledWith("/orders");
  });
});

describe("step one — which vendor", () => {
  it("asks the server for active vendors only, with the flag the right way round", async () => {
    render(<NewPurchaseOrderPage />);
    await screen.findByRole("combobox", { name: /vendor/i });

    // `listVendors`'s flag is inverted against the endpoint's own question: true is active-only,
    // false asks for the inactive ones as well. The server accepts an order against a vendor
    // somebody deliberately dropped — `requireVendor` checks only that the row exists — so this
    // call being right is the whole of the guard.
    expect(listVendors).toHaveBeenCalledWith(true, "test-token");
  });

  it("offers no supplier the temple has dropped", async () => {
    // The narrowing happens in the request, so what proves it here is that the screen renders the
    // answer to that request and invents nothing: one active vendor in, one option out.
    listVendors.mockResolvedValue([vendor({}), vendor({ id: "v2", name: "Sri Lakshmi Traders" })]);
    render(<NewPurchaseOrderPage />);

    const picker = await screen.findByRole("combobox", { name: /vendor/i });
    const options = within(picker).getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["Choose a vendor…", "Govind Wholesale", "Sri Lakshmi Traders"]);
  });

  it("asks one question, so leaving to add a vendor can lose nothing", async () => {
    render(<NewPurchaseOrderPage />);

    const form = await screen.findByRole("form", { name: /choose a vendor/i });
    // D-7's premise, asserted rather than assumed: there is exactly one control in this form and it
    // is the picker. The moment a second field appears here, routing out to /vendors/new starts
    // costing somebody their typing and the two-screen shape stops paying for itself.
    expect(within(form).getAllByRole("combobox")).toHaveLength(1);
    expect(within(form).queryByRole("textbox")).toBeNull();

    expect(within(form).getByRole("link", { name: /add a vendor/i })).toHaveAttribute(
      "href",
      "/vendors/new"
    );
  });

  it("leads to the lines once a vendor is chosen, and not before", async () => {
    render(<NewPurchaseOrderPage />);
    const picker = await screen.findByRole("combobox", { name: /vendor/i });

    // Nothing chosen, nothing to continue to.
    expect(screen.getByRole("button", { name: /continue/i })).toBeDisabled();

    fireEvent.change(picker, { target: { value: "v1" } });
    expect(screen.getByRole("button", { name: /continue/i })).toBeEnabled();

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /choose a vendor/i }));
    });
    // In the address, so step two is linkable and survives a reload.
    expect(pushMock).toHaveBeenCalledWith("/orders/new/lines?vendor=v1");
  });
});

describe("step two — the lines", () => {
  beforeEach(() => {
    paramsRef.current = new URLSearchParams("vendor=v1");
  });

  it("names the vendor the order is being raised against", async () => {
    render(<NewPurchaseOrderLinesPage />);
    expect(await screen.findByText("Govind Wholesale")).toBeInTheDocument();
  });

  it("raises an order carrying an ingredient line and a described one", async () => {
    render(<NewPurchaseOrderLinesPage />);

    // An ingredient out of the catalogue…
    await chooseRiceFromTheCatalogue();
    fireEvent.click(screen.getByRole("button", { name: /^add line$/i }));
    fireEvent.change(screen.getByLabelText("Quantity of Rice"), { target: { value: "30" } });

    // …and something the catalogue has never heard of, which is what T-024 made possible.
    // `{ selector }` because the field is hinted: InfoHint's "i" button carries the accessible name
    // "More about <label>", so a bare getByLabelText matches the input and the button both.
    const describe_ = screen.getByLabelText(/an item not in the catalogue/i, {
      selector: "input",
    });
    fireEvent.change(describe_, { target: { value: "  Plastic stool  " } });
    fireEvent.click(screen.getByRole("button", { name: /add described line/i }));
    fireEvent.change(screen.getByLabelText("Quantity of Plastic stool"), { target: { value: "4" } });

    fireEvent.change(screen.getByLabelText(/needed by/i), { target: { value: tomorrow() } });
    fireEvent.change(screen.getByLabelText(/deliver to/i), { target: { value: "Main store" } });

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /raise a purchase order/i }));
    });

    expect(createPurchaseOrder).toHaveBeenCalledTimes(1);
    const sent = createPurchaseOrder.mock.calls[0][0];
    expect(sent.vendorId).toBe("v1");
    expect(sent.neededBy).toBe(tomorrow());
    expect(sent.deliveryLocation).toBe("Main store");
    expect(sent.notes).toBeNull();

    // Both halves of the subject on every line, and never both filled. `objectContaining` cannot
    // test that absence — it would pass on a line that omitted `description` entirely, which is the
    // undefined the server refuses with KMS-400128 — so the keys are inspected, then the values.
    expect(sent.lines).toHaveLength(2);
    for (const line of sent.lines) {
      expect(Object.keys(line)).toContain("ingredientId");
      expect(Object.keys(line)).toContain("description");
    }
    expect(sent.lines[0]).toEqual({
      ingredientId: "ing1", description: null, quantity: 30, unit: "KG", expectedPrice: null,
    });
    expect(sent.lines[1]).toEqual({
      ingredientId: null, description: "Plastic stool", quantity: 4, unit: "PIECES", expectedPrice: null,
    });

    // Rule 8: back to the list, with the confirmation waiting there — the same parameter the vendor
    // list has taken since /vendors/new was built, plus the id so the order itself is one press away.
    expect(pushMock).toHaveBeenCalledWith("/orders?added=Govind%20Wholesale&po=po-new");
  });

  it("refuses a needed-by date that has already passed, in words, before the round trip", async () => {
    render(<NewPurchaseOrderLinesPage />);

    await chooseRiceFromTheCatalogue();
    fireEvent.click(screen.getByRole("button", { name: /^add line$/i }));
    fireEvent.change(screen.getByLabelText("Quantity of Rice"), { target: { value: "30" } });

    // The browser refuses this itself — the box carries a `min` of the temple's today — which is
    // why the attribute is asserted as well as the message. jsdom enforces neither, so the message
    // below is what a person meets if anything ever does get past the box.
    const needed = screen.getByLabelText(/needed by/i);
    expect(needed).toHaveAttribute("min", todayIso());
    fireEvent.change(needed, { target: { value: yesterday() } });

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /raise a purchase order/i }));
    });

    // The server's own KMS-400014, said early and in plain words. Nothing was sent.
    expect(screen.getByRole("alert")).toHaveTextContent(/that date has already passed/i);
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });

  it("will not raise an empty order", async () => {
    render(<NewPurchaseOrderLinesPage />);
    await screen.findByRole("form", { name: /raise a purchase order/i });

    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: /raise a purchase order/i }));
    });

    expect(screen.getByRole("alert")).toHaveTextContent(/at least one line/i);
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });

  it("sends somebody who arrived without a live vendor back to the question", async () => {
    // A hand-edited address, or a vendor dropped between the two screens. The server would accept
    // the order — `requireVendor` only checks the row exists — so this screen is the guard.
    paramsRef.current = new URLSearchParams("vendor=v-dropped");
    render(<NewPurchaseOrderLinesPage />);

    expect(await screen.findByText(/no vendor chosen/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /choose a vendor/i })).toHaveAttribute(
      "href",
      "/orders/new"
    );
    expect(screen.queryByRole("form", { name: /raise a purchase order/i })).toBeNull();
  });
});
