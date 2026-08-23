import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

const { paramsRef } = vi.hoisted(() => ({ paramsRef: { current: new URLSearchParams() } }));
vi.mock("next/navigation", () => ({ useSearchParams: () => paramsRef.current }));

import { BackToRecipes } from "@/components/BackToRecipes";

describe("the way back from a recipe", () => {
  it("returns to the search that found it, when there was one", () => {
    paramsRef.current = new URLSearchParams("q=palya");
    render(<BackToRecipes />);
    const link = screen.getByRole("link");
    expect(link).toHaveTextContent("Back to search");
    expect(link).toHaveAttribute("href", "/recipes?q=palya");
  });

  it("returns to the list when nobody was searching", () => {
    paramsRef.current = new URLSearchParams();
    render(<BackToRecipes />);
    const link = screen.getByRole("link");
    expect(link).toHaveTextContent("Recipes");
    expect(link).not.toHaveTextContent("Back to search");
    expect(link).toHaveAttribute("href", "/recipes");
  });

  it("treats a box holding only spaces as an empty box", () => {
    paramsRef.current = new URLSearchParams("q=%20%20");
    render(<BackToRecipes />);
    expect(screen.getByRole("link")).toHaveAttribute("href", "/recipes");
  });
});
