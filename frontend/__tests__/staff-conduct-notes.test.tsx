import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { StaffConductNoteView } from "@/lib/api";

/**
 * Dated, attributed, permanent conduct notes on a staff record (E6-S16).
 *
 * <p>Three things are worth asserting and the third is the one that matters most.
 *
 * <p><b>That a note reads as a record.</b> The author and the moment come before the words, because
 * a remark with neither is `staff_profiles.notes`, which is what this exists instead of.
 *
 * <p><b>That the permanence is said before the button.</b> Somebody writing about a colleague has to
 * know it cannot be edited while they are choosing their words, not once they have pressed Save.
 *
 * <p><b>That somebody without the permission gets no panel at all.</b> Not hidden, not greyed —
 * absent, and nothing fetched. The endpoint is the real guard, but a panel that renders and then
 * fails is a panel that has already told a kitchen manager their colleague has a file.
 *
 * <p>The real `useAuthedQuery` runs here rather than a stub, so "nothing was fetched" is a claim
 * about the component and not about the mock.
 */

const { authRef, listMock, addMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  listMock: vi.fn(),
  addMock: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, staffConductNotes: listMock, addStaffConductNote: addMock },
  };
});

import { ConductNotes } from "@/components/staff/ConductNotes";

function note(o: Partial<StaffConductNoteView> = {}): StaffConductNoteView {
  return {
    id: "n1",
    body: "Left the gas on overnight. Spoken to the same morning.",
    authorUserId: "u-admin",
    authorName: "Radhika Dasi",
    createdAt: "2026-08-31T12:38:00Z",
    ...o,
  };
}

describe("conduct notes on a staff record", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    listMock.mockReset().mockResolvedValue([]);
    addMock.mockReset().mockResolvedValue({ id: "n2" });
  });

  it("puts the author and the moment above the words, in the order the server sent", async () => {
    listMock.mockResolvedValue([
      note({ id: "n2", body: "Second thing.", authorName: "Radhika Dasi" }),
      note({ id: "n1", body: "First thing.", authorName: "Gopal Das", createdAt: "2026-07-04T05:00:00Z" }),
    ]);
    render(<ConductNotes staffId="s1" />);

    const entries = await screen.findAllByRole("listitem");
    expect(entries).toHaveLength(2);
    // Newest first is the server's ordering, and the panel does not resort it.
    expect(entries[0]).toHaveTextContent("Second thing.");
    expect(entries[1]).toHaveTextContent("First thing.");

    // Attribution, in the temple's own clock.
    expect(entries[0]).toHaveTextContent("Radhika Dasi");
    expect(entries[0]).toHaveTextContent("31 Aug 2026");
    expect(entries[1]).toHaveTextContent("Gopal Das");
    expect(entries[1]).toHaveTextContent("4 Jul 2026");
  });

  it("says a note cannot be changed before there is anything to press", async () => {
    render(<ConductNotes staffId="s1" />);

    expect(await screen.findByText(/can’t be edited or deleted/)).toBeInTheDocument();
    expect(screen.getByText(/Your name and the date are saved with it/)).toBeInTheDocument();
    // Nothing offers to undo it afterwards, because nothing can.
    expect(screen.queryByRole("button", { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete/i })).not.toBeInTheDocument();
  });

  it("saves a note, then clears the box and re-reads the list", async () => {
    render(<ConductNotes staffId="s1" />);
    const box = await screen.findByLabelText("Add a note");

    fireEvent.change(box, { target: { value: "  Short with the sevaks on Tuesday.  " } });
    fireEvent.click(screen.getByRole("button", { name: "Save note" }));

    await waitFor(() =>
      expect(addMock).toHaveBeenCalledWith("s1", "Short with the sevaks on Tuesday.", "test-token")
    );
    await waitFor(() => expect(box).toHaveValue(""));
  });

  it("will not save an empty note, so nothing permanent lands by accident", async () => {
    render(<ConductNotes staffId="s1" />);
    const box = await screen.findByLabelText("Add a note");

    expect(screen.getByRole("button", { name: "Save note" })).toBeDisabled();
    fireEvent.change(box, { target: { value: "   " } });
    expect(screen.getByRole("button", { name: "Save note" })).toBeDisabled();
    expect(addMock).not.toHaveBeenCalled();
  });

  it("says so plainly when there is nothing on the record yet", async () => {
    render(<ConductNotes staffId="s1" />);
    expect(await screen.findByText("No conduct notes on this record.")).toBeInTheDocument();
  });

  it("draws nothing at all for a kitchen manager, and asks the server for nothing", async () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "km" } };
    const { container } = render(<ConductNotes staffId="s1" />);

    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByText("Conduct notes")).not.toBeInTheDocument();
    // The endpoint is the real guard. This is the half that stops the panel telling a manager
    // their colleague has a file before the server has refused them.
    expect(listMock).not.toHaveBeenCalled();
  });

  it("draws nothing for kitchen staff either", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "cook" } };
    const { container } = render(<ConductNotes staffId="s1" />);

    expect(container).toBeEmptyDOMElement();
    expect(listMock).not.toHaveBeenCalled();
  });
});
