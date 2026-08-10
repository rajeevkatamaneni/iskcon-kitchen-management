import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import ProfilePage from "@/app/profile/page";

describe("profile", () => {
  it("shows contact details, a channel choice, and the consent ask", () => {
    render(<ProfilePage />);

    expect(screen.getByRole("heading", { name: /your account/i })).toBeInTheDocument();

    // The three channels, WhatsApp first (India-first) and selected by default.
    const whatsapp = screen.getByRole("radio", { name: /whatsapp/i });
    expect(whatsapp).toBeChecked();
    expect(screen.getByRole("radio", { name: /sms/i })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: /email/i })).toBeInTheDocument();

    // Consent is a clear, plain-language ask with an explicit action.
    expect(screen.getByText(/withdraw this consent at any time/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /i agree/i })).toBeInTheDocument();
  });

  it("presents contact details as read-only, to be changed by an administrator", () => {
    render(<ProfilePage />);

    const contact = screen.getByRole("region", { name: /contact details/i });
    // No inputs in the contact section — it is a display, not a form.
    expect(within(contact).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(contact).getByText(/ask your temple administrator/i)).toBeInTheDocument();
  });

  it("marks profile as the current page in navigation", () => {
    render(<ProfilePage />);

    const nav = screen.getByRole("navigation", { name: /main/i });
    expect(within(nav).getByRole("link", { current: "page" })).toHaveTextContent(/profile/i);
  });
});
