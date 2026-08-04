import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import HomePage from "@/app/page";

describe("HomePage", () => {
  it("renders the temple name as the main heading", () => {
    render(<HomePage />);
    expect(
      screen.getByRole("heading", { name: /iskcon seva kitchen/i })
    ).toBeInTheDocument();
  });
});
