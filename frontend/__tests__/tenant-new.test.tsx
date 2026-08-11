import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiError } from "@/lib/api";

// The form provisions a tenant behind SUPER_ADMIN. Mock auth (role + token) and the api call so
// we can assert exactly what the form sends — the point of these tests is that it normalizes
// input the strict server-side validation would otherwise reject, and derives the slug itself.
const { provisionSpy, pushMock, authRef } = vi.hoisted(() => ({
  provisionSpy: vi.fn(async (input: { slug: string }) => ({ slug: input.slug })),
  pushMock: vi.fn(),
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "SUPER_ADMIN" },
      getToken: async () => "token",
    } as {
      status: string;
      appUser: { role: string } | null;
      getToken: () => Promise<string>;
    },
  },
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock, replace: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, provisionTenant: provisionSpy } };
});

import NewTenantPage from "@/app/tenants/new/page";

describe("add a temple", () => {
  beforeEach(() => {
    provisionSpy.mockClear();
    pushMock.mockClear();
    authRef.current = {
      status: "signed-in",
      appUser: { role: "SUPER_ADMIN" },
      getToken: async () => "token",
    };
  });

  it("previews the derived web address, cleans the phone, and hands off on success", async () => {
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), {
      target: { value: "Sri Sri Radha Govinda Temple" },
    });
    // The slug is shown as a read-only preview under the name, never an editable field.
    expect(screen.queryByLabelText(/link name/i)).not.toBeInTheDocument();
    expect(screen.getByText("/t/sri-sri-radha-govinda-temple")).toBeInTheDocument();

    // A number a person would actually type — spaces, and a zero-width character riding along.
    fireEvent.change(screen.getByLabelText(/phone number/i), {
      target: { value: "+91 70304 33344​" },
    });

    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as { slug: string; adminPhone: string };
    expect(sent.slug).toBe("sri-sri-radha-govinda-temple");
    expect(sent.adminPhone).toBe("+917030433344");

    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/tenants?created=sri-sri-radha-govinda-temple")
    );
  });

  it("steers a duplicate-name web-address clash to the Name field", async () => {
    provisionSpy.mockRejectedValueOnce(
      new ApiError({
        code: "KMS-4901",
        message: "Another temple is already using that web address.",
        action: "Choose a different one.",
        fieldErrors: [],
      })
    );
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "ISKCON Bangalore" } });
    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    // The clash is really a name clash, since the slug is hidden and derived from the name.
    expect(await screen.findByText(/very similar name already exists/i)).toBeInTheDocument();
  });
});
