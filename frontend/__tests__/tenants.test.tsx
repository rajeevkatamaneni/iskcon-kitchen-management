import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Field } from "@/components/Field";
import { ApiError, type TenantSummary } from "@/lib/api";

// The tenants screen is guarded and fetches live data. Drive both from mutable refs so each
// test can put the user in a role and the query in a state, without hitting Firebase or fetch.
const { authRef, queryRef, replaceMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "SUPER_ADMIN" } } as {
      status: string;
      appUser: { role: string } | null;
    },
  },
  queryRef: {
    current: { data: [] as TenantSummary[] | null, error: null as ApiError | null, loading: false },
  },
  replaceMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: replaceMock }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/use-authed-query", () => ({ useAuthedQuery: () => queryRef.current }));

import TenantsPage from "@/app/tenants/page";

function tenant(overrides: Partial<TenantSummary>): TenantSummary {
  return {
    id: "t-1",
    slug: "radha-govinda",
    name: "Sri Sri Radha Govinda Temple",
    timezone: "Asia/Kolkata",
    currency: "INR",
    is_80g_approved: false,
    status: "ACTIVE",
    created_at: "2026-08-01T00:00:00Z",
    user_count: 0,
    ...overrides,
  };
}

describe("tenants list", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "SUPER_ADMIN" } };
    queryRef.current = { data: [], error: null, loading: false };
    replaceMock.mockClear();
  });

  it("invites the first action when there are no temples", () => {
    // An empty state is an invitation, not an apology — and it is the first thing a new
    // platform operator ever sees.
    render(<TenantsPage />);

    expect(screen.getByRole("heading", { name: /temples/i })).toBeInTheDocument();
    expect(screen.getByText(/no temples yet/i)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /add the first temple/i })
    ).toBeInTheDocument();
  });

  it("shows each temple from live data", () => {
    queryRef.current = {
      data: [
        tenant({ id: "t-1", name: "Radha Govinda", slug: "radha-govinda", user_count: 4, is_80g_approved: true }),
        tenant({ id: "t-2", name: "Krishna Balaram", slug: "krishna-balaram", user_count: 1, is_80g_approved: false }),
      ],
      error: null,
      loading: false,
    };
    render(<TenantsPage />);

    expect(screen.getByText("Radha Govinda")).toBeInTheDocument();
    expect(screen.getByText("krishna-balaram")).toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
    expect(screen.getByText("Approved")).toBeInTheDocument();
    expect(screen.getByText("Not approved")).toBeInTheDocument();
    expect(screen.queryByText(/no temples yet/i)).not.toBeInTheDocument();
  });

  it("says it is loading before the data arrives", () => {
    queryRef.current = { data: null, error: null, loading: true };
    render(<TenantsPage />);

    expect(screen.getByText(/loading temples/i)).toBeInTheDocument();
  });

  it("shows the error contract when the load fails", () => {
    queryRef.current = {
      data: null,
      loading: false,
      error: new ApiError({
        code: "KMS-0000",
        message: "We couldn't load this.",
        action: "Try again.",
        fieldErrors: [],
      }),
    };
    render(<TenantsPage />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("KMS-0000")).toBeInTheDocument();
  });

  it("refuses a signed-in user who is not a platform operator", () => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN" } };
    render(<TenantsPage />);

    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^temples$/i })).not.toBeInTheDocument();
  });

  it("marks the current page in navigation", () => {
    render(<TenantsPage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    const current = within(nav).getByRole("link", { current: "page" });

    expect(current).toHaveTextContent(/temples/i);
  });
});

describe("error notice", () => {
  it("shows what happened, what to do, and the code to quote", () => {
    const error = new ApiError({
      code: "KMS-4901",
      message: "Another temple is already using that web address.",
      action: "Choose a different one.",
      fieldErrors: [],
    });

    render(<ErrorNotice error={error} />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText(/already using that web address/i)).toBeInTheDocument();
    expect(screen.getByText(/choose a different one/i)).toBeInTheDocument();
    expect(screen.getByText("KMS-4901")).toBeInTheDocument();
  });
});

describe("field", () => {
  it("announces its error to assistive technology", () => {
    // Red text alone is not an error message for someone using a screen reader, and this is
    // the component every form in the application will use.
    render(
      <Field id="slug" label="Web address" error="Use only lowercase letters.">
        {(props) => <input {...props} name="slug" />}
      </Field>
    );

    const input = screen.getByLabelText(/web address/i);

    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription(/use only lowercase letters/i);
  });

  it("keeps its label visible rather than relying on a placeholder", () => {
    render(
      <Field id="name" label="Name">
        {(props) => <input {...props} name="name" placeholder="Sri Radha Govinda" />}
      </Field>
    );

    // A placeholder disappears the moment someone types — exactly when an unfamiliar form
    // most needs to say what is being asked.
    expect(screen.getByText("Name")).toBeInTheDocument();
  });
});
