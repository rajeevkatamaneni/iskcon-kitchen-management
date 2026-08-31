import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientRequestDetail, IngredientRequestStatus, IngredientView, Kitchen } from "@/lib/api";

const { authRef, kitchensMock, ingredientsMock, createMock, updateMock, getMock, submitMock, pushMock } =
  vi.hoisted(() => ({
    authRef: {
      current: {
        status: "signed-in",
        appUser: { role: "KITCHEN_STAFF", userId: "u1", fullName: "Radha" },
        getToken: async () => "token",
        refresh: () => {},
      } as {
        status: string;
        appUser: { role: string; userId?: string; fullName?: string } | null;
        getToken: () => Promise<string>;
        refresh: () => void;
      },
    },
    kitchensMock: vi.fn(),
    ingredientsMock: vi.fn(),
    createMock: vi.fn(),
    updateMock: vi.fn(),
    getMock: vi.fn(),
    submitMock: vi.fn(),
    pushMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useParams: () => ({ id: "ir1" }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listKitchens: kitchensMock,
      listIngredients: ingredientsMock,
      createIngredientRequest: createMock,
      updateIngredientRequest: updateMock,
      getIngredientRequest: getMock,
      submitIngredientRequest: submitMock,
    },
  };
});

import NewIngredientRequestPage from "@/app/ingredient-requests/new/page";
import EditIngredientRequestPage from "@/app/ingredient-requests/[id]/edit/page";

function kitchen(overrides: Partial<Kitchen> = {}): Kitchen {
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
    ...overrides,
  };
}

function ingredient(overrides: Partial<IngredientView> = {}): IngredientView {
  return {
    id: "i1",
    name: "Rice",
    category: "Grains",
    unit: "KG",
    sattvicProhibited: false,
    aliases: [],
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

/** Fills in the two things a request cannot exist without. */
function fillTheBasics() {
  fireEvent.change(screen.getByLabelText(/^kitchen$/i), { target: { value: "k1" } });
  fireEvent.change(screen.getByLabelText(/needed on/i), { target: { value: "2026-09-04" } });
}

function addAnIngredient() {
  fireEvent.change(screen.getByLabelText(/^ingredient 1$/i), { target: { value: "i1" } });
  fireEvent.change(screen.getByLabelText(/^quantity 1$/i), { target: { value: "40" } });
}

function addADish() {
  fireEvent.change(screen.getByLabelText(/^dish 1$/i), { target: { value: "Khichdi" } });
  fireEvent.change(screen.getByLabelText(/^dish quantity 1$/i), { target: { value: "200" } });
}

describe("raising an ingredient request", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "u1", fullName: "Radha" },
      getToken: async () => "token",
      refresh: () => {},
    };
    kitchensMock.mockReset().mockResolvedValue([kitchen()]);
    ingredientsMock.mockReset().mockResolvedValue([ingredient()]);
    createMock.mockReset().mockResolvedValue({ id: "ir-new" });
    submitMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("leaves out a kitchen that plans its own meals", async () => {
    kitchensMock.mockResolvedValue([
      kitchen(),
      kitchen({ id: "k2", name: "Restaurant kitchen", isMain: false, usesMealPlanner: true }),
    ]);
    render(<NewIngredientRequestPage />);

    const picker = await screen.findByLabelText(/^kitchen$/i);
    expect(within(picker).getByText("Prasadam kitchen")).toBeInTheDocument();
    // It draws its stock through the planner, so asking the store too would issue the same food
    // twice — and the API refuses it with KMS-4976.
    expect(within(picker).queryByText("Restaurant kitchen")).not.toBeInTheDocument();
  });

  it("offers only the units the chosen ingredient can be measured in", async () => {
    ingredientsMock.mockResolvedValue([ingredient(), ingredient({ id: "i2", name: "Ghee", unit: "L" })]);
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^ingredient 1$/i);

    fireEvent.change(screen.getByLabelText(/^ingredient 1$/i), { target: { value: "i1" } });
    const units = within(screen.getByLabelText(/^unit 1$/i));
    expect(units.getByText("Kg")).toBeInTheDocument();
    expect(units.getByText("gm")).toBeInTheDocument();
    // Three litres of rice is not a quantity of rice, and the API refuses it with KMS-4001.
    expect(units.queryByText("L")).not.toBeInTheDocument();
    expect(units.queryByText("pieces")).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^ingredient 1$/i), { target: { value: "i2" } });
    const forGhee = within(screen.getByLabelText(/^unit 1$/i));
    expect(forGhee.getByText("L")).toBeInTheDocument();
    expect(forGhee.getByText("ml")).toBeInTheDocument();
    expect(forGhee.queryByText("Kg")).not.toBeInTheDocument();
  });

  it("counts a dish in servings, which an ingredient may never be", async () => {
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^dish unit 1$/i);

    expect(within(screen.getByLabelText(/^dish unit 1$/i)).getByText("servings")).toBeInTheDocument();
    expect(within(screen.getByLabelText(/^unit 1$/i)).queryByText("servings")).not.toBeInTheDocument();
  });

  it("refuses to send a request that does not say what is being cooked", async () => {
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fillTheBasics();
    addAnIngredient();
    fireEvent.click(screen.getByRole("button", { name: /submit for review/i }));

    expect(await screen.findByText(/say what you are cooking/i)).toBeInTheDocument();
    // Said here rather than by the server: nothing was sent at all.
    expect(createMock).not.toHaveBeenCalled();
    expect(submitMock).not.toHaveBeenCalled();
  });

  it("refuses to send a request that asks for nothing", async () => {
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fillTheBasics();
    addADish();
    fireEvent.click(screen.getByRole("button", { name: /submit for review/i }));

    expect(await screen.findByText(/add at least one ingredient/i)).toBeInTheDocument();
    expect(createMock).not.toHaveBeenCalled();
  });

  it("saves an incomplete draft without complaining about either", async () => {
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fillTheBasics();
    fireEvent.click(screen.getByRole("button", { name: /save as draft/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({ kitchenId: "k1", neededOn: "2026-09-04", lines: [], dishes: [] }),
        "token"
      )
    );
    // A draft is a note to oneself until it is sent, so nothing goes for review.
    expect(submitMock).not.toHaveBeenCalled();
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/ingredient-requests/ir-new"));
  });

  it("still asks for the kitchen and the date on a draft, because the record cannot exist without them", async () => {
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fireEvent.click(screen.getByRole("button", { name: /save as draft/i }));
    expect(await screen.findByText(/choose which kitchen/i)).toBeInTheDocument();
    expect(createMock).not.toHaveBeenCalled();
  });

  it("creates and then sends a complete request for review", async () => {
    render(<NewIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fillTheBasics();
    addAnIngredient();
    addADish();
    fireEvent.change(screen.getByLabelText(/^reason$/i), { target: { value: "Janmashtami feast" } });
    fireEvent.click(screen.getByRole("button", { name: /submit for review/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        {
          kitchenId: "k1",
          neededOn: "2026-09-04",
          purpose: "Janmashtami feast",
          lines: [{ ingredientId: "i1", quantity: 40, unit: "KG" }],
          dishes: [{ dishName: "Khichdi", quantity: 200, unit: "SERVINGS" }],
        },
        "token"
      )
    );
    await waitFor(() => expect(submitMock).toHaveBeenCalledWith("ir-new", "token"));
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/ingredient-requests/ir-new"));
  });

  it("refuses a volunteer the page", () => {
    authRef.current = {
      ...authRef.current,
      appUser: { role: "VOLUNTEER", userId: "u9", fullName: "Guest" },
    };
    render(<NewIngredientRequestPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /new request/i })).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// The same form, on a request that already exists (E10-S9).
// ---------------------------------------------------------------------------

function existing(status: IngredientRequestStatus, requestedBy = "u1"): IngredientRequestDetail {
  return {
    request: {
      id: "ir1",
      reference: "IR-2026-0041",
      kitchenId: "k1",
      kitchenName: "Prasadam kitchen",
      neededOn: "2026-09-04",
      purpose: "Janmashtami feast",
      status,
      requestedBy,
      requestedByName: "Radha",
      submittedAt: null,
      decidedByName: null,
      decidedAt: null,
      issuedAt: null,
      lineCount: 1,
      dishCount: 1,
    },
    lines: [
      {
        id: "l1",
        lineNo: 1,
        ingredientId: "i1",
        ingredientName: "Rice",
        quantity: 40,
        unit: "KG",
        issuedQuantity: null,
        issuedUnit: null,
        note: null,
      },
    ],
    dishes: [{ id: "d1", lineNo: 1, dishName: "Khichdi", quantity: 200, unit: "SERVINGS" }],
    events: [],
  };
}

describe("editing an ingredient request", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "u1", fullName: "Radha" },
      getToken: async () => "token",
      refresh: () => {},
    };
    kitchensMock.mockReset().mockResolvedValue([kitchen()]);
    ingredientsMock.mockReset().mockResolvedValue([ingredient()]);
    getMock.mockReset().mockResolvedValue(existing("DRAFT"));
    updateMock.mockReset().mockResolvedValue(undefined);
    submitMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("opens on what the request already says", async () => {
    render(<EditIngredientRequestPage />);

    expect(await screen.findByLabelText(/^kitchen$/i)).toHaveValue("k1");
    expect(screen.getByLabelText(/needed on/i)).toHaveValue("2026-09-04");
    expect(screen.getByLabelText(/^ingredient 1$/i)).toHaveValue("i1");
    expect(screen.getByLabelText(/^dish 1$/i)).toHaveValue("Khichdi");
  });

  it("saves a draft and sends it in one act", async () => {
    render(<EditIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fireEvent.click(screen.getByRole("button", { name: /submit for review/i }));
    await waitFor(() => expect(updateMock).toHaveBeenCalledWith("ir1", expect.anything(), "token"));
    await waitFor(() => expect(submitMock).toHaveBeenCalledWith("ir1", "token"));
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/ingredient-requests/ir1"));
  });

  it("does not offer to re-draft a request that is already out for review", async () => {
    getMock.mockResolvedValue(existing("SUBMITTED"));
    render(<EditIngredientRequestPage />);

    expect(await screen.findByRole("button", { name: /save changes/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /save as draft/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /submit for review/i })).not.toBeInTheDocument();
  });

  it("saves a correction to a request out for review without changing where it stands", async () => {
    getMock.mockResolvedValue(existing("SUBMITTED"));
    render(<EditIngredientRequestPage />);
    await screen.findByLabelText(/^kitchen$/i);

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    await waitFor(() => expect(updateMock).toHaveBeenCalledWith("ir1", expect.anything(), "token"));
    expect(submitMock).not.toHaveBeenCalled();
  });

  it("shows no form at all on somebody else's draft", async () => {
    getMock.mockResolvedValue(existing("DRAFT", "another-cook"));
    render(<EditIngredientRequestPage />);

    expect(await screen.findByText(/not yours to change/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^kitchen$/i)).not.toBeInTheDocument();
  });

  it("shows no form at all on a request that has been answered", async () => {
    getMock.mockResolvedValue(existing("APPROVED"));
    render(<EditIngredientRequestPage />);

    expect(await screen.findByText(/can no longer be changed/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^kitchen$/i)).not.toBeInTheDocument();
  });
});
