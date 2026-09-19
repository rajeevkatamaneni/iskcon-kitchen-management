import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { PageHeader } from "@/components/ds/PageHeader";

/**
 * The shared page header's actions wrap when they do not fit (T-277).
 *
 * <p>Its actions box used to be `flex-none`: as wide as all its buttons on one line, whatever the
 * screen. An invoice's three actions (Pay this invoice, Record a credit note, Void this bill) then ran
 * 91px past a 390px phone, and the page was 481px wide (measured in T-274 and again in T-277 against
 * the old component). The box now may shrink (`min-w-0`) and wraps (`flex-wrap`), so buttons that fit
 * stay side by side and only the one that does not goes to a new line.
 *
 * <p>jsdom does no layout, so this pins the classes that make the wrapping happen. The geometry itself
 * was measured in headless Chrome on every page that uses PageHeader, at 1280 and 390, before and after
 * (docs/work/proof/T-277.md): nothing moved at either width, and the invoice page at 390 went from
 * 481px wide to 390.
 */
describe("PageHeader", () => {
  const three = (
    <PageHeader
      title="KVM/2026/0917"
      subtitle="Kalasipalya Vegetable Mandi"
      actions={
        <>
          <button type="button">Pay this invoice</button>
          <button type="button">Record a credit note</button>
          <button type="button">Void this bill</button>
        </>
      }
    />
  );

  it("lets its actions shrink and wrap, never holding them on one line wider than the screen", () => {
    render(three);
    const box = screen.getByRole("button", { name: "Pay this invoice" }).parentElement!;
    expect(box).toHaveClass("flex", "flex-wrap", "min-w-0");
    expect(box).not.toHaveClass("flex-none");
    expect(box).not.toHaveClass("shrink-0");
  });

  it("keeps every action in the one box, in order, beside the title's block", () => {
    render(three);
    const box = screen.getByRole("button", { name: "Pay this invoice" }).parentElement!;
    expect([...box.children].map((b) => b.textContent)).toEqual([
      "Pay this invoice",
      "Record a credit note",
      "Void this bill",
    ]);
    // The title's block and the actions share one wrapping row: the actions go under the title only
    // when both cannot sit side by side.
    const row = box.parentElement!;
    expect(row).toHaveClass("flex", "flex-wrap");
    expect(row).toContainElement(screen.getByRole("heading", { level: 1, name: "KVM/2026/0917" }));
  });

  it("draws no actions box when there are no actions", () => {
    render(<PageHeader title="Deliveries" subtitle="What vendors are bringing, and what has come in." />);
    const row = screen.getByRole("heading", { level: 1 }).parentElement!.parentElement!;
    expect(row.children).toHaveLength(1);
  });
});
