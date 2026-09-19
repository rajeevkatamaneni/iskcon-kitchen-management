import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  IngredientSupplyView,
  IngredientView,
  PreferredVendorView,
  VendorSupplyView,
  VendorView,
} from "@/lib/api";

/**
 * Four defects the second round of VERIFY-A found on the ingredient page (VERIFY2-A, N1 to N4), each
 * pinned here (T-296). The repros are in docs/work/proof/VERIFY2-A.md, "New defects".
 *
 * <ul>
 *   <li><strong>N1.</strong> Pack sizes kept a server refusal (KMS-400157, a size the ingredient
 *       already has) after the entry changed, so a Box of 0 showed "Size must be more than 0" with the
 *       duplicate's box still under it. The vendor page's own pack row had the same defect and T-292
 *       fixed it; this is that fix, on this page. Each way the entry can change has its own test.</li>
 *   <li><strong>N2.</strong> The trend tip beside a pack-sold supply gave the old price per Kg, "₹60
 *       on 19 Sept", beside "₹1,600 / bag · ₹64 / Kg", where it reads as ₹60 a bag. It now gives the
 *       old price in the unit the row leads with: per bag for a bag, per Kg otherwise. The words stay
 *       "₹&lt;amount&gt; on &lt;date&gt;" (R-VEN-3's acceptance check, the conductor 2026-09-19).</li>
 *   <li><strong>N3.</strong> A phone card read "Lead time 2 days —": the dash was Preferred with no
 *       label. The label is the cell's `data-label`, which the card stylesheet prints.</li>
 *   <li><strong>N4.</strong> Link a vendor took three rows at 1024 where two fit, because the tick
 *       and the button always had a line of their own. jsdom lays nothing out, so what is pinned here is
 *       the structure that lets them flow beside Lead time; the widths at 1280, 1024 and 390 are
 *       measured in the browser in docs/work/proof/T-296.md.</li>
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
  getIngredient: vi.fn(),
  listIngredientSupplies: vi.fn(),
  listVendors: vi.fn(),
  listPreferredVendors: vi.fn(),
  addPackSize: vi.fn(),
  removePackSize: vi.fn(),
  setMarketRate: vi.fn(),
  setVendorSupply: vi.fn(),
  addVendorSupplies: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "jag" }),
  usePathname: () => "/ingredients/jag",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});

import IngredientDetailPage from "@/app/ingredients/[id]/page";
import { previousPriceText } from "@/components/ingredient/supply";

const BAG = { id: "bag", name: "Bag", quantity: 25, unit: "KG", baseQuantity: 25, label: "Bag = 25 Kg" };

function jaggery(o: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "jag",
    name: "VERIFY2-A Jaggery",
    category: "Sweeteners",
    unit: "KG",
    packSizes: [BAG, { id: "p500", name: null, quantity: 500, unit: "GM", baseQuantity: 0.5, label: "500 gm" }],
    marketRate: 58,
    marketRateOn: "2026-09-19",
    marketRateSource: "MANUAL",
    ekadashiProhibited: false,
    supply: false,
    libraryDerived: false,
    aliases: [],
    createdAt: "2026-09-19T09:00:00Z",
    ...o,
  } as IngredientView;
}

function vendor(id: string, name: string): VendorView {
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
    active: true,
    whatsappReachable: false,
    createdAt: "2026-08-01T00:00:00Z",
  };
}

function supply(o: Partial<IngredientSupplyView> & { vendorId: string; vendorName: string }): IngredientSupplyView {
  return {
    ingredientId: "jag",
    ingredientName: "VERIFY2-A Jaggery",
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

/** VERIFY2-A's own rows: Vendor One by the bag, up from ₹60 / Kg; Vendor Two by the Kg, preferred. */
const VENDOR_ONE = supply({
  vendorId: "v1",
  vendorName: "VERIFY2-A Vendor One",
  packSizeId: "bag",
  packLabel: "Bag = 25 Kg",
  pricePerPack: 1600,
  lastPrice: 64,
  previousPrice: 60,
  previousPriceOn: "2026-09-19",
  leadTimeDays: 2,
});
const VENDOR_TWO = supply({
  vendorId: "v2",
  vendorName: "VERIFY2-A Vendor Two",
  lastPrice: 60,
  previousPrice: 58,
  previousPriceOn: "2026-09-19",
  leadTimeDays: 1,
  preferred: true,
});

const held: PreferredVendorView = { ingredientId: "jag", vendorId: "v2", vendorName: "VERIFY2-A Vendor Two" };

async function apiError(code: string, message: string) {
  const { ApiError } = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return new ApiError({ code, message, action: "Use the one that’s there, or enter a different size.", fieldErrors: [] }, 400);
}

beforeEach(() => {
  mocks.getIngredient.mockReset().mockResolvedValue(jaggery());
  mocks.listIngredientSupplies.mockReset().mockResolvedValue([VENDOR_ONE, VENDOR_TWO]);
  mocks.listVendors.mockReset().mockResolvedValue([vendor("v1", "VERIFY2-A Vendor One"), vendor("v2", "VERIFY2-A Vendor Two"), vendor("v3", "Sri Balaji Traders")]);
  mocks.listPreferredVendors.mockReset().mockResolvedValue([held]);
  mocks.addPackSize.mockReset().mockResolvedValue({ id: "new" });
  mocks.removePackSize.mockReset().mockResolvedValue(undefined);
  mocks.setMarketRate.mockReset().mockResolvedValue(undefined);
  mocks.setVendorSupply.mockReset().mockResolvedValue(undefined);
  mocks.addVendorSupplies.mockReset().mockResolvedValue(undefined);
});

async function renderPage() {
  render(<IngredientDetailPage />);
  await screen.findByRole("heading", { level: 1, name: "VERIFY2-A Jaggery" });
}

const packSection = () => screen.getByRole("region", { name: "Pack sizes" });
const packForm = () => screen.getByRole("form", { name: "Add a pack size" });
const DUP_TEXT = "This ingredient already has a pack of that size.";
const staleShown = () => within(packSection()).queryByText(DUP_TEXT);

function type(label: string, value: string) {
  fireEvent.change(within(packForm()).getByLabelText(label), { target: { value } });
}
function pressAdd() {
  fireEvent.click(within(packForm()).getByRole("button", { name: "Add pack size" }));
}

/** VERIFY2-A's first step: Sack, 25000, gm, Add, and the server refuses it as a duplicate. */
async function refusedDuplicate() {
  mocks.addPackSize.mockRejectedValueOnce(await apiError("KMS-400157", DUP_TEXT));
  await renderPage();
  type("Pack name (optional)", "Sack");
  type("Size", "25000");
  type("Unit", "GM");
  pressAdd();
  expect(await within(packSection()).findByText(DUP_TEXT)).toBeInTheDocument();
}

describe("VERIFY2-A N1: a pack-size refusal never outlives the entry that caused it", () => {
  it("the exact repro: a duplicate, then Box, 0, Kg and Add — only 'Size must be more than 0' shows", async () => {
    await refusedDuplicate();
    type("Pack name (optional)", "Box");
    type("Size", "0");
    type("Unit", "KG");
    pressAdd();
    expect(await screen.findByText("Size must be more than 0")).toBeInTheDocument();
    expect(staleShown()).toBeNull();
    expect(within(packSection()).queryByText(/KMS-400157/)).toBeNull();
    expect(mocks.addPackSize).toHaveBeenCalledTimes(1);
  });

  it("typing in Pack name clears it", async () => {
    await refusedDuplicate();
    type("Pack name (optional)", "Box");
    expect(staleShown()).toBeNull();
  });

  it("typing in Size clears it, including a size below nought (VERIFY2-A's −5)", async () => {
    await refusedDuplicate();
    type("Size", "-5");
    expect(staleShown()).toBeNull();
  });

  it("changing Unit clears it", async () => {
    await refusedDuplicate();
    type("Unit", "KG");
    expect(staleShown()).toBeNull();
  });

  it("each press of Add clears it before the size is checked, with no field touched", async () => {
    // Reached without typing: a removal is refused while the Size box is empty, and then Add is
    // pressed. The old code checked the size first and returned, so the refusal stayed on screen.
    mocks.removePackSize.mockRejectedValueOnce(await apiError("KMS-400159", "A vendor sells jaggery in this pack."));
    await renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Remove 500 gm" }));
    expect(await within(packSection()).findByText("A vendor sells jaggery in this pack.")).toBeInTheDocument();
    pressAdd();
    // An empty box is refused as empty, in the words every form uses (T-343).
    expect(await screen.findByText("Size is required")).toBeInTheDocument();
    expect(within(packSection()).queryByText("A vendor sells jaggery in this pack.")).toBeNull();
  });

  it("a press that goes to the server clears it while that press is still with the server, and a success leaves none", async () => {
    // A guard, not a discriminator: the old code also cleared on a press whose size was valid. It is
    // here so every path the task names has a test (docs/work/proof/T-296.md says which are which).
    mocks.removePackSize.mockRejectedValueOnce(await apiError("KMS-400159", "A vendor sells jaggery in this pack."));
    let answer: (v: unknown) => void = () => {};
    mocks.addPackSize.mockImplementationOnce(() => new Promise((res) => { answer = res; }));
    await renderPage();
    type("Size", "10");
    fireEvent.click(screen.getByRole("button", { name: "Remove 500 gm" }));
    expect(await within(packSection()).findByText("A vendor sells jaggery in this pack.")).toBeInTheDocument();
    pressAdd();
    await waitFor(() => expect(within(packSection()).queryByText("A vendor sells jaggery in this pack.")).toBeNull());
    await act(async () => answer({ id: "new" }));
    expect(within(packSection()).queryByRole("alert")).toBeNull();
    expect(screen.queryByText("Size must be more than 0")).toBeNull();
  });
});

describe("VERIFY3 A3-1: one sentence for a size that is not more than nought", () => {
  /** Every red sentence in the pack-size section, in reading order. */
  const sentences = () =>
    within(packSection())
      .queryAllByText(/^Size (must|is)/)
      .map((el) => el.textContent);

  it("the exact repro: 0 and Add, then -5 and Add, says 'more than 0' once and never 'at least 0'", async () => {
    await renderPage();
    type("Size", "0");
    pressAdd();
    expect(await screen.findByText("Size must be more than 0")).toBeInTheDocument();
    expect(sentences()).toEqual(["Size must be more than 0"]);
    type("Size", "-5");
    pressAdd();
    await waitFor(() => expect(sentences()).toEqual(["Size must be more than 0"]));
    expect(within(packSection()).queryByText(/at least/)).toBeNull();
    expect(mocks.addPackSize).not.toHaveBeenCalled();
  });

  it("the sentence follows each change: gone once the size is right, back when it is wrong again", async () => {
    await renderPage();
    type("Size", "-5");
    pressAdd();
    expect(await screen.findByText("Size must be more than 0")).toBeInTheDocument();
    type("Size", "2");
    await waitFor(() => expect(sentences()).toEqual([]));
    type("Size", "");
    // Nobody is told off mid-word: an emptied box gains no sentence until the next press.
    expect(sentences()).toEqual([]);
    pressAdd();
    await waitFor(() => expect(sentences()).toEqual(["Size is required"]));
    type("Size", "0");
    await waitFor(() => expect(sentences()).toEqual(["Size must be more than 0"]));
  });
});

describe("VERIFY2-A N2: the trend tip gives the old price in the unit the row leads with", () => {
  const rowOf = async (name: string) =>
    (await screen.findByRole("link", { name })).closest("tr") as HTMLElement;

  it("a bag supply: '₹1,500 on 19 Sept' beside '₹1,600 / bag · ₹64 / Kg', never the per-Kg ₹60", async () => {
    await renderPage();
    const row = await rowOf("VERIFY2-A Vendor One");
    expect(row).toHaveTextContent("₹1,600 / bag · ₹64 / Kg");
    const marker = within(row).getByRole("img", { name: "Up from ₹1,500 on 19 Sept" });
    fireEvent.focus(marker);
    expect((await screen.findByRole("tooltip")).textContent).toBe("₹1,500 on 19 Sept");
  });

  it("a supply sold by the Kg keeps '₹58 on 19 Sept'", async () => {
    await renderPage();
    const row = await rowOf("VERIFY2-A Vendor Two");
    expect(row).toHaveTextContent("₹60 / Kg");
    fireEvent.focus(within(row).getByRole("img", { name: "Up from ₹58 on 19 Sept" }));
    expect((await screen.findByRole("tooltip")).textContent).toBe("₹58 on 19 Sept");
  });

  describe("previousPriceText, which both pages hand to PriceTrend", () => {
    const base: VendorSupplyView = {
      ingredientId: "x", ingredientName: "X", lastPrice: 64, unit: "KG", packSizeId: null, packLabel: null,
      pricePerPack: null, previousPrice: 60, previousPriceOn: "2026-09-19", leadTimeDays: null, preferred: false,
    };

    it("a pack: the old price per unit times today's pack", () => {
      expect(previousPriceText({ ...base, packSizeId: "bag", packLabel: "Bag = 25 Kg" }, [BAG])(60)).toBe("₹1,500");
    });

    it("a pack measured in grams on a gram ingredient: ₹0.3333 a gram on a 3 Kg bag is ₹999.90, not float dust", () => {
      const g3 = { id: "g3", name: "Bag", quantity: 3, unit: "KG", baseQuantity: 3000, label: "Bag = 3 Kg" };
      expect(previousPriceText({ ...base, unit: "GM", packSizeId: "g3", packLabel: "Bag = 3 Kg" }, [g3])(0.3333)).toBe("₹999.90");
    });

    it("no pack: per Kg, and a price kept per gram is said per Kg", () => {
      expect(previousPriceText(base, [])(60)).toBe("₹60");
      expect(previousPriceText({ ...base, unit: "GM" }, [])(0.065)).toBe("₹65");
    });

    it("a pack not loaded yet falls back to the per-Kg figure, the row's second price", () => {
      expect(previousPriceText({ ...base, packSizeId: "bag", packLabel: "Bag = 25 Kg" }, undefined)(60)).toBe("₹60");
    });
  });
});

describe("VERIFY2-A N3: every value on a vendor card is labelled", () => {
  it("a vendor that is not preferred labels its dash; the Preferred badge needs no label", async () => {
    await renderPage();
    const one = (await screen.findByRole("link", { name: "VERIFY2-A Vendor One" })).closest("tr") as HTMLElement;
    const dash = Array.from(one.querySelectorAll("td")).find((td) => td.textContent === "—")!;
    expect(dash.getAttribute("data-label")).toBe("Preferred");

    const two = screen.getByRole("link", { name: "VERIFY2-A Vendor Two" }).closest("tr") as HTMLElement;
    const badge = within(two).getByText("Preferred").closest("td")!;
    expect(badge.hasAttribute("data-label")).toBe(false);
  });
});

describe("VERIFY2-A N4: the tick and the button flow beside Lead time when they fit", () => {
  it("sit in the same wrapping line as the four boxes, together, at the foot of the row", async () => {
    await renderPage();
    const form = screen.getByRole("form", { name: "Link a vendor" });
    const button = within(form).getByRole("button", { name: "Link vendor" });
    const tick = within(form).getByRole("checkbox", { name: "Preferred" });
    const pair = button.parentElement!;
    // Together: the tick's label and the button are one item, so a wrap never parts them.
    expect(pair.contains(tick)).toBe(true);
    expect(pair).toHaveClass("self-end");
    // In the boxes' own line, as its last item, so it goes to a new row only when it cannot fit.
    const line = pair.parentElement!;
    expect(line).toHaveClass("flex-wrap");
    expect(line.contains(within(form).getByLabelText("Lead time (days)"))).toBe(true);
    expect(line.contains(within(form).getByLabelText("Vendor"))).toBe(true);
    expect(line.lastElementChild).toBe(pair);
  });
});
