import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import UsersPage from "@/app/users/page";

describe("user management", () => {
  it("offers an add-person form and an inviting empty state", () => {
    render(<UsersPage />);

    expect(screen.getByRole("heading", { name: /people/i })).toBeInTheDocument();

    const form = screen.getByRole("form", { name: /add a person/i });
    expect(within(form).getByLabelText(/full name/i)).toBeInTheDocument();
    expect(within(form).getByLabelText(/email/i)).toBeInTheDocument();
    expect(within(form).getByLabelText(/phone/i)).toBeInTheDocument();
    expect(within(form).getByRole("button", { name: /add person/i })).toBeInTheDocument();

    expect(screen.getByText(/just you so far/i)).toBeInTheDocument();
  });

  it("offers the fixed roles but not platform operator", () => {
    render(<UsersPage />);
    const role = screen.getByLabelText(/role/i);
    expect(within(role).getByRole("option", { name: /temple admin/i })).toBeInTheDocument();
    expect(within(role).getByRole("option", { name: /kitchen staff/i })).toBeInTheDocument();
    expect(within(role).getByRole("option", { name: /volunteer/i })).toBeInTheDocument();
    expect(within(role).queryByRole("option", { name: /super|platform/i })).not.toBeInTheDocument();
  });

  it("marks people as the current page in navigation", () => {
    render(<UsersPage />);
    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("link", { current: "page" })).toHaveTextContent(/people/i);
  });
});
