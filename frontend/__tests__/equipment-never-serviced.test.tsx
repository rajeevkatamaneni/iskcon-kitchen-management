import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError, type EquipmentView } from "@/lib/api";

/**
 * The tick box for equipment that will never need servicing (T-120), on the item's own page.
 *
 * <p>Rajeev, 2026-09-10: *"They should be two different things. Maybe a check box for equipment
 * that don't need service like a ladder. When checked, the Service interval box is cleared out and
 * uneditable."*
 *
 * <p>What is asserted here is the ruling, clause by clause — the box exists, ticking it clears the
 * interval and makes it uneditable, and the reader is told why rather than left looking at a greyed
 * box — plus the two things the ruling implies and did not say. That the request sends no interval
 * at all when the box is ticked, because the server refuses that pairing outright and a screen
 * should never post something it knows will be turned away. And that the record above the form stops
 * saying "—" and "Not scheduled" for a ladder, because an em-dash and a phrase about scheduling are
 * exactly the two things that made a ladder and an unscheduled boiler indistinguishable.
 */

const { authRef, queryRef, scheduleMock, pushMock, replaceMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: {
    current: {
      data: null as { equipment: EquipmentView; services: unknown[]; history: unknown[] } | null,
      error: null as ApiError | null,
      loading: false,
    },
  },
  scheduleMock: vi.fn(),
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "eq-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: vi.fn() }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, setEquipmentServiceSchedule: scheduleMock },
  };
});

import EquipmentItemPage from "@/app/equipment/[id]/page";

function machine(o: Partial<EquipmentView> = {}): EquipmentView {
  return {
    id: "eq-1",
    name: "Wet grinder, 10 litre",
    storageLocation: "Main kitchen",
    condition: "GOOD",
    acquisitionDate: "2024-01-10",
    source: "PURCHASED",
    notes: null,
    createdAt: "2026-08-01T00:00:00Z",
    serialNumber: "WG-4471",
    purchaseCostInr: 48000,
    warrantyExpiry: "2027-01-10",
    neverNeedsServicing: false,
    serviceIntervalDays: 180,
    serviceIntervalUnit: "MONTHS",
    serviceIntervalCount: 6,
    serviceCompany: "Sharma Engineering",
    serviceCompanyPhone: "+919876500011",
    lastServicedOn: "2026-01-01",
    nextServiceOn: "2026-07-01",
    nextServiceBasis: "SERVICED",
    serviceStatus: "OVERDUE",
    ...o,
  };
}

/** A thing the temple owns and will never service. Rajeev's own example. */
function ladder(o: Partial<EquipmentView> = {}): EquipmentView {
  return machine({
    name: "Ladder, 8ft",
    neverNeedsServicing: true,
    serviceIntervalDays: null,
    serviceIntervalUnit: null,
    serviceIntervalCount: null,
    serviceCompany: null,
    serviceCompanyPhone: null,
    lastServicedOn: null,
    nextServiceOn: null,
    nextServiceBasis: "NONE",
    serviceStatus: "NOT_SERVICED",
    ...o,
  });
}

function record(equipment: EquipmentView) {
  return { equipment, services: [], history: [] };
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  queryRef.current = { data: record(machine()), error: null, loading: false };
  scheduleMock.mockReset().mockResolvedValue(undefined);
  pushMock.mockReset();
  replaceMock.mockReset();
});

function openSchedule() {
  fireEvent.click(screen.getByRole("button", { name: /change the schedule/i }));
}

function theBox(): HTMLInputElement {
  return screen.getByRole("checkbox", { name: /never needs servicing/i }) as HTMLInputElement;
}

function theCount(): HTMLInputElement {
  return screen.getByLabelText("How often") as HTMLInputElement;
}

describe("marking a thing as never needing servicing", () => {
  it("offers the tick box on the schedule form", () => {
    render(<EquipmentItemPage />);
    openSchedule();

    expect(theBox()).toBeInTheDocument();
    expect(theBox().checked).toBe(false);
  });

  it("clears the interval and makes it uneditable when ticked, and says why", () => {
    render(<EquipmentItemPage />);
    openSchedule();

    // The grinder starts with a real schedule on it: six months, editable.
    expect(theCount().value).toBe("6");
    expect(theCount().disabled).toBe(false);

    fireEvent.click(theBox());

    // "the Service interval box is cleared out and uneditable" — both halves, in his own words.
    expect(theCount().value).toBe("");
    expect(theCount().disabled).toBe(true);
    expect((screen.getByLabelText("Interval unit") as HTMLSelectElement).disabled).toBe(true);

    // And not a silently greyed-out box. A disabled field with no explanation makes the reader
    // wonder what they broke.
    expect(
      screen.getByText("This equipment does not need servicing, so there is no interval to set.")
    ).toBeInTheDocument();
  });

  it("gives the interval back when the box is un-ticked", () => {
    // Somebody ticked the wrong row. Un-ticking returns the form to a usable state rather than
    // leaving the field dead until the page is reloaded.
    render(<EquipmentItemPage />);
    openSchedule();

    fireEvent.click(theBox());
    fireEvent.click(theBox());

    expect(theCount().disabled).toBe(false);
    expect(
      screen.queryByText("This equipment does not need servicing, so there is no interval to set.")
    ).not.toBeInTheDocument();
  });

  it("sends the flag with no interval at all, never both", async () => {
    render(<EquipmentItemPage />);
    openSchedule();

    fireEvent.click(theBox());
    fireEvent.click(screen.getByRole("button", { name: /save the schedule/i }));

    await waitFor(() => expect(scheduleMock).toHaveBeenCalledTimes(1));
    const sent = scheduleMock.mock.calls[0][1];

    // Inspected by key rather than with objectContaining: a missing property and an explicit
    // false read identically to that matcher, and "the flag was never sent" is precisely the
    // failure this test exists to catch.
    expect(Object.keys(sent)).toContain("neverNeedsServicing");
    expect(sent.neverNeedsServicing).toBe(true);
    expect(sent.intervalCount).toBeNull();
    expect(sent.intervalUnit).toBeNull();
  });

  it("sends the flag as false, not as absent, on an ordinary schedule", async () => {
    render(<EquipmentItemPage />);
    openSchedule();

    fireEvent.click(screen.getByRole("button", { name: /save the schedule/i }));

    await waitFor(() => expect(scheduleMock).toHaveBeenCalledTimes(1));
    const sent = scheduleMock.mock.calls[0][1];

    expect(Object.keys(sent)).toContain("neverNeedsServicing");
    expect(sent.neverNeedsServicing).toBe(false);
    expect(sent.intervalCount).toBe(6);
    expect(sent.intervalUnit).toBe("MONTHS");
  });

  it("comes back ticked on a thing already marked", () => {
    queryRef.current = { data: record(ladder()), error: null, loading: false };
    render(<EquipmentItemPage />);
    openSchedule();

    expect(theBox().checked).toBe(true);
    expect(theCount().disabled).toBe(true);
  });

  it("says so in the record, instead of an em-dash and a word about scheduling", () => {
    queryRef.current = { data: record(ladder()), error: null, loading: false };
    render(<EquipmentItemPage />);

    expect(screen.getByText("It does not need servicing")).toBeInTheDocument();
    expect(screen.getByText("Not needed")).toBeInTheDocument();

    // The two phrases that used to be a ladder's whole answer, and the reason nobody could tell it
    // from a boiler somebody had forgotten about.
    expect(screen.queryByText("Not scheduled")).not.toBeInTheDocument();
  });

  it("still says not scheduled for a machine nobody has got round to", () => {
    // The other side of the same distinction, and the one that must keep being chaseable.
    queryRef.current = {
      data: record(
        machine({
          name: "Steam Boiler",
          neverNeedsServicing: false,
          serviceIntervalDays: null,
          serviceIntervalUnit: null,
          serviceIntervalCount: null,
          lastServicedOn: null,
          nextServiceOn: null,
          nextServiceBasis: "NONE",
          serviceStatus: "NOT_SCHEDULED",
        })
      ),
      error: null,
      loading: false,
    };
    render(<EquipmentItemPage />);

    expect(screen.getByText("Not scheduled")).toBeInTheDocument();
    expect(screen.queryByText("It does not need servicing")).not.toBeInTheDocument();
  });

  it("is not offered to kitchen staff, like every other servicing decision", () => {
    // Setting an interval is the Temple Admin's alone (E3-S10 D10), and declaring that a thing
    // needs none is the same decision with the same answer.
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<EquipmentItemPage />);

    expect(screen.queryByRole("button", { name: /change the schedule/i })).not.toBeInTheDocument();
  });
});
