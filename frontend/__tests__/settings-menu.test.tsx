import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type MenuLayout } from "@/lib/api";
import { standardMenu } from "@/lib/nav";

/**
 * Settings → Menu, the screen a temple arranges its own left-hand menu on (T-422).
 *
 * <p>What is worth asserting here is not that a button exists. It is that the board is built from
 * what the temple saved, that every move does the thing its label says, and above all that
 * <b>what reaches the server is exactly the arrangement on the screen</b> — because that arrangement
 * repaints the menu of everybody at the temple, and a save that quietly sent something else would be
 * discovered by a cook rather than by a test.
 *
 * <p>Three claims are about absence and say so out loud, because an absence assertion passes
 * vacuously once the thing it guards has gone. Each is paired with a positive assertion in the same
 * test — the thirty-three destinations are on the board, the seven headings are editable — so that a
 * board that failed to render cannot read as a board with nothing wrong on it.
 *
 * <p>Dragging is not tested. It is an enhancement and nothing depends on it (D-M10): every move it
 * offers is also on a button or a select, and those are what these tests press. jsdom has no drag
 * implementation to speak of, so a test of it would assert the mock rather than the behaviour.
 *
 * <p>The auth object is one stable reference for the reason `meal-kinds.test.tsx` gives: a mock that
 * rebuilds its closure every render re-triggers any effect listing `getToken`.
 */
const { authRef, saveMock, resetMock, refreshMock } = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: {
        role: "TEMPLE_ADMIN",
        fullName: "Test Person",
        menuLayout: null as MenuLayout | null,
      },
      getToken: async () => "test-token",
      refresh: async () => {},
    },
  },
  saveMock: vi.fn(),
  resetMock: vi.fn(),
  refreshMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/settings/menu",
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, saveMenuLayout: saveMock, resetMenuLayout: resetMock },
  };
});

import MenuLayoutPage from "@/app/settings/menu/page";

/** The standard menu narrowed to what a Temple Admin can reach — what an untouched board holds. */
function adminBoard(): { id: string; title: string | null; items: string[] }[] {
  return standardMenu().map((group) => ({
    id: group.id,
    title: group.title ?? null,
    items: group.items.filter((item) => item.roles.includes("TEMPLE_ADMIN")).map((item) => item.id),
  }));
}

/** Every destination an administrator can reach, by id. */
function adminItemIds(): string[] {
  return adminBoard().flatMap((group) => group.items);
}

/** The editor, never the real menu beside it: both draw the same labels, on purpose (D-M9). */
function board() {
  return within(screen.getByRole("main"));
}

/**
 * The inline New group panel.
 *
 * <p>Scoped, because its heading box and the seven group heading boxes all sit inside a label whose
 * visible word is "Heading" — which is right on the screen, where each one is beside the group it
 * belongs to, and ambiguous to a query that can see the whole page at once. The group boxes carry an
 * `aria-label` naming their group, so a screen reader is never in that position.
 */
function newGroupForm() {
  return within(screen.getByRole("form", { name: "New group" }));
}

/** The headings of every group on the board, in order. Empty string for a group with no heading. */
function headings(): string[] {
  return board()
    .getAllByRole("textbox")
    .map((box) => (box as HTMLInputElement).value);
}

/** The items of one group, by label, in order. */
function itemsIn(groupName: string): string[] {
  const region = screen.getByRole("region", { name: `Group: ${groupName}` });
  return within(region)
    .queryAllByRole("combobox")
    .map((box) => box.getAttribute("aria-label"))
    .filter((label): label is string => !!label && label.startsWith("Move "))
    .map((label) => label.replace(/^Move /, "").replace(/ to another group$/, ""));
}

function renderScreen(layout: MenuLayout | null = null) {
  authRef.current.appUser = {
    role: "TEMPLE_ADMIN",
    fullName: "Test Person",
    menuLayout: layout,
  };
  authRef.current.refresh = async () => {
    refreshMock();
  };
  return render(<MenuLayoutPage />);
}

beforeEach(() => {
  saveMock.mockReset().mockResolvedValue(undefined);
  resetMock.mockReset().mockResolvedValue(undefined);
  refreshMock.mockReset();
});

describe("the board", () => {
  it("is built from the temple's saved arrangement, not from the standard menu", () => {
    renderScreen({
      version: 1,
      groups: [
        { id: "temple", title: "Temple", items: ["settings", "audit"] },
        { id: "main", title: null, items: ["today"] },
      ],
    });

    // The two groups the arrangement names come out in its order, with its own item order inside
    // them. Everything it does not place lands at the bottom under "New", which is `nav.ts`'s rule
    // for a destination whose standard group the temple no longer has.
    expect(headings()).toEqual(["Temple", "", "New"]);
    expect(itemsIn("Temple").slice(0, 2)).toEqual(["Settings", "Audit log"]);
    expect(itemsIn("group 2")[0]).toBe("Today");
  });

  it("holds every destination an administrator can reach, and only those", () => {
    renderScreen();

    const onBoard = adminBoard().flatMap((group) =>
      itemsIn(group.title ?? "group 1")
    );
    expect(onBoard).toHaveLength(adminItemIds().length);
    // The volunteer's four and the operator's five are not arrangeable here (D-M7). They are not
    // lost: the merge puts each one back at the end of its standard group.
    expect(board().queryByLabelText("Move Donate to another group")).toBeNull();
    expect(board().queryByLabelText("Move Temples to another group")).toBeNull();
  });
});

describe("moving an item", () => {
  it("moves up and down within its group", () => {
    renderScreen();
    expect(itemsIn("Ordering").slice(0, 3)).toEqual([
      "Shopping list",
      "Purchase orders",
      "Deliveries",
    ]);

    fireEvent.click(board().getByRole("button", { name: "Move Deliveries up" }));
    expect(itemsIn("Ordering").slice(0, 3)).toEqual([
      "Shopping list",
      "Deliveries",
      "Purchase orders",
    ]);

    fireEvent.click(board().getByRole("button", { name: "Move Deliveries down" }));
    expect(itemsIn("Ordering").slice(0, 3)).toEqual([
      "Shopping list",
      "Purchase orders",
      "Deliveries",
    ]);
  });

  it("rolls off the bottom of a group into the top of the next one", () => {
    renderScreen();
    expect(itemsIn("Ordering").at(-1)).toBe("Vendor performance");

    fireEvent.click(board().getByRole("button", { name: "Move Vendor performance down" }));

    expect(itemsIn("Ordering")).not.toContain("Vendor performance");
    expect(itemsIn("Inventory & Recipes")[0]).toBe("Vendor performance");
  });

  it("rolls off the top of a group into the bottom of the one above", () => {
    renderScreen();
    expect(itemsIn("Ordering")[0]).toBe("Shopping list");

    fireEvent.click(board().getByRole("button", { name: "Move Shopping list up" }));

    expect(itemsIn("Ordering")).not.toContain("Shopping list");
    expect(itemsIn("group 1").at(-1)).toBe("Shopping list");
  });

  it("goes to the end of any group through Move to group", () => {
    renderScreen();

    fireEvent.change(board().getByLabelText("Move Deliveries to another group"), {
      target: { value: "people" },
    });

    expect(itemsIn("Ordering")).not.toContain("Deliveries");
    expect(itemsIn("People").at(-1)).toBe("Deliveries");
  });

  it("says what moved and where it landed, where a screen reader will hear it", () => {
    renderScreen();
    fireEvent.click(board().getByRole("button", { name: "Move Deliveries up" }));

    expect(screen.getByRole("status")).toHaveTextContent(
      "Deliveries is now 2 of 6 in Ordering."
    );
  });
});

describe("moving a group", () => {
  it("reorders whole groups and stops at the ends", () => {
    renderScreen();
    expect(headings()).toEqual([
      "",
      "Ordering",
      "Inventory & Recipes",
      "Kitchens",
      "People",
      "Giving & Outreach",
      "Temple",
    ]);

    fireEvent.click(board().getByRole("button", { name: "Move Kitchens up" }));
    expect(headings()[2]).toBe("Kitchens");
    expect(headings()[3]).toBe("Inventory & Recipes");

    // The first group has nowhere above it, and the control says so rather than doing nothing.
    expect(board().getByRole("button", { name: "Move group 1 up" })).toBeDisabled();
    expect(board().getByRole("button", { name: "Move Temple down" })).toBeDisabled();
  });
});

describe("groups a temple makes and unmakes", () => {
  it("adds a group from an inline form, not a browser dialog", async () => {
    renderScreen();

    fireEvent.click(board().getByRole("button", { name: "New group" }));
    fireEvent.change(newGroupForm().getByLabelText("Heading"), { target: { value: "Evening" } });
    fireEvent.click(screen.getByRole("button", { name: "Add group" }));

    await waitFor(() => expect(headings().at(-1)).toBe("Evening"));
    expect(
      screen.getByRole("region", { name: "Group: Evening" })
    ).toHaveTextContent("A group with nothing in it doesn’t appear in the menu.");
  });

  it("renames a heading in place, and the rename is what gets saved", async () => {
    renderScreen();

    fireEvent.change(board().getByLabelText("Heading for Ordering"), {
      target: { value: "Buying" },
    });
    expect(headings()[1]).toBe("Buying");

    fireEvent.click(board().getByRole("button", { name: "Save" }));
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));
    expect(saveMock.mock.calls[0][0].groups[1]).toEqual({
      id: "ordering",
      title: "Buying",
      items: adminBoard()[1].items,
    });
  });

  it("deletes an empty group at once, and one with items only after asking where they go", async () => {
    renderScreen();

    // Empty first: made, then deleted, with nothing in between.
    fireEvent.click(board().getByRole("button", { name: "New group" }));
    fireEvent.change(newGroupForm().getByLabelText("Heading"), { target: { value: "Evening" } });
    fireEvent.click(screen.getByRole("button", { name: "Add group" }));
    await waitFor(() => expect(headings()).toContain("Evening"));

    fireEvent.click(board().getByRole("button", { name: "Delete Evening" }));
    expect(headings()).not.toContain("Evening");

    // Now one that holds something. Nothing goes until the panel is answered.
    fireEvent.click(board().getByRole("button", { name: "Delete Giving & Outreach" }));
    expect(headings()).toContain("Giving & Outreach");
    const panel = screen.getByRole("group", { name: "Delete Giving & Outreach" });
    expect(panel).toHaveTextContent("This group holds 3 items. Move them to");

    fireEvent.change(within(panel).getByRole("combobox"), { target: { value: "people" } });
    fireEvent.click(within(panel).getByRole("button", { name: "Move and delete group" }));

    expect(headings()).not.toContain("Giving & Outreach");
    expect(itemsIn("People").slice(-3)).toEqual(["Donations", "Wish list", "Communications"]);
  });

  it("keeps a group the administrator has emptied, until they delete it themselves (D-M2)", () => {
    renderScreen();

    for (const item of ["Donations", "Wish list", "Communications"]) {
      fireEvent.change(board().getByLabelText(`Move ${item} to another group`), {
        target: { value: "people" },
      });
    }

    expect(headings()).toContain("Giving & Outreach");
    expect(itemsIn("Giving & Outreach")).toEqual([]);
    expect(
      screen.getByRole("region", { name: "Group: Giving & Outreach" })
    ).toHaveTextContent("Nothing in this group yet.");
  });

  it("will not delete the last group left", () => {
    // One group holding everything, so there is no second group to move anything into.
    renderScreen({
      version: 1,
      groups: [{ id: "main", title: null, items: adminItemIds() }],
    });

    expect(headings()).toEqual([""]);
    expect(board().getByRole("button", { name: "Delete group 1" })).toBeDisabled();
    expect(board().getByText("A menu needs one group, so this one can’t be deleted.")).toBeTruthy();
  });
});

/**
 * The group that holds what this board never shows (D-M11, T-426).
 *
 * <p>Nine destinations are not on this board — a volunteer's four and the platform operator's five
 * — and `applyMenuLayout` puts each one at the end of its standard group, which for all nine is the
 * group whose id is `main`. Delete that group and the merge has nowhere to put them, so they go to
 * the bottom of a volunteer's menu under a heading reading "New", and the administrator who did it
 * cannot see it happen. So it cannot be deleted.
 *
 * <p><b>The rule is the group's id, not the first position</b>, and the last test here is the one
 * that records that choice: with another group moved above it, the group on top can be deleted and
 * the one that is no longer first still cannot. `applyMenuLayout` settles it — it places an unplaced
 * destination with `mergedById.get(group.id)`, looking the standard group's id up in the
 * arrangement, wherever that group now sits.
 */
const PROTECTED_REFUSAL =
  "Other people see destinations in this group that you can’t, so it can’t be deleted. You can still move your own items out of it.";
const LAST_GROUP_REFUSAL = "A menu needs one group, so this one can’t be deleted.";

/**
 * Every refusal sentence on the board, in order — the whole of what the screen says about deleting.
 *
 * <p>`selector: "p"` because a partial matcher otherwise matches every ancestor of the sentence as
 * well, up to `main`, and a count of those would mean nothing. Counting these is how each test below
 * shows the two refusals have not collided into two sentences over one button.
 */
function refusalsOnBoard(): string[] {
  return board()
    .queryAllByText(/can’t be deleted/, { selector: "p" })
    .map((p) => p.textContent ?? "");
}

describe("the group that can’t be deleted", () => {
  it("refuses to delete it, and says why", () => {
    renderScreen();

    expect(board().getByRole("button", { name: "Delete group 1" })).toBeDisabled();
    expect(refusalsOnBoard()).toEqual([PROTECTED_REFUSAL]);
  });

  it("lets every other group be deleted, including one the temple made itself", async () => {
    renderScreen();

    for (const heading of ["Ordering", "Inventory & Recipes", "Kitchens", "People", "Giving & Outreach", "Temple"]) {
      expect(board().getByRole("button", { name: `Delete ${heading}` })).toBeEnabled();
    }

    fireEvent.click(board().getByRole("button", { name: "New group" }));
    fireEvent.change(newGroupForm().getByLabelText("Heading"), { target: { value: "Evening" } });
    fireEvent.click(screen.getByRole("button", { name: "Add group" }));
    await waitFor(() => expect(headings()).toContain("Evening"));

    expect(board().getByRole("button", { name: "Delete Evening" })).toBeEnabled();
    fireEvent.click(board().getByRole("button", { name: "Delete Evening" }));
    expect(headings()).not.toContain("Evening");

    // One refusal on the whole board, which is the positive half of the claim above: a board where
    // every Delete had been greyed out would pass the loop only if it also failed this.
    expect(refusalsOnBoard()).toEqual([PROTECTED_REFUSAL]);
  });

  it("still lets the administrator empty it (D-M2)", () => {
    renderScreen();
    expect(itemsIn("group 1")).toEqual([
      "Today",
      "Vaishnava calendar",
      "Meal planner",
      "Reuse a plan",
      "Cost per serving",
    ]);

    for (const item of ["Today", "Vaishnava calendar", "Meal planner", "Reuse a plan", "Cost per serving"]) {
      fireEvent.change(board().getByLabelText(`Move ${item} to another group`), {
        target: { value: "temple" },
      });
    }

    // Emptied, still on the board with its empty-state line, and still not deletable.
    expect(headings()[0]).toBe("");
    expect(itemsIn("group 1")).toEqual([]);
    expect(screen.getByRole("region", { name: "Group: group 1" })).toHaveTextContent(
      "Nothing in this group yet."
    );
    expect(board().getByRole("button", { name: "Delete group 1" })).toBeDisabled();
    expect(refusalsOnBoard()).toEqual([PROTECTED_REFUSAL]);
  });

  it("gives the last group left its own sentence, and only one of the two", async () => {
    // Both refusals are true of this board: one group, and it is the protected one. The last group
    // left is the sentence that binds whatever group it is, so that is the one shown — and the other
    // appears the moment a second group makes it the refusal that is actually in the way.
    renderScreen({
      version: 1,
      groups: [{ id: "main", title: null, items: adminItemIds() }],
    });

    expect(board().getByRole("button", { name: "Delete group 1" })).toBeDisabled();
    expect(refusalsOnBoard()).toEqual([LAST_GROUP_REFUSAL]);

    fireEvent.click(board().getByRole("button", { name: "New group" }));
    fireEvent.change(newGroupForm().getByLabelText("Heading"), { target: { value: "Evening" } });
    fireEvent.click(screen.getByRole("button", { name: "Add group" }));
    await waitFor(() => expect(headings()).toContain("Evening"));

    expect(refusalsOnBoard()).toEqual([PROTECTED_REFUSAL]);
    expect(board().getByRole("button", { name: "Delete group 1" })).toBeDisabled();
    expect(board().getByRole("button", { name: "Delete Evening" })).toBeEnabled();
  });

  it("protects the group by which group it is, not by where it sits", () => {
    // Temple on top, and the group those nine destinations belong to second.
    renderScreen({
      version: 1,
      groups: [
        { id: "temple", title: "Temple", items: ["settings", "audit"] },
        { id: "main", title: null, items: ["today"] },
      ],
    });
    expect(headings()).toEqual(["Temple", "", "New"]);

    // The group that is now first is an ordinary group, and it can go.
    expect(board().getByRole("button", { name: "Delete Temple" })).toBeEnabled();
    // The one that is no longer first is still the one the nine land in, so it still cannot.
    expect(board().getByRole("button", { name: "Delete group 2" })).toBeDisabled();
    expect(refusalsOnBoard()).toEqual([PROTECTED_REFUSAL]);

    // And deleting the group above it really does happen, so the assertion above is not reading a
    // board that refuses everything.
    fireEvent.click(board().getByRole("button", { name: "Delete Temple" }));
    const panel = screen.getByRole("group", { name: "Delete Temple" });
    fireEvent.click(within(panel).getByRole("button", { name: "Move and delete group" }));
    expect(headings()).not.toContain("Temple");
  });
});

describe("saving, cancelling and resetting", () => {
  it("sends exactly the arrangement on the screen, then refreshes the session", async () => {
    renderScreen();

    // Nothing to save until something has changed, and the screen says which state it is in.
    expect(board().getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.click(board().getByRole("button", { name: "Move Deliveries up" }));
    expect(board().getByText("Not saved yet")).toBeTruthy();

    fireEvent.click(board().getByRole("button", { name: "Save" }));
    await waitFor(() => expect(saveMock).toHaveBeenCalledTimes(1));

    const expected = adminBoard();
    expected[1] = {
      ...expected[1],
      items: ["shopping-list", "deliveries", "orders", "invoices", "vendors", "vendor-performance"],
    };
    expect(saveMock.mock.calls[0][0]).toEqual({ version: 1, groups: expected });
    // The real menu on the left of this screen repaints from the session, so saving has to refresh
    // it. Without this the administrator saves and sees nothing change (D-M9).
    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(saveMock.mock.invocationCallOrder[0]).toBeLessThan(refreshMock.mock.invocationCallOrder[0]);
  });

  it("puts the saved arrangement back when Cancel is pressed", () => {
    renderScreen();

    fireEvent.click(board().getByRole("button", { name: "Move Deliveries up" }));
    expect(itemsIn("Ordering")[1]).toBe("Deliveries");

    fireEvent.click(board().getByRole("button", { name: "Cancel" }));

    expect(itemsIn("Ordering")[1]).toBe("Purchase orders");
    expect(board().getByRole("button", { name: "Save" })).toBeDisabled();
    expect(saveMock).not.toHaveBeenCalled();
  });

  it("asks before resetting, and only then puts the standard menu back", async () => {
    renderScreen({
      version: 1,
      groups: [{ id: "main", title: null, items: adminItemIds() }],
    });

    fireEvent.click(board().getByRole("button", { name: "Reset to the standard menu" }));
    expect(resetMock).not.toHaveBeenCalled();
    expect(
      screen.getByText("Reset the menu for everyone at this temple? This can’t be undone, though you can arrange it again.")
    ).toBeTruthy();

    fireEvent.click(board().getByRole("button", { name: "Reset to the standard menu" }));
    await waitFor(() => expect(resetMock).toHaveBeenCalledTimes(1));
    expect(refreshMock).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(headings()).toHaveLength(7));
  });

  it("shows the server's own refusal, in its own words", async () => {
    renderScreen();
    saveMock.mockRejectedValue(
      new ApiError(
        {
          code: "KMS-400190",
          message: "A menu item can only be in one group.",
          action: "Take the repeated item out of one of the groups, then save again.",
          fieldErrors: [],
        },
        409
      )
    );

    fireEvent.click(board().getByRole("button", { name: "Move Deliveries up" }));
    fireEvent.click(board().getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("A menu item can only be in one group.")
    );
    expect(screen.getByRole("alert")).toHaveTextContent("KMS-400190");
  });
});

describe("what this screen deliberately cannot do", () => {
  /**
   * An absence, stated as one. Renaming and hiding are forbidden by the product — a label has to
   * match the page title, the help and the training, and a temple that does not use a feature
   * switches the feature off rather than taking its row off the menu.
   *
   * <p>Both halves of this would pass on an empty page, so each is paired with a count that would
   * not: thirty-three destinations are on the board, and the only boxes anybody can type in are the
   * seven group headings.
   */
  it("offers no way to rename an item or to hide one", () => {
    renderScreen();

    expect(board().queryByRole("button", { name: /rename/i })).toBeNull();
    expect(board().queryByRole("button", { name: /hide/i })).toBeNull();
    expect(board().queryAllByRole("checkbox")).toHaveLength(0);

    // The positive halves, so neither claim above can pass on a board that failed to draw.
    expect(board().getAllByLabelText(/ to another group$/)).toHaveLength(adminItemIds().length);
    expect(headings()).toHaveLength(7);
  });
});
