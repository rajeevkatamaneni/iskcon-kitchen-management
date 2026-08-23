import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { DateRange } from "@/components/ds/DateRange";

/**
 * A span of dates that cannot run backwards.
 *
 * <p>Every screen asking for one asked for two independent dates and hoped: leave from the 20th to
 * the 14th, an audit window running backwards, an invoice due before it was issued.
 */
describe("a pair of dates", () => {
  it("refuses an end before the start, and carries the end along when the start passes it", () => {
    render(
      <DateRange
        from={{ name: "fromDate", label: "First day", defaultValue: "2026-08-10" }}
        to={{ name: "toDate", label: "Last day", defaultValue: "2026-08-12" }}
      />
    );

    const start = screen.getByLabelText("First day");
    const end = screen.getByLabelText("Last day");

    // The browser itself refuses anything earlier, in every locale and on a phone.
    expect(end).toHaveAttribute("min", "2026-08-10");

    // Moving the start past the end moves the end with it — somebody who shifts the start of a
    // leave has shifted the leave, not created a contradiction to be caught on submit.
    fireEvent.change(start, { target: { value: "2026-08-20" } });
    expect(end).toHaveValue("2026-08-20");
    expect(end).toHaveAttribute("min", "2026-08-20");

    // Moving it back leaves the end where it is; only the floor moves.
    fireEvent.change(start, { target: { value: "2026-08-15" } });
    expect(end).toHaveValue("2026-08-20");
    expect(end).toHaveAttribute("min", "2026-08-15");
  });

  it("keeps the field names, so the forms that read themselves still work", () => {
    render(
      <DateRange from={{ name: "from", label: "From" }} to={{ name: "to", label: "To" }} />
    );
    expect(screen.getByLabelText("From")).toHaveAttribute("name", "from");
    expect(screen.getByLabelText("To")).toHaveAttribute("name", "to");
  });
});

describe("a portion, said the way a cook says it", () => {
  it("steps below a whole unit into the smaller one", async () => {
    const { portion } = await import("@/lib/format");
    expect(portion(0.2, "LITRES")).toBe("200 ml");
    expect(portion(0.1, "L")).toBe("100 ml");
    expect(portion(0.15, "KG")).toBe("150 gm");
    // A whole unit and above stays where it is — 1.5 litres reads perfectly well.
    expect(portion(1.5, "LITRES")).toBe("1.5 litres");
    expect(portion(3, "PIECES")).toBe("3 pieces");
    expect(portion(null, "LITRES")).toBe("—");
  });
});
