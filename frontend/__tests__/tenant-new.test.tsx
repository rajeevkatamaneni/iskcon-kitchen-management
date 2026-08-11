import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

// The form provisions a tenant behind SUPER_ADMIN. Mock auth (role + token) and the api call so
// we can assert exactly what the form sends — the point of these tests is that it normalizes
// input the strict server-side validation would otherwise reject.
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

  it("suggests a slug from the name and strips junk from the phone before submitting", async () => {
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), {
      target: { value: "Sri Sri Radha Govinda Temple" },
    });
    // The slug follows the name, valid by construction.
    expect(screen.getByLabelText(/web address/i)).toHaveValue("sri-sri-radha-govinda-temple");

    // A number a person would actually type — spaces, and a zero-width character riding along.
    fireEvent.change(screen.getByLabelText(/phone number/i), {
      target: { value: "+91 70304 33344​" },
    });

    fireEvent.click(screen.getByRole("button", { name: /add temple/i }));

    await waitFor(() => expect(provisionSpy).toHaveBeenCalledTimes(1));
    const sent = provisionSpy.mock.calls[0][0] as { slug: string; adminPhone: string };
    expect(sent.slug).toBe("sri-sri-radha-govinda-temple");
    expect(sent.adminPhone).toBe("+917030433344");

    // On success it hands off to the temples list, which shows the confirmation there.
    await waitFor(() =>
      expect(pushMock).toHaveBeenCalledWith("/tenants?created=sri-sri-radha-govinda-temple")
    );
  });

  it("stops following the name once the slug is edited by hand", () => {
    render(<NewTenantPage />);

    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "Radha" } });
    expect(screen.getByLabelText(/web address/i)).toHaveValue("radha");

    fireEvent.change(screen.getByLabelText(/web address/i), { target: { value: "custom-address" } });
    fireEvent.change(screen.getByLabelText(/^name/i), { target: { value: "Radha Govinda" } });

    // The hand-edited slug is left alone.
    expect(screen.getByLabelText(/web address/i)).toHaveValue("custom-address");
  });
});
