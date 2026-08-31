import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { Kitchen, UserSummary } from "@/lib/api";

// Both screens read three things before they can be answered — the temple's kitchens, its people,
// and (when editing) the kitchen itself — so the real useAuthedQuery runs here against a mocked
// api. Discriminating three fetchers by identity would prove less than letting them all resolve.
const { authRef, listKitchensMock, listUsersMock, getKitchenMock, createMock, updateMock, impactMock, pushMock } =
  vi.hoisted(() => ({
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
    listKitchensMock: vi.fn(),
    listUsersMock: vi.fn(),
    getKitchenMock: vi.fn(),
    createMock: vi.fn(),
    updateMock: vi.fn(),
    impactMock: vi.fn(),
    pushMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useParams: () => ({ id: "k1" }),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listKitchens: listKitchensMock,
      listUsers: listUsersMock,
      getKitchen: getKitchenMock,
      createKitchen: createMock,
      updateKitchen: updateMock,
      mealPlannerImpact: impactMock,
    },
  };
});

import NewKitchenPage from "@/app/kitchens/new/page";
import EditKitchenPage from "@/app/kitchens/[id]/edit/page";

function kitchen(overrides: Partial<Kitchen> = {}): Kitchen {
  return {
    id: "k1",
    name: "Prasadam hall",
    description: null,
    location: null,
    isMain: false,
    usesMealPlanner: false,
    inChargeUserId: null,
    inChargeName: null,
    contactPhone: null,
    status: "ACTIVE",
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

function person(overrides: Partial<UserSummary> = {}): UserSummary {
  return {
    id: "u1",
    fullName: "Gopal Das",
    email: "gopal@example.org",
    phone: "+919876543210",
    role: "TEMPLE_ADMIN",
    status: "ACTIVE",
    createdAt: "2026-08-01T00:00:00Z",
    ...overrides,
  };
}

describe("adding a kitchen", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
    };
    listKitchensMock.mockReset().mockResolvedValue([]);
    listUsersMock.mockReset().mockResolvedValue([person()]);
    getKitchenMock.mockReset();
    createMock.mockReset().mockResolvedValue({ id: "k-new" });
    updateMock.mockReset().mockResolvedValue(undefined);
    impactMock.mockReset();
    pushMock.mockReset();
  });

  it("creates a kitchen and returns to the list with the confirmation", async () => {
    listKitchensMock.mockResolvedValue([kitchen({ id: "k0", name: "Deity kitchen", isMain: true })]);
    render(<NewKitchenPage />);

    expect(screen.getByRole("heading", { name: /new kitchen/i })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/who runs it/i)).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Prasadam hall" } });
    fireEvent.change(screen.getByLabelText(/^location$/i), { target: { value: "East wing" } });
    fireEvent.change(screen.getByLabelText(/who runs it/i), { target: { value: "u1" } });
    fireEvent.change(screen.getByLabelText(/contact phone/i), { target: { value: "+91 98765 43210" } });

    fireEvent.click(screen.getByRole("button", { name: /add kitchen/i }));

    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Prasadam hall",
          location: "East wing",
          inChargeUserId: "u1",
          contactPhone: "+91 98765 43210",
          isMain: false,
          usesMealPlanner: false,
        }),
        "test-token"
      )
    );
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/kitchens?added=Prasadam%20hall")
    );
  });

  it("ticks and locks the main-kitchen box on a temple's first kitchen, and says why", async () => {
    render(<NewKitchenPage />);

    const main = await screen.findByLabelText(/main kitchen/i);
    await waitFor(() => expect(main).toBeChecked());
    expect(main).toBeDisabled();
    expect(screen.getByText(/a temple’s only kitchen is its main one/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Deity kitchen" } });
    fireEvent.click(screen.getByRole("button", { name: /add kitchen/i }));

    // Nothing to take the flag from, so nothing to confirm.
    await waitFor(() =>
      expect(createMock).toHaveBeenCalledWith(expect.objectContaining({ isMain: true }), "test-token")
    );
  });

  it("refuses anyone but a temple administrator", () => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", fullName: "Test Person" },
      getToken: async () => "test-token",
    };
    render(<NewKitchenPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("editing a kitchen", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", fullName: "Test Person" },
      getToken: async () => "test-token",
    };
    listKitchensMock.mockReset().mockResolvedValue([
      kitchen(),
      kitchen({ id: "k0", name: "Deity kitchen", isMain: true }),
    ]);
    listUsersMock.mockReset().mockResolvedValue([person()]);
    getKitchenMock.mockReset().mockResolvedValue(kitchen());
    createMock.mockReset();
    updateMock.mockReset().mockResolvedValue(undefined);
    impactMock.mockReset().mockResolvedValue({ draftsDeleted: 0, requestsDenied: 0 });
    pushMock.mockReset();
  });

  it("names the kitchen that would stop being the main one, and waits", async () => {
    render(<EditKitchenPage />);
    const main = await screen.findByLabelText(/main kitchen/i);
    expect(main).not.toBeChecked();
    expect(screen.getByText(/Deity kitchen holds this at the moment/i)).toBeInTheDocument();

    fireEvent.click(main);
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    const warning = await screen.findByRole("alert");
    expect(warning).toHaveTextContent(/Deity kitchen is the temple’s main kitchen at the moment/i);
    expect(updateMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /save these changes/i }));
    await waitFor(() =>
      expect(updateMock).toHaveBeenCalledWith("k1", expect.objectContaining({ isMain: true }), "test-token")
    );
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/kitchens?updated=Prasadam%20hall")
    );
  });

  it("counts what turning the meal planner on would settle, and blocks until it is agreed to", async () => {
    impactMock.mockResolvedValue({ draftsDeleted: 2, requestsDenied: 3 });
    render(<EditKitchenPage />);

    const planner = await screen.findByLabelText(/plans its meals here/i);
    fireEvent.click(planner);
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(impactMock).toHaveBeenCalledWith("k1", "test-token"));
    const warning = await screen.findByRole("alert");
    expect(warning).toHaveTextContent("2 drafts will be deleted and 3 requests will be denied.");
    expect(updateMock).not.toHaveBeenCalled();

    // Going back leaves the tick where it is and saves nothing.
    fireEvent.click(screen.getByRole("button", { name: /go back/i }));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(updateMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: /save these changes/i }));
    await waitFor(() =>
      expect(updateMock).toHaveBeenCalledWith(
        "k1",
        expect.objectContaining({ usesMealPlanner: true }),
        "test-token"
      )
    );
  });

  it("saves without asking when the meal planner would settle nothing", async () => {
    impactMock.mockResolvedValue({ draftsDeleted: 0, requestsDenied: 0 });
    render(<EditKitchenPage />);

    const planner = await screen.findByLabelText(/plans its meals here/i);
    fireEvent.click(planner);
    fireEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() =>
      expect(updateMock).toHaveBeenCalledWith(
        "k1",
        expect.objectContaining({ usesMealPlanner: true }),
        "test-token"
      )
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
