import { describe, expect, it } from "vitest";
import { render, within } from "@testing-library/react";

import { ShiftFields } from "@/app/volunteers/shift-form";

/**
 * Every box on the shift form is named by its visible label, in all three places the form is used
 * (T-213).
 *
 * <p>The browser test of the meal rebuild found six of the eight boxes read as bare text boxes: a
 * screen reader heard "edit text" and nothing else. Those six sat inside a wrapping `<label>`; the one
 * that was named had a separate `<label htmlFor>` beside it. So this file asks two things of each box:
 *
 * <ol>
 *   <li>That it is found by its name, the way a screen reader finds it: `getByRole` with a name where
 *       the box has a role. A date box and a time box have no ARIA role in the mapping jsdom's
 *       queries use (a browser exposes them as its own date and time controls), so for those two the
 *       name is checked with `toHaveAccessibleName`, which runs the same name computation.</li>
 *   <li>That the name comes from a separate `<label>` pointing at the box's id, not from a label
 *       wrapped around it. This is the half that catches the defect: jsdom's name computation follows
 *       the spec and names a wrapped box, so a name check alone passed on the markup that the
 *       browser's accessibility tree left unnamed.</li>
 * </ol>
 */

const noop = () => {};

const LABELS = [
  "Title",
  "Date",
  "Volunteers requested",
  "Start time",
  "End time",
  "Location",
  "Reminder hours before",
  "Description",
] as const;

function form(container: HTMLElement, name: RegExp): HTMLFormElement {
  return within(container).getByRole("form", { name }) as HTMLFormElement;
}

/** The box a label names, found the way a screen reader finds it. */
function box(f: HTMLFormElement, label: (typeof LABELS)[number]): HTMLInputElement {
  const scope = within(f);
  switch (label) {
    case "Title":
    case "Location":
    case "Description":
    case "Reminder hours before":
      return scope.getByRole("textbox", { name: label }) as HTMLInputElement;
    case "Volunteers requested":
      return scope.getByRole("spinbutton", { name: label }) as HTMLInputElement;
    case "Date":
    case "Start time":
    case "End time": {
      // No ARIA role to query by (see the file comment), so the box is found by what it submits and
      // its computed accessible name is asserted instead.
      const field = { Date: "shiftDate", "Start time": "startTime", "End time": "endTime" }[label];
      const input = f.querySelector<HTMLInputElement>(`input[name="${field}"]`);
      expect(input, `${label} box`).not.toBeNull();
      expect(input).toHaveAccessibleName(label);
      return input as HTMLInputElement;
    }
  }
}

/** The name comes from a separate `<label for>` whose words are exactly the name. */
function expectExplicitLabel(f: HTMLFormElement, input: HTMLInputElement, label: string) {
  expect(input.id, `${label} has an id`).not.toBe("");
  const pointing = Array.from(f.querySelectorAll("label")).filter((l) => l.htmlFor === input.id);
  expect(pointing, `one label points at ${label}`).toHaveLength(1);
  expect(pointing[0].textContent?.trim()).toBe(label);
  expect(pointing[0].contains(input), `${label}'s label stands beside its box`).toBe(false);
}

function expectAllNamed(f: HTMLFormElement, labels: readonly (typeof LABELS)[number][] = LABELS) {
  for (const label of labels) {
    const input = box(f, label);
    expect(input).toHaveAccessibleName(label);
    expectExplicitLabel(f, input, label);
  }
}

describe("every shift form box is named by its label (T-213)", () => {
  it("Post a shift, blank", () => {
    const { container } = render(<ShiftFields editing={false} onSubmit={noop} />);
    expectAllNamed(form(container, /post a shift/i));
  });

  it("editing a plain shift, filled in", () => {
    const { container } = render(
      <ShiftFields
        editing
        onSubmit={noop}
        shift={{
          title: "Sunday prep",
          description: "Chopping",
          shiftDate: "2026-09-20",
          startTime: "20:00:00",
          endTime: "02:00:00",
          location: "Main kitchen",
          capacity: 4,
          reminderOffsetsMinutes: [1440],
        }}
      />
    );
    const f = form(container, /edit a shift/i);
    expectAllNamed(f);
    // The overnight line sits under End time. It explains the box; it is not part of its name.
    expect(within(f).getByText(/ends the next day/i)).toBeInTheDocument();
    expect(box(f, "End time")).toHaveAccessibleName("End time");
  });

  it("the planner's layer: the fixed date is named Date and described by its line", () => {
    const { container } = render(
      <ShiftFields
        editing={false}
        onSubmit={noop}
        fixedDate="2026-09-15"
        shift={{ title: "Help for Lunch", capacity: 3, endTime: "12:00:00" }}
      />
    );
    const f = form(container, /post a shift/i);
    expectAllNamed(f);

    const date = box(f, "Date");
    expect(date).toHaveAttribute("readonly");
    expect(date).toHaveValue("2026-09-15");
    expect(date).toHaveAccessibleName("Date");
    expect(date).toHaveAccessibleDescription(/the day of the meal\. it cannot be changed here\./i);
  });

  it("a meal shift on the edit page has no date box, and no label pointing at nothing", () => {
    const { container } = render(
      <ShiftFields
        editing
        onSubmit={noop}
        meal={{ mealId: "m1", name: "Lunch", date: "2026-09-15" }}
        shift={{ title: "Help for Lunch", shiftDate: "2026-09-15", startTime: "10:00:00", endTime: "12:00:00" }}
      />
    );
    const f = form(container, /edit a shift/i);
    expect(f.querySelector('input[name="shiftDate"]')).toBeNull();
    expectAllNamed(
      f,
      LABELS.filter((l) => l !== "Date")
    );
    for (const label of Array.from(f.querySelectorAll("label"))) {
      expect(label.htmlFor, `label "${label.textContent}" points at a box`).not.toBe("");
      expect(document.getElementById(label.htmlFor), `label "${label.textContent}"'s box`).not.toBeNull();
    }
  });

  it("two forms on one page share no box ids, and each label names the box in its own form", () => {
    const { container } = render(
      <>
        <ShiftFields editing={false} onSubmit={noop} />
        <ShiftFields editing={false} onSubmit={noop} fixedDate="2026-09-15" />
      </>
    );
    const forms = Array.from(container.querySelectorAll("form"));
    expect(forms).toHaveLength(2);

    // The ids inside the forms. The form element's own id is left out on purpose: it is the fixed
    // SHIFT_FORM that the page's header button submits by (`form="shift-form"`), and changing it is
    // outside this task. See docs/work/proof/T-213.md.
    const ids = forms.flatMap((f) => Array.from(f.querySelectorAll("[id]")).map((el) => el.id));
    expect(ids.length).toBeGreaterThanOrEqual(2 * 7);
    expect(new Set(ids).size).toBe(ids.length);

    for (const f of forms) expectAllNamed(f);
  });
});
