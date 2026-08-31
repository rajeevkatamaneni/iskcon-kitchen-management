import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";
import type { IngredientRequestDetail, IngredientRequestStatus } from "@/lib/api";

const {
  authRef,
  getMock,
  submitMock,
  deleteMock,
  approveMock,
  denyMock,
  withdrawMock,
  issueMock,
  pushMock,
} = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_STAFF", userId: "author", fullName: "Radha" },
      getToken: async () => "token",
      refresh: () => {},
    } as {
      status: string;
      appUser: { role: string; userId?: string; fullName?: string } | null;
      getToken: () => Promise<string>;
      refresh: () => void;
    },
  },
  getMock: vi.fn(),
  submitMock: vi.fn(),
  deleteMock: vi.fn(),
  approveMock: vi.fn(),
  denyMock: vi.fn(),
  withdrawMock: vi.fn(),
  issueMock: vi.fn(),
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
      getIngredientRequest: getMock,
      submitIngredientRequest: submitMock,
      deleteIngredientRequest: deleteMock,
      approveIngredientRequest: approveMock,
      denyIngredientRequest: denyMock,
      withdrawIngredientRequest: withdrawMock,
      recordIngredientIssue: issueMock,
    },
  };
});

import IngredientRequestPage from "@/app/ingredient-requests/[id]/page";

function detail(status: IngredientRequestStatus, overrides: Partial<IngredientRequestDetail> = {}) {
  const request: IngredientRequestDetail["request"] = {
    id: "ir1",
    reference: "IR-2026-0041",
    kitchenId: "k1",
    kitchenName: "Prasadam kitchen",
    neededOn: "2026-09-04",
    purpose: "Janmashtami feast",
    status,
    requestedBy: "author",
    requestedByName: "Radha",
    submittedAt: status === "DRAFT" ? null : "2026-08-30T09:00:00Z",
    decidedByName: status === "APPROVED" || status === "DENIED" ? "Gopal" : null,
    decidedAt: status === "APPROVED" || status === "DENIED" ? "2026-08-30T10:00:00Z" : null,
    issuedAt: status === "ISSUED" ? "2026-08-30T11:00:00Z" : null,
    lineCount: 1,
    dishCount: 1,
  };
  return {
    request,
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
    events: [
      {
        id: "e1",
        eventType: "CREATED",
        detail: "IR-2026-0041 raised as a draft",
        actorName: "Radha",
        at: "2026-08-30T09:00:00Z",
      },
    ],
    ...overrides,
  } satisfies IngredientRequestDetail;
}

function signedInAs(role: string, userId: string) {
  authRef.current = {
    status: "signed-in",
    appUser: { role, userId, fullName: "Somebody" },
    getToken: async () => "token",
    refresh: () => {},
  };
}

/** The controls the header is offering, named as they read and in the order they are read in. */
function controls(): string[] {
  const header = screen.getByRole("heading", { name: /IR-2026-0041/ }).closest("header");
  return [...(header as HTMLElement).querySelectorAll("button, a")].map(
    (el) => el.textContent?.trim() ?? ""
  );
}

describe("the ingredient request record", () => {
  beforeEach(() => {
    signedInAs("KITCHEN_STAFF", "author");
    getMock.mockReset().mockResolvedValue(detail("DRAFT"));
    submitMock.mockReset().mockResolvedValue(undefined);
    deleteMock.mockReset().mockResolvedValue(undefined);
    approveMock.mockReset().mockResolvedValue(undefined);
    denyMock.mockReset().mockResolvedValue(undefined);
    withdrawMock.mockReset().mockResolvedValue(undefined);
    issueMock.mockReset().mockResolvedValue(undefined);
    pushMock.mockReset();
  });

  it("shows the request, its lines, its dishes and its trail", async () => {
    render(<IngredientRequestPage />);

    expect(await screen.findByRole("heading", { name: /IR-2026-0041/ })).toBeInTheDocument();
    expect(screen.getByText(/prasadam kitchen/i)).toBeInTheDocument();
    expect(screen.getByText("Rice")).toBeInTheDocument();
    expect(screen.getByText("40 Kg")).toBeInTheDocument();
    expect(screen.getByText("Khichdi")).toBeInTheDocument();
    expect(screen.getByText("200 servings")).toBeInTheDocument();
    // One sentence per event, with who did it and when.
    expect(screen.getByText(/IR-2026-0041 raised as a draft — Radha, /)).toBeInTheDocument();
  });

  it("never prints a stored status at a person", async () => {
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(screen.getByText("Draft")).toBeInTheDocument();
    expect(screen.queryByText("DRAFT")).not.toBeInTheDocument();
  });

  // --- A draft ----------------------------------------------------------

  it("lets the author of a draft edit it, send it and delete it", async () => {
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual(["Edit", "Delete", "Submit for review"]);
  });

  it("lets an approver delete somebody else's draft, and nothing more", async () => {
    signedInAs("TEMPLE_ADMIN", "admin");
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    // Only its author can send it for review, and only its author can rewrite it.
    expect(controls()).toEqual(["Delete"]);
    expect(screen.getByText(/somebody else’s draft/i)).toBeInTheDocument();
  });

  it("offers a bystander nothing at all on somebody else's draft", async () => {
    signedInAs("KITCHEN_STAFF", "another-cook");
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual([]);
    expect(screen.getByText(/only the person who raised it/i)).toBeInTheDocument();
  });

  it("asks before deleting, and returns to the list with the reference", async () => {
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    fireEvent.click(screen.getByRole("button", { name: /^delete$/i }));
    expect(deleteMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /delete for good/i }));
    await waitFor(() => expect(deleteMock).toHaveBeenCalledWith("ir1", "token"));
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/ingredient-requests?deleted=IR-2026-0041")
    );
  });

  it("refuses to send a draft with no dishes on it, and says why", async () => {
    getMock.mockResolvedValue(detail("DRAFT", { dishes: [] }));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    fireEvent.click(screen.getByRole("button", { name: /submit for review/i }));
    expect(await screen.findByText(/say what you are cooking/i)).toBeInTheDocument();
    expect(submitMock).not.toHaveBeenCalled();
  });

  // --- Awaiting review --------------------------------------------------

  it("lets the author take a submitted request back, but not answer it", async () => {
    getMock.mockResolvedValue(detail("SUBMITTED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual(["Edit", "Withdraw to a draft"]);
    expect(screen.queryByRole("button", { name: /^approve$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^deny$/i })).not.toBeInTheDocument();
  });

  it("lets an approver correct it, take it back, approve it or deny it with a note", async () => {
    signedInAs("KITCHEN_MANAGER", "manager");
    getMock.mockResolvedValue(detail("SUBMITTED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual(["Edit", "Withdraw to a draft"]);

    fireEvent.change(screen.getByLabelText(/note \(optional\)/i), {
      target: { value: "Take it from the opened tin." },
    });
    fireEvent.click(screen.getByRole("button", { name: /^approve$/i }));
    await waitFor(() =>
      expect(approveMock).toHaveBeenCalledWith("ir1", "Take it from the opened tin.", "token")
    );
  });

  it("shows a bystander that somebody else answers it", async () => {
    signedInAs("KITCHEN_STAFF", "another-cook");
    getMock.mockResolvedValue(detail("SUBMITTED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual([]);
    expect(screen.getByText(/waiting for an answer/i)).toBeInTheDocument();
  });

  // --- Decided ----------------------------------------------------------

  it("offers nothing at all on a denied request, and says the door is shut", async () => {
    signedInAs("TEMPLE_ADMIN", "admin");
    getMock.mockResolvedValue(detail("DENIED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual([]);
    expect(screen.getByText(/denied, and that is final/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /record the issue/i })).not.toBeInTheDocument();
  });

  it("says an issued request is finished, and offers nothing on it", async () => {
    signedInAs("TEMPLE_ADMIN", "admin");
    getMock.mockResolvedValue(detail("ISSUED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(controls()).toEqual([]);
    expect(screen.getByText(/gone over the counter/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /record the issue/i })).not.toBeInTheDocument();
  });

  // --- Approved, and issuing -------------------------------------------

  it("pre-fills each line with the quantity that was approved", async () => {
    signedInAs("KITCHEN_MANAGER", "manager");
    getMock.mockResolvedValue(detail("APPROVED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(screen.getByLabelText(/issued rice/i)).toHaveValue(40);
    // Nothing about an approved request can be edited any more.
    expect(controls()).toEqual([]);
  });

  it("issues what the storekeeper actually typed, zero included", async () => {
    signedInAs("KITCHEN_MANAGER", "manager");
    getMock.mockResolvedValue(detail("APPROVED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    fireEvent.change(screen.getByLabelText(/issued rice/i), { target: { value: "0" } });
    fireEvent.click(screen.getByRole("button", { name: /record the issue/i }));

    await waitFor(() =>
      expect(issueMock).toHaveBeenCalledWith(
        "ir1",
        { lines: [{ lineId: "l1", quantity: 0, unit: "KG" }], note: null },
        "token"
      )
    );
  });

  it("makes clear that a shortfall issued nothing at all", async () => {
    signedInAs("KITCHEN_MANAGER", "manager");
    getMock.mockResolvedValue(detail("APPROVED"));
    issueMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-4911",
          message: "There isn’t enough stock to cook this.",
          action: "Cook a smaller quantity, or receive or adjust stock for the ingredients that are short.",
          fieldErrors: [{ field: "Rice", message: "need 40, have 12 KG" }],
        },
        409
      )
    );
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    fireEvent.click(screen.getByRole("button", { name: /record the issue/i }));

    expect(await screen.findByText(/nothing was issued/i)).toBeInTheDocument();
    expect(screen.getByText(/or not at all, so no stock has moved/i)).toBeInTheDocument();
    expect(screen.getByText(/need 40, have 12 KG/)).toBeInTheDocument();
    expect(screen.getByText(/correct the count on the inventory screen/i)).toBeInTheDocument();
  });

  it("keeps the issue form away from somebody with no issuing rights", async () => {
    signedInAs("KITCHEN_STAFF", "author");
    getMock.mockResolvedValue(detail("APPROVED"));
    render(<IngredientRequestPage />);
    await screen.findByRole("heading", { name: /IR-2026-0041/ });

    expect(screen.queryByRole("button", { name: /record the issue/i })).not.toBeInTheDocument();
    expect(screen.getByText(/waiting on the store/i)).toBeInTheDocument();
    // The work order is a reading act, so it is offered to whoever is looking.
    expect(screen.getByRole("button", { name: /download work order/i })).toBeInTheDocument();
  });

  it("refuses a volunteer the page", () => {
    signedInAs("VOLUNTEER", "guest");
    render(<IngredientRequestPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
