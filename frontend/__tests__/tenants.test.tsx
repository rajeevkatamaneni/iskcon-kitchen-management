import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import TenantsPage from "@/app/tenants/page";
import { ErrorNotice } from "@/components/ErrorNotice";
import { Field } from "@/components/Field";
import { ApiError } from "@/lib/api";

describe("tenants list", () => {
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
