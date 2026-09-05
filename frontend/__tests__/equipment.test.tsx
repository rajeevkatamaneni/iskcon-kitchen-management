import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ApiError, EquipmentView } from "@/lib/api";

/**
 * The equipment list (E3-S11), and the five columns it is allowed.
 *
 * <p>The register has been recording things since August with no screen at all. What is asserted
 * here is what UAT-085 walks: the five columns and no more, the service state said in words as
 * well as colour, three filters that combine rather than replace one another, scrapped machines
 * out of the way until asked for, and the confirmation a newly registered machine comes back with.
 */

const { authRef, queryRef, listMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: {
    current: { data: [] as EquipmentView[] | null, error: null as ApiError | null, loading: false },
  },
  listMock: vi.fn(),
}));

// The screen reads its own address bar — Today arrives here already filtered, and registering comes
// back with a confirmation in the URL — so the stub answers both halves of next/navigation.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "eq-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: vi.fn() }),
}));

import EquipmentPage from "@/app/equipment/page";

/** Today, in the temple's own day, so the "in n days" arithmetic is stable wherever this runs. */
const TODAY = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata" }).format(new Date());

function isoIn(days: number): string {
  const day = 24 * 60 * 60 * 1000;
  return new Date(Date.parse(`${TODAY}T00:00:00Z`) + days * day).toISOString().slice(0, 10);
}

function machine(o: Partial<EquipmentView>): EquipmentView {
  return {
    id: "eq-1",
    name: "Wet Grinder 10L",
    storageLocation: "Main kitchen",
    condition: "GOOD",
    acquisitionDate: "2024-01-10",
    source: "PURCHASED",
    notes: null,
    createdAt: "2026-08-01T00:00:00Z",
    serialNumber: "WG-4471",
    purchaseCostInr: 48000,
    warrantyExpiry: null,
    serviceIntervalDays: 180,
    serviceIntervalUnit: "MONTHS",
    serviceIntervalCount: 6,
    serviceCompany: "Sharma Engineering",
    serviceCompanyPhone: "+919876500011",
    lastServicedOn: "2026-01-01",
    nextServiceOn: isoIn(-12),
    nextServiceBasis: "SERVICED",
    serviceStatus: "OVERDUE",
    ...o,
  };
}

describe("the equipment list", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [machine({})], error: null, loading: false };
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
    listMock.mockReset();
  });

  it("shows five columns and only five — the other eleven fields are on the item", () => {
    render(<EquipmentPage />);

    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["Name", "Location", "Status", "Next service", "Service company"]);

    // Everything visible at once needs a horizontal scroll on a laptop, which is the density
    // complaint raised against the recipe list. These four are on /equipment/[id].
    expect(screen.queryByText("WG-4471")).not.toBeInTheDocument();
    expect(screen.queryByText(/every 6 months/i)).not.toBeInTheDocument();
  });

  it("says how overdue a machine is, in words as well as in red", () => {
    render(<EquipmentPage />);

    // Colour alone fails anybody who cannot see the difference, and a bare date makes the reader do
    // the arithmetic this column exists to do for them (D3).
    const state = screen.getByText("Overdue by 12 days");
    expect(state).toBeInTheDocument();
    expect(state.className).toContain("danger");
  });

  it("says how soon a due machine is, in words as well as in amber", () => {
    queryRef.current = {
      data: [machine({ serviceStatus: "DUE_SOON", nextServiceOn: isoIn(9) })],
      error: null,
      loading: false,
    };
    render(<EquipmentPage />);

    const state = screen.getByText("Due in 9 days");
    expect(state.className).toContain("warning");
  });

  it("says a machine nobody has decided about is not scheduled, rather than leaving a blank", () => {
    queryRef.current = {
      data: [
        machine({
          name: "Prep Table 6ft",
          serviceStatus: "NOT_SCHEDULED",
          nextServiceOn: null,
          nextServiceBasis: "NONE",
          serviceIntervalCount: null,
          serviceIntervalUnit: null,
        }),
      ],
      error: null,
      loading: false,
    };
    render(<EquipmentPage />);

    expect(screen.getByText("Not scheduled")).toBeInTheDocument();
  });

  it("says a derived date came from the purchase, so it never reads as a service that happened", () => {
    queryRef.current = {
      data: [machine({ lastServicedOn: null, nextServiceBasis: "PURCHASED" })],
      error: null,
      loading: false,
    };
    render(<EquipmentPage />);

    expect(screen.getByText(/from the purchase, never serviced/i)).toBeInTheDocument();
  });

  it("narrows by all three filters together rather than letting the last one win", () => {
    queryRef.current = {
      data: [
        machine({ id: "a", name: "Wet Grinder 10L" }),
        machine({
          id: "b",
          name: "Sweets Mixer",
          storageLocation: "Prasadam kitchen",
          condition: "NEEDS_REPAIR",
        }),
        machine({
          id: "c",
          name: "Steam Boiler",
          serviceStatus: "DUE_SOON",
          nextServiceOn: isoIn(9),
        }),
      ],
      error: null,
      loading: false,
    };
    render(<EquipmentPage />);

    const rows = () => screen.getAllByRole("row").length - 1;
    expect(rows()).toBe(3);

    fireEvent.change(screen.getByLabelText(/^service$/i), { target: { value: "OVERDUE" } });
    expect(rows()).toBe(2);

    // The intersection, not the union, and not whichever was touched last (UAT-085 step 20).
    fireEvent.change(screen.getByLabelText(/^location$/i), { target: { value: "Main kitchen" } });
    expect(rows()).toBe(1);
    expect(screen.getByRole("link", { name: "Wet Grinder 10L" })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^condition$/i), { target: { value: "NEEDS_REPAIR" } });
    expect(screen.queryByRole("link", { name: "Wet Grinder 10L" })).not.toBeInTheDocument();
  });

  it("arrives from Today already filtered to the machines the nudge counted", () => {
    paramsRef.current = new URLSearchParams("serviceStatus=OVERDUE");
    queryRef.current = {
      data: [
        machine({ id: "a" }),
        machine({ id: "b", name: "Steam Boiler", serviceStatus: "OK", nextServiceOn: isoIn(90) }),
      ],
      error: null,
      loading: false,
    };
    render(<EquipmentPage />);

    expect(screen.getByLabelText(/^service$/i)).toHaveValue("OVERDUE");
    expect(screen.getAllByRole("row").length - 1).toBe(1);
  });

  it("keeps scrapped machines out until they are asked for", () => {
    render(<EquipmentPage />);

    // Which rows come back is the server's decision, so what is asserted here is that asking is
    // possible at all and that the control says what it does.
    const scrapped = screen.getByLabelText(/show scrapped items/i);
    expect(scrapped).not.toBeChecked();
    fireEvent.click(scrapped);
    expect(scrapped).toBeChecked();
  });

  it("shows the confirmation a newly registered machine comes back with, and strips the param", () => {
    paramsRef.current = new URLSearchParams("added=Idli%20Steamer%206-tray");
    render(<EquipmentPage />);

    expect(screen.getByText(/Idli Steamer 6-tray is now on the register/i)).toBeInTheDocument();
    // Replaced away, so it does not come back on reload or on the back button.
    expect(replaceMock).toHaveBeenCalledWith("/equipment");
  });

  it("sends registering to a screen of its own rather than a panel above the list", () => {
    render(<EquipmentPage />);
    expect(screen.getByRole("link", { name: /register equipment/i })).toHaveAttribute(
      "href",
      "/equipment/new"
    );
  });

  it("shows an empty state when the register holds nothing", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<EquipmentPage />);
    expect(screen.getByText(/nothing on the register yet/i)).toBeInTheDocument();
  });

  it("shows kitchen staff the red rows — they are not shielded from the state of the machines", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<EquipmentPage />);

    const table = within(screen.getByRole("table"));
    expect(table.getByText("Overdue by 12 days")).toBeInTheDocument();
  });

  it("refuses a role the register is not for", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<EquipmentPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
