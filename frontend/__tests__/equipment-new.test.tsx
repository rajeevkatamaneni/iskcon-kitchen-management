import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * Registering a piece of equipment (E3-S11 D2).
 *
 * <p>Twelve fields, so a screen of its own at its own address rather than a panel over the list —
 * the design system's rule is four or more. The case that carries the most is the last one: the
 * register is `MANAGE_INVENTORY` and the service schedule is `MANAGE_EQUIPMENT_SERVICING`, and a
 * cook offered an interval field would be offered a request the server would refuse.
 */

const { authRef, createMock, scheduleMock, pushMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  createMock: vi.fn(),
  scheduleMock: vi.fn(),
  pushMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      createEquipment: createMock,
      setEquipmentServiceSchedule: scheduleMock,
    },
  };
});

import NewEquipmentPage from "@/app/equipment/new/page";

describe("registering equipment", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    createMock.mockReset().mockResolvedValue({ id: "eq-new" });
    scheduleMock.mockReset().mockResolvedValue(undefined);
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
    // The "i" beside a hinted label is a button whose accessible name names the field, so a
    // bare label query matches two things. Ask for the control.
    fireEvent.change(screen.getByLabelText(/acquired on/i, { selector: "input" }), {
      target: { value: "2026-09-04" },
    });

    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    await waitFor(() => expect(createMock).toHaveBeenCalledTimes(1));
    expect(createMock.mock.calls[0][0]).toMatchObject({
      name: "Idli Steamer 6-tray",
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
    fireEvent.change(screen.getByLabelText(/service company/i), {
      target: { value: "Bengaluru Kitchen Engineering" },
    });
    fireEvent.change(screen.getByLabelText(/their phone number/i), {
      target: { value: "+91 98450 12345" },
    });
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    await waitFor(() => expect(scheduleMock).toHaveBeenCalledTimes(1));
    expect(scheduleMock.mock.calls[0][0]).toBe("eq-new");
    // The count and the unit go together — a day total with no unit could not be shown back in the
    // words somebody typed, and a unit with no count is not an interval.
    expect(scheduleMock.mock.calls[0][1]).toEqual({
      intervalCount: 1,
      intervalUnit: "YEARS",
      // T-120: a newly registered thing is never flagged. Registering says nothing about
      // whether it will ever need servicing; the tick box lives on the item's own page.
      neverNeedsServicing: false,
      // Text, and nothing behind it: the managed list this replaced was removed on 2026-09-04.
      serviceCompany: "Bengaluru Kitchen Engineering",
      serviceCompanyPhone: "+91 98450 12345",
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

  it("takes the service company as typing, with nothing to add or pick from", () => {
    // Rajeev, 2026-09-04: "A Text box serves the purpose JUST FINE." The managed list, its
    // *Add a service company* button and the whole CRUD behind it went with that sentence.
    render(<NewEquipmentPage />);

    expect(screen.getByLabelText(/service company/i).tagName).toBe("INPUT");
    expect(screen.queryByRole("button", { name: /add a service company/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: /service company/i })).not.toBeInTheDocument();
  });

  it("sends the company on its own, with no interval, when that is all anybody said", async () => {
    render(<NewEquipmentPage />);

    fireEvent.change(screen.getByLabelText(/^name$/i), { target: { value: "Steam Cauldron" } });
    fireEvent.change(screen.getByLabelText(/service company/i), {
      target: { value: "Iyer Repairs" },
    });
    fireEvent.click(screen.getByRole("button", { name: /register it/i }));

    await waitFor(() => expect(scheduleMock).toHaveBeenCalledTimes(1));
    expect(scheduleMock.mock.calls[0][1]).toEqual({
      intervalCount: null,
      intervalUnit: null,
      // T-120: a newly registered thing is never flagged. Registering says nothing about
      // whether it will ever need servicing; the tick box lives on the item's own page.
      neverNeedsServicing: false,
      serviceCompany: "Iyer Repairs",
      serviceCompanyPhone: null,
    });
  });

  it("offers no kind to pick, because the register has none", () => {
    // Removed on 2026-09-04: a closed vocabulary of three the temple could not extend was worse
    // than none, and the name of the thing already says what it is.
    render(<NewEquipmentPage />);
    expect(screen.queryByLabelText(/^kind$/i)).not.toBeInTheDocument();
  });

  it("gives Notes the whole width and room to write in", () => {
    render(<NewEquipmentPage />);
    const notes = screen.getByLabelText(/^notes$/i);
    expect(notes.tagName).toBe("TEXTAREA");
    expect(notes.getAttribute("maxlength")).toBe("1000");
    expect(notes.parentElement?.className).toContain("col-span-2");
  });

  it("offers kitchen staff the register and never the schedule", () => {
    // MANAGE_EQUIPMENT_SERVICING is the temple admin's alone (E3-S10 D10). A cook registers the
    // machine and changes its condition; committing the temple to a service contract is not theirs.
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewEquipmentPage />);

    expect(screen.getByLabelText(/^name$/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/how often/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/service company/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/their phone number/i)).not.toBeInTheDocument();
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
