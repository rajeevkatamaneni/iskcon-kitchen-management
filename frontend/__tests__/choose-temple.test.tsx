import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * Where a devotee lands when Google has vouched for them and no temple has.
 *
 * <p>Untested until now, and it appeared in the suite only as a redirect *target* — `RequireRole`
 * sends a `no-account` session here, and two tests asserted that call. Nothing had ever rendered the
 * screen those people arrive at.
 *
 * <p>It is reachable with a Firebase identity but no account of ours, which is the closest thing to
 * signed-out that a guarded product has: there is no `appUser`, no tenant, and no permission. So the
 * states asserted here are the three the page actually distinguishes — still loading, already
 * belonging somewhere (which is a bounce, not a screen), and belonging nowhere (which is the screen).
 *
 * <p>The line about which account they are signed in as is treated as part of the contract. It used
 * to sit at the foot of the form in the quietest grey in the palette; Rajeev lost ten minutes on
 * 2026-08-30 to having picked the wrong Google account with the answer on screen the whole time, and
 * it was moved into the header for that reason. A test that only checked the heading would not
 * notice it drifting back down.
 */

const { authRef, replaceMock, signOutMock, refreshMock, temples, joinTemple, setActiveTempleId } =
  vi.hoisted(() => {
    const signOutMock = vi.fn(async () => {});
    const refreshMock = vi.fn(async () => {});
    return {
      signOutMock,
      refreshMock,
      // Mutated in place: a new object per render would re-arm every effect that depends on it.
      authRef: {
        current: {
          status: "no-account",
          user: {
            email: "gopal@example.org",
            phoneNumber: null,
            displayName: "Gopal Das",
          } as Record<string, unknown> | null,
          appUser: null,
          getToken: async () => "token-abc",
          signOut: signOutMock,
          refresh: refreshMock,
        },
      },
      replaceMock: vi.fn(),
      temples: vi.fn(),
      joinTemple: vi.fn(),
      setActiveTempleId: vi.fn(),
    };
  });

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, temples, joinTemple },
    setActiveTempleId,
  };
});

import ChooseTemplePage from "@/app/choose-temple/page";

const MYSORE = { id: "t1", name: "ISKCON Mysore", address: "Jayalakshmipuram", distanceKm: 4 };

function withNoTemple() {
  authRef.current.status = "no-account";
  authRef.current.user = {
    email: "gopal@example.org",
    phoneNumber: null,
    displayName: "Gopal Das",
  };
}

/** Choose Mysore in the picker, which waits 350ms after the last keystroke before it searches. */
async function pickMysore() {
  fireEvent.change(screen.getByLabelText(/search for your temple/i), {
    target: { value: "Mysore" },
  });
  fireEvent.click(
    await screen.findByRole("button", { name: /ISKCON Mysore/i }, { timeout: 3000 })
  );
}

describe("choosing a temple, with an identity but no account", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    withNoTemple();
    temples.mockResolvedValue([MYSORE]);
    joinTemple.mockResolvedValue({});
  });

  it("asks the one question, and says who is being asked", () => {
    render(<ChooseTemplePage />);

    expect(
      screen.getByRole("heading", { name: /which temple do you serve at\?/i })
    ).toBeInTheDocument();
    // In the header, above the form — not in grey at the foot of it.
    expect(screen.getByText("gopal@example.org")).toBeInTheDocument();
    expect(screen.getByText(/join more temples later/i)).toBeInTheDocument();
  });

  it("falls back to the phone number for somebody who signed in with one", () => {
    authRef.current.user = { email: null, phoneNumber: "+919876543210", displayName: null };
    render(<ChooseTemplePage />);

    expect(screen.getByText("+919876543210")).toBeInTheDocument();
  });

  it("offers the way out of the wrong Google account", async () => {
    render(<ChooseTemplePage />);

    fireEvent.click(screen.getByRole("button", { name: /use a different account/i }));

    await waitFor(() => expect(signOutMock).toHaveBeenCalled());
  });

  it("asks its own question in the picker rather than repeating the heading", () => {
    // The page has already asked "Which temple do you serve at?" — the box below it must not ask
    // the same thing again, which is what the picker's default label would do.
    render(<ChooseTemplePage />);

    expect(screen.getByLabelText(/search for your temple/i)).toBeInTheDocument();
  });

  it("pre-fills the name Google gave, split into the two the temple's list wants", () => {
    render(<ChooseTemplePage />);

    expect(screen.getByLabelText(/first name/i)).toHaveValue("Gopal");
    expect(screen.getByLabelText(/last name/i)).toHaveValue("Das");
  });

  it("will not join until a temple, a name and a reachable number are all there", async () => {
    render(<ChooseTemplePage />);

    const join = screen.getByRole("button", { name: /join this temple/i });
    expect(join).toBeDisabled();

    await pickMysore();
    expect(screen.getByRole("button", { name: /join this temple/i })).toBeDisabled();

    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /join this temple/i })).toBeEnabled()
    );
  });

  it("joins, remembers which temple the next request speaks for, and lands them somewhere", async () => {
    render(<ChooseTemplePage />);

    await pickMysore();
    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
    fireEvent.click(screen.getByRole("button", { name: /join this temple/i }));

    await waitFor(() =>
      expect(joinTemple).toHaveBeenCalledWith(
        "t1",
        { firstName: "Gopal", lastName: "Das", phone: "+919876543210", email: "gopal@example.org" },
        "token-abc"
      )
    );
    await waitFor(() => expect(setActiveTempleId).toHaveBeenCalledWith("t1"));
    await waitFor(() => expect(refreshMock).toHaveBeenCalled());
    await waitFor(() => expect(replaceMock).toHaveBeenCalledWith("/"));
  });

  it("explains a refused join with the code to quote, and stays on the screen", async () => {
    const { ApiError } = await import("@/lib/api");
    joinTemple.mockRejectedValue(
      new ApiError({
        code: "KMS-400021",
        message: "That temple is not accepting new devotees.",
        action: "Ask the temple administrator to add you.",
        fieldErrors: [],
      })
    );
    render(<ChooseTemplePage />);

    await pickMysore();
    fireEvent.change(screen.getByLabelText(/^phone$/i), { target: { value: "+919876543210" } });
    fireEvent.click(screen.getByRole("button", { name: /join this temple/i }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-400021")).toBeInTheDocument();
    expect(replaceMock).not.toHaveBeenCalled();
  });
});

describe("choosing a temple, when the question does not apply", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    withNoTemple();
    temples.mockResolvedValue([MYSORE]);
  });

  it("waits, rather than asking, while the session is still being worked out", () => {
    authRef.current.status = "loading";
    render(<ChooseTemplePage />);

    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: /which temple do you serve at\?/i })
    ).not.toBeInTheDocument();
    expect(replaceMock).not.toHaveBeenCalled();
  });

  it("sends a signed-out visitor to the front door instead of asking them anything", () => {
    authRef.current.status = "signed-out";
    authRef.current.user = null;
    render(<ChooseTemplePage />);

    expect(replaceMock).toHaveBeenCalledWith("/sign-in");
    expect(
      screen.queryByRole("heading", { name: /which temple do you serve at\?/i })
    ).not.toBeInTheDocument();
  });

  it("bounces somebody who already belongs to a temple", () => {
    authRef.current.status = "signed-in";
    render(<ChooseTemplePage />);

    expect(replaceMock).toHaveBeenCalledWith("/");
  });
});
