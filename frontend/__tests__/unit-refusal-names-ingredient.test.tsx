import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type IngredientView, type Kitchen, type StockDetail } from "@/lib/api";

/*
 * T-154. Since T-150 the server answers a unit from the wrong family with KMS-400013 and one field
 * error whose message names the ingredient — "Ghee is measured in L, and there is no way to turn Kg
 * into L." The order screen printed that line; the three other screens that can be refused it did
 * not, because the shared ErrorNotice prints only the message, the next step and the code. So on
 * staging a storekeeper was told a unit was wrong and not which ingredient it was wrong for
 * (UAT-081 step 17).
 *
 * Each test below drives the real screen to the real submit, has the API refuse it exactly as the
 * server does, and asserts the ingredient's sentence is on screen *beside the error box* — not merely
 * somewhere in the document, since the ingredient's name is also in the pickers.
 *
 * The real `useAuthedQuery` is used rather than a stub, so the three screens load their pickers from
 * the mocked `api` the way they do in the browser.
 */
const {
  authRef,
  listIngredientsMock,
  listKitchensMock,
  recordDonationMock,
  createRequestMock,
  submitRequestMock,
  getItemMock,
  listMovementsMock,
  adjustMock,
} = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
      getToken: async () => "test-token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; userId: string; fullName: string };
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
  listIngredientsMock: vi.fn(),
  listKitchensMock: vi.fn(),
  recordDonationMock: vi.fn(),
  createRequestMock: vi.fn(),
  submitRequestMock: vi.fn(),
  getItemMock: vi.fn(),
  listMovementsMock: vi.fn(),
  adjustMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({ id: "item-1" }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listIngredients: listIngredientsMock,
      listWishlist: vi.fn().mockResolvedValue([]),
      recordDonation: recordDonationMock,
      listKitchens: listKitchensMock,
      createIngredientRequest: createRequestMock,
      submitIngredientRequest: submitRequestMock,
      getInventoryItem: getItemMock,
      listMovements: listMovementsMock,
      adjustStock: adjustMock,
    },
  };
});

import NewDonationPage from "@/app/donations/new/page";
import NewIngredientRequestPage from "@/app/ingredient-requests/new/page";
import InventoryItemPage from "@/app/inventory/[id]/page";

/** The refusal as the server sends it: the ingredient's name is the key, its sentence the message. */
function wrongFamily(name: string, sentence: string): ApiError {
  return new ApiError(
    {
      code: "KMS-400013",
      message: "That unit can't be used for this ingredient.",
      action: "Choose a unit the ingredient is measured in.",
      fieldErrors: [{ field: name, message: sentence }],
    },
    400
  );
}

/**
 * The sentence is on screen, and it sits in the same block as the error box — which is what "in the
 * error area" means, and what separates this from the name merely appearing in a picker.
 */
async function expectBesideTheErrorBox(sentence: string) {
  const line = await screen.findByText(sentence);
  const area = line.closest("div");
  expect(area).not.toBeNull();
  expect(within(area as HTMLElement).getByRole("alert")).toHaveTextContent("KMS-400013");
}

function ingredient(overrides: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "ghee",
    name: "Ghee",
    category: "Dairy",
    unit: "L",
    ekadashiProhibited: false,
    supply: false,
    libraryDerived: false,
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

function kitchen(): Kitchen {
  return {
    id: "k1",
    name: "Prasadam kitchen",
    description: null,
    location: null,
    isMain: true,
    usesMealPlanner: false,
    inChargeUserId: null,
    inChargeName: null,
    contactPhone: null,
    status: "ACTIVE",
    createdAt: "2026-08-01T00:00:00Z",
  };
}

function stock(): StockDetail {
  return {
    item: {
      itemId: "item-1",
      ingredientId: "rice",
      ingredientName: "Rice",
      category: "Grains",
      storageLocation: "Main store",
      unit: "KG",
      onHand: 0,
      committed: 0,
      available: 0,
      reorderThreshold: null,
      belowThreshold: false,
      expiringSoon: false,
      soonestExpiry: null,
      notes: null,
    },
    batches: [],
    committed: [],
  };
}

beforeEach(() => {
  authRef.current = {
    status: "signed-in",
    appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
    getToken: async () => "test-token",
    refresh: () => {},
  };
  listIngredientsMock.mockReset();
  listKitchensMock.mockReset().mockResolvedValue([kitchen()]);
  recordDonationMock.mockReset();
  createRequestMock.mockReset();
  submitRequestMock.mockReset();
  getItemMock.mockReset().mockResolvedValue(stock());
  listMovementsMock.mockReset().mockResolvedValue([]);
  adjustMock.mockReset();
});

describe("a unit from the wrong family names the ingredient on screen", () => {
  it("on a gift of goods", async () => {
    const sentence = "Ghee is measured in L, and there is no way to turn Kg into L.";
    listIngredientsMock.mockResolvedValue([ingredient()]);
    recordDonationMock.mockRejectedValue(wrongFamily("Ghee", sentence));
    render(<NewDonationPage />);

    const form = screen.getByRole("form", { name: /record a donation/i });
    fireEvent.change(within(form).getByLabelText(/donor name/i), { target: { value: "Govind Das" } });
    fireEvent.click(within(form).getByRole("button", { name: /add a food item/i }));
    const picker = within(form).getByLabelText(/food ingredient 1/i);
    await within(picker).findByRole("option", { name: "Ghee" });
    fireEvent.change(picker, { target: { value: "ghee" } });
    fireEvent.change(within(form).getByLabelText(/quantity 1/i), { target: { value: "5" } });
    fireEvent.click(screen.getByRole("button", { name: /record donation/i }));

    await waitFor(() => expect(recordDonationMock).toHaveBeenCalled());
    await expectBesideTheErrorBox(sentence);
  });

  it("on an ingredient request", async () => {
    const sentence = "Rice is measured in Kg, and there is no way to turn L into Kg.";
    listIngredientsMock.mockResolvedValue([ingredient({ id: "rice", name: "Rice", category: "Grains", unit: "KG" })]);
    createRequestMock.mockRejectedValue(wrongFamily("Rice", sentence));
    authRef.current = { ...authRef.current, appUser: { role: "KITCHEN_STAFF", userId: "u1", fullName: "Radha" } };
    render(<NewIngredientRequestPage />);

    const kitchens = await screen.findByLabelText(/^kitchen$/i);
    await within(kitchens).findByRole("option", { name: "Prasadam kitchen" });
    fireEvent.change(kitchens, { target: { value: "k1" } });
    fireEvent.change(screen.getByLabelText(/needed on/i), { target: { value: "2026-09-04" } });
    const picker = screen.getByLabelText(/^ingredient 1$/i);
    await within(picker).findByRole("option", { name: "Rice" });
    fireEvent.change(picker, { target: { value: "rice" } });
    fireEvent.change(screen.getByLabelText(/^quantity 1$/i), { target: { value: "40" } });
    fireEvent.change(screen.getByLabelText(/^dish 1$/i), { target: { value: "Khichdi" } });
    fireEvent.change(screen.getByLabelText(/^dish quantity 1$/i), { target: { value: "200" } });
    fireEvent.change(screen.getByLabelText(/^reason$/i), { target: { value: "Janmashtami feast" } });
    fireEvent.click(screen.getByRole("button", { name: /submit for review/i }));

    await waitFor(() => expect(createRequestMock).toHaveBeenCalled());
    await expectBesideTheErrorBox(sentence);
  });

  it("on a stock adjustment", async () => {
    // Word for word what staging answered on 2026-09-12 when litres were counted against rice.
    const sentence = "Rice is measured in Kg, and there is no way to turn L into Kg.";
    adjustMock.mockRejectedValue(wrongFamily("Rice", sentence));
    render(<InventoryItemPage />);

    fireEvent.click(await screen.findByRole("button", { name: /record what's on the shelf/i }));
    const form = screen.getByRole("form", { name: /adjust stock/i });
    fireEvent.change(within(form).getByLabelText(/how much is there/i), { target: { value: "5" } });
    fireEvent.change(within(form).getByLabelText(/^unit$/i), { target: { value: "L" } });
    fireEvent.click(within(form).getByRole("button", { name: /record the count/i }));

    await waitFor(() => expect(adjustMock).toHaveBeenCalled());
    await expectBesideTheErrorBox(sentence);
  });
});
