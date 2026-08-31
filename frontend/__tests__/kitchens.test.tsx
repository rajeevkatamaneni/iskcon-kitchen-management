import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type Kitchen } from "@/lib/api";

// The kitchens list is guarded and fetches live data. Drive both from mutable refs so each test can
// put the user in a role and the query in a state, without reaching Firebase or fetch.
const { authRef, queryRef, reloadMock, replaceMock, paramsRef, deleteMock, archiveMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
    } as {
      status: string;
      appUser: { role: string; fullName?: string } | null;
      getToken: () => Promise<string>;
    },
  },
  queryRef: { current: { data: [] as Kitchen[] | null, error: null as ApiError | null, loading: false, reload: () => {} } },
  reloadMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
  deleteMock: vi.fn(),
  archiveMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, deleteKitchen: deleteMock, archiveKitchen: archiveMock },
  };
});
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import KitchensPage from "@/app/kitchens/page";

function kitchen(overrides: Partial<Kitchen> = {}): Kitchen {
  return {
    id: "k1",
    name: "Deity kitchen",
    description: null,
    location: "Ground floor",
    isMain: true,
    usesMealPlanner: false,
    inChargeUserId: null,
    inChargeName: "Gopal Das",
    contactPhone: null,
    status: "ACTIVE",
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

/** The refusal the server gives for a kitchen the store has already answered. */
function inUse(): ApiError {
  return new ApiError(
    {
      code: "KMS-4973",
      message: "That kitchen has asked the store for things.",
      action: "Archive it instead.",
      fieldErrors: [],
    },
    409
  );
}

describe("kitchens list", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
    };
    queryRef.current = { data: [], error: null, loading: false, reload: reloadMock };
    reloadMock.mockClear();
    replaceMock.mockClear();
    paramsRef.current = new URLSearchParams();
    deleteMock.mockReset();
    archiveMock.mockReset().mockResolvedValue(undefined);
  });

  it("lists the temple's kitchens and offers the way to a new one", () => {
    queryRef.current = {
      data: [
        kitchen(),
        kitchen({ id: "k2", name: "Prasadam hall", isMain: false, usesMealPlanner: true, inChargeName: null }),
      ],
      error: null,
      loading: false,
      reload: reloadMock,
    };
    render(<KitchensPage />);

    expect(screen.getByRole("heading", { name: /kitchens/i, level: 1 })).toBeInTheDocument();
    expect(screen.getByText("Deity kitchen")).toBeInTheDocument();
    expect(screen.getByText("Main kitchen")).toBeInTheDocument();
    expect(screen.getByText("Prasadam hall")).toBeInTheDocument();
    // Which door each kitchen's stock leaves by is the one fact that changes how it is run.
    expect(screen.getByText("Its own meal plan")).toBeInTheDocument();
    expect(screen.getByText("Asking the store")).toBeInTheDocument();

    const add = screen.getAllByRole("link", { name: /add a kitchen/i })[0];
    expect(add).toHaveAttribute("href", "/kitchens/new");
    expect(screen.getAllByRole("link", { name: /^edit$/i })[0]).toHaveAttribute(
      "href",
      "/kitchens/k1/edit"
    );
  });

  it("says what a kitchen is for when there are none", () => {
    render(<KitchensPage />);
    expect(screen.getByText(/no kitchens yet/i)).toBeInTheDocument();
    const ways = screen.getAllByRole("link", { name: /add a kitchen/i });
    expect(ways).toHaveLength(2);
    for (const way of ways) expect(way).toHaveAttribute("href", "/kitchens/new");
  });

  it("flashes the confirmation a saved kitchen came back with", () => {
    paramsRef.current = new URLSearchParams("added=Prasadam%20hall");
    queryRef.current = { data: [kitchen()], error: null, loading: false, reload: reloadMock };
    render(<KitchensPage />);

    expect(screen.getByText(/Prasadam hall was added\./i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/kitchens");
  });

  it("offers archive, and says why, when the store has already answered that kitchen", async () => {
    queryRef.current = { data: [kitchen()], error: null, loading: false, reload: reloadMock };
    deleteMock.mockRejectedValue(inUse());
    render(<KitchensPage />);

    fireEvent.click(screen.getByRole("button", { name: /delete/i }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/delete deity kitchen\?/i)).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: /delete kitchen/i }));

    await waitFor(() =>
      expect(screen.getByText(/deity kitchen cannot be deleted/i)).toBeInTheDocument()
    );
    expect(screen.getByText(/history stays readable/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete kitchen/i })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /archive it instead/i }));
    await waitFor(() => expect(archiveMock).toHaveBeenCalledWith("k1", "test-token"));
    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
  });

  it("refuses anyone but a temple administrator", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", fullName: "Test Person" },
      getToken: async () => "test-token",
    };
    render(<KitchensPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
