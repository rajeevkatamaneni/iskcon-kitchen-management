import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ApiError, WishlistItemView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as WishlistItemView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import WishlistAdminPage from "@/app/wishlist/page";
import NewWishlistItemPage from "@/app/wishlist/new/page";
import { api } from "@/lib/api";

function item(o: Partial<WishlistItemView>): WishlistItemView {
  return {
    id: "w1", title: "Rice sacks", description: null, imageRef: null, priceInr: 1000,
    category: "CONSUMABLE", quantityWanted: 10, paidInr: 3000, sortOrder: 0,
    status: "ACTIVE", note: null, createdAt: "2026-08-01T00:00:00Z", ...o,
  };
}

describe("wish-list admin", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [item({})], error: null, loading: false };
    reloadMock.mockReset();
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("lists items with the money given against their price, and sends adding one to its own screen", () => {
    render(<WishlistAdminPage />);
    expect(screen.getByRole("heading", { name: /wish list/i })).toBeInTheDocument();
    expect(screen.getByText("Rice sacks")).toBeInTheDocument();
    // Ten sacks at ₹1,000 is a ₹10,000 item; ₹3,000 of it is in hand. Never "3 of 10 sacks" —
    // the temple buys the sacks together out of whatever has been given.
    expect(screen.getByText("₹3,000 of ₹10,000")).toBeInTheDocument();
    // Five fields, so the form is a screen of its own rather than a panel over this list.
    expect(screen.getByRole("link", { name: /add an item/i })).toHaveAttribute("href", "/wishlist/new");
  });

  it("shows the confirmation a newly added item comes back with", () => {
    paramsRef.current = new URLSearchParams("added=Rice%20sacks");
    render(<WishlistAdminPage />);
    expect(screen.getByText(/Rice sacks is on the wish list\./i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/wishlist");
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<WishlistAdminPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("adding a wish-list item", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    pushMock.mockReset();
  });

  it("commits from the header, with Cancel beside it and no back-link", () => {
    render(<NewWishlistItemPage />);
    expect(screen.getByRole("form", { name: /add wish-list item/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add item/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/wishlist");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<NewWishlistItemPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

/*
 * T-166, slice F of the blank-required-fields wave.
 * Rajeev’s ruling, 2026-09-11: "Required fields should carry `required` on the element and if left
 * unfilled, we should at least show 'Required' in red on form submit. Ideally, we should say
 * 'Quantity is required' OR 'Note is required'."
 *
 * The title and the price carry `required`; the quantity does too but opens at 1, so it is not
 * refused. Add item sits in the header, outside the form, and points at it with form="…", which is
 * why this presses that button: a submit from outside the tag has to be checked just the same.
 */
describe("a blank wish-list item (T-166)", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [], error: null, loading: false };
    pushMock.mockReset();
  });

  it("names the blank title and price beside their boxes, from the header's button, and adds nothing", async () => {
    const create = vi.spyOn(api, "createWishlistItem").mockResolvedValue({} as never);
    render(<NewWishlistItemPage />);

    fireEvent.click(screen.getByRole("button", { name: /add item/i }));

    expectSaidBeside(screen.getByRole("textbox", { name: "Title" }), "Title is required");
    expectSaidBeside(screen.getByRole("spinbutton", { name: "Price (₹)" }), "Price (₹) is required");
    await settle();
    expect(create).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
    create.mockRestore();
  });

  it("adds the item once the two boxes are filled, so the refusal is not a dead end", async () => {
    const create = vi.spyOn(api, "createWishlistItem").mockResolvedValue({} as never);
    render(<NewWishlistItemPage />);

    fireEvent.change(screen.getByRole("textbox", { name: "Title" }), { target: { value: "Rice sacks" } });
    fireEvent.change(screen.getByRole("spinbutton", { name: "Price (₹)" }), { target: { value: "250" } });
    fireEvent.click(screen.getByRole("button", { name: /add item/i }));

    await waitFor(() =>
      expect(create).toHaveBeenCalledWith(
        expect.objectContaining({ title: "Rice sacks", priceInr: 250, quantityWanted: 1 }),
        "test-token"
      )
    );
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/wishlist?added=Rice%20sacks"));
    create.mockRestore();
  });
});

/**
 * The sentence Form puts beside a refused box. Checked three ways so that "beside" means something:
 * the box is marked invalid, it is described by that very sentence, and the sentence's slot sits
 * straight after the box, or after the label wrapping it.
 */
function expectSaidBeside(box: HTMLElement, sentence: string | RegExp) {
  const said = screen.getByText(sentence);
  expect(box).toHaveAttribute("aria-invalid", "true");
  expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
  expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
}

/** Lets a handler that awaits a token reach its API call, so "not called" is not merely "not yet". */
function settle() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
