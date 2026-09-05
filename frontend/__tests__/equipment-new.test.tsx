import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ServiceProviderView } from "@/lib/api";

/**
 * Registering a piece of equipment (E3-S11 D2).
 *
 * <p>Twelve fields, so a screen of its own at its own address rather than a panel over the list —
 * the design system's rule is four or more. The case that carries the most is the last one: the
 * register is `MANAGE_INVENTORY` and the service schedule is `MANAGE_EQUIPMENT_SERVICING`, and a
 * cook offered an interval field would be offered a request the server would refuse.
 */

const { providersFn, authRef, providersRef, createMock, scheduleMock, createProviderMock, pushMock } =
  vi.hoisted(() => ({
    providersFn: () => {},
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    providersRef: { current: [] as ServiceProviderView[] },
    createMock: vi.fn(),
    scheduleMock: vi.fn(),
    createProviderMock: vi.fn(),
    pushMock: vi.fn(),
  }));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ data: providersRef.current, error: null, loading: false, reload: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listServiceProviders: providersFn,
      createEquipment: createMock,
      setEquipmentServiceSchedule: scheduleMock,
      createServiceProvider: createProviderMock,
    },
  };
});

import NewEquipmentPage from "@/app/equipment/new/page";

function provider(o: Partial<ServiceProviderView>): ServiceProviderView {
  return {
    id: "sp-1",
    name: "Sharma Engineering",
    phone: "+919876500011",
    email: null,
    note: null,
    equipmentCount: 6,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

describe("registering equipment", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    providersRef.current = [provider({})];
    createMock.mockReset().mockResolvedValue({ id: "eq-new" });
    scheduleMock.mockReset().mockResolvedValue(undefined);
    // Adding one really does put it in the list: the screen re-reads the providers after a save,
    // and a stub that did not would let the picker "select" an option that was never there.
    createProviderMock.mockReset().mockImplementation(async (input: { name: string }) => {
      providersRef.current = [...providersRef.current, provider({ id: "sp-2", name: input.name })];
      return { id: "sp-2" };
    });
    pushMock.mockReset();
  });

  it("registers the machine and returns to the list with the confirmation", async () => {
    render(<NewEquipmentPage />);
    expect(screen.getByRole("heading", { name: "Register equipment" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^name$/i), {
      target: { value: "Idli Steamer 6-tray" },
    });
    fireEvent.change(screen.getByLabelText(/where it lives/i), {
      target: { value: "Prasadam kitchen" },
    });
    fireEvent.change(screen.getByLabelText(/acquired on/i), { target: { value: "2026-09-04" } });

    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1));
    expect(createMock.mock.calls[0][0]).toMatchObject({
      name: "Idli Steamer 6-tray",
      category: "MACHINE",
      storageLocation: "Prasadam kitchen",
      condition: "GOOD",
      acquisitionDate: "2026-09-04",
    });

    // Rule 8: the confirmation waits on the list, not here.
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/equipment?added=Idli%20Steamer%206-tray")
    );
  });

  it("sends the schedule as a second request, because it is a second permission", async () => {
    render(<NewEquipmentPage />);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Wet Grinder 10L" } });
    fireEvent.change(screen.getByLabelText(/how often/i), { target: { value: "1" } });
    fireEvent.change(screen.getByLabelText(/interval unit/i), { target: { value: "YEARS" } });
    fireEvent.change(screen.getByLabelText(/service company/i), { target: { value: "sp-1" } });
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    await waitFor(() => expect(scheduleMock).toHaveBeenCalledTimes(1));
    expect(scheduleMock.mock.calls[0][0]).toBe("eq-new");
    // The count and the unit go together — a day total with no unit could not be shown back in the
    // words somebody typed, and a unit with no count is not an interval.
    expect(scheduleMock.mock.calls[0][1]).toEqual({
      intervalCount: 1,
      intervalUnit: "YEARS",
      serviceProviderId: "sp-1",
    });
  });

  it("asks for no schedule at all when nobody has decided about one", async () => {
    render(<NewEquipmentPage />);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Prep Table 6ft" } });
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1));
    // Sending an empty schedule would be this screen asserting a decision on the temple's behalf.
    expect(scheduleMock).not.toHaveBeenCalled();
  });

  it("adds a service company without leaving the screen, and selects what was just typed", async () => {
    render(<NewEquipmentPage />);

    fireEvent.click(screen.getByRole("button", { name: /add a service company/i }));
    fireEvent.change(screen.getByLabelText(/company name/i), { target: { value: "Iyer Repairs" } });
    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876500022" } });
    fireEvent.click(screen.getByRole("button", { name: /add the company/i }));

    await waitFor(() => expect(createProviderMock).toHaveBeenCalledTimes(1));
    expect(createProviderMock.mock.calls[0][0]).toEqual({
      name: "Iyer Repairs",
      phone: "+919876500022",
    });
    // A separate settings page for four fields would be a trip nobody makes (E3-S11).
    await waitFor(() => expect(screen.getByLabelText(/service company/i)).toHaveValue("sp-2"));
  });

  it("offers kitchen staff the register and never the schedule", () => {
    // MANAGE_EQUIPMENT_SERVICING is the temple admin's alone (E3-S10 D10). A cook registers the
    // machine and changes its condition; committing the temple to a service contract is not theirs.
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewEquipmentPage />);

    expect(screen.getByLabelText(/^name$/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/how often/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/service company/i)).not.toBeInTheDocument();
  });

  it("offers Cancel back to the list, and no way out that is not Cancel", () => {
    render(<NewEquipmentPage />);
    expect(screen.getByRole("link", { name: /^cancel$/i })).toHaveAttribute("href", "/equipment");
    expect(screen.queryByRole("button", { name: /close/i })).not.toBeInTheDocument();
  });

  it("refuses a role the register is not for", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<NewEquipmentPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
