import { render, screen, act, fireEvent } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { FieldRow } from "@/components/ds/FieldRow";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { Field, FIELD_LABEL } from "@/components/Field";

describe("Field", () => {
  it("indents the label and the error to the same line as the value", () => {
    const { container } = render(
      <Field id="gstin" label="GSTIN" hint="Fifteen characters" error="That is not a valid GSTIN">
        {(props) => <input {...props} />}
      </Field>
    );
    for (const el of [container.querySelector("label"), container.querySelector("#gstin-error")]) {
      expect(el?.className).toContain("pl-field-inset");
    }
  });

  it("puts the hint in an “i” beside the label rather than a line under the box", () => {
    const { container } = render(
      <Field id="gstin" label="GSTIN" hint="Fifteen characters">
        {(props) => <input {...props} />}
      </Field>
    );

    // Nothing under the box, and the label still names the control rather than the button beside
    // it — the association a <label> wrapped around the "i" would have quietly taken away.
    expect(container.querySelector("#gstin-hint")).toBeNull();
    expect(screen.getByLabelText("GSTIN", { selector: "input" })).toHaveAttribute("id", "gstin");

    const info = screen.getByRole("button", { name: "More about GSTIN" });
    expect(screen.queryByText("Fifteen characters")).toBeNull();
    fireEvent.mouseOver(info);
    expect(screen.getByRole("tooltip")).toHaveTextContent("Fifteen characters");
  });

  /*
   * T-174. `required` used to print "(required)" beside the label and never reach the box, so the
   * browser refused nothing and `Form` had nothing to name. The props are captured as well as the
   * rendered box, because "not required" has to mean the key is absent: an explicit
   * `required: undefined` renders the same box here but would take away the `required` of a control
   * that sets its own before spreading these props.
   */
  it("puts required on the box when the field is required, and still says so beside the label", () => {
    let handed: Record<string, unknown> = {};
    render(
      <Field id="temple-name" label="Name" required>
        {(props) => {
          handed = { ...props };
          return <input {...props} />;
        }}
      </Field>
    );

    expect(screen.getByLabelText(/^name/i, { selector: "input" })).toBeRequired();
    expect(handed.required).toBe(true);
    expect(screen.getByText("(required)")).toBeInTheDocument();
  });

  it("leaves required off the box, and out of the props, when the field is not required", () => {
    let handed: Record<string, unknown> = {};
    render(
      <Field id="temple-address" label="Address">
        {(props) => {
          handed = { ...props };
          return <input {...props} />;
        }}
      </Field>
    );

    expect(screen.getByLabelText("Address", { selector: "input" })).not.toBeRequired();
    expect(Object.keys(handed)).not.toContain("required");
    expect(screen.queryByText("(required)")).toBeNull();
  });

  it("does not take away a required the control sets for itself before the spread", () => {
    render(
      <Field id="temple-note" label="Note">
        {(props) => <input required {...props} />}
      </Field>
    );

    expect(screen.getByLabelText("Note", { selector: "input" })).toBeRequired();
  });

  it("sets the label a step darker and a step heavier than its hint", () => {
    expect(FIELD_LABEL).toContain("font-medium");
    expect(FIELD_LABEL).toContain("text-ink");
    expect(FIELD_LABEL).not.toContain("text-ink-secondary");
  });
});

describe("FieldRow", () => {
  it("gives every child the row's tracks, so a caller cannot opt out", () => {
    const { container } = render(
      <FieldRow>
        <span data-testid="a">a</span>
        <span data-testid="b">b</span>
      </FieldRow>
    );
    const cells = container.querySelectorAll("[data-field-row-cell]");
    expect(cells).toHaveLength(2);
    cells.forEach((cell) => {
      expect(cell.className).toContain("grid-rows-subgrid");
      expect(cell.className).toContain("row-span-2");
    });
  });

  it("does not give a column to a field that did not render", () => {
    // Half of these rows are conditional — {kind?.isEvent && <Field/>} — and a falsy child
    // taking a track would leave a hole where the field it stands for would have been.
    const show = false;
    const { container } = render(
      <FieldRow>
        <span>a</span>
        {show && <span>b</span>}
        {null}
        <span>c</span>
      </FieldRow>
    );
    expect(container.querySelectorAll("[data-field-row-cell]")).toHaveLength(2);
  });
});

describe("InlineNotice", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("clears a confirmation after five seconds", () => {
    render(
      <InlineNotice tone="success" autoDismiss>
        Payment recorded
      </InlineNotice>
    );
    expect(screen.getByText("Payment recorded")).toBeTruthy();
    act(() => void vi.advanceTimersByTime(5_200));
    expect(screen.queryByText("Payment recorded")).toBeNull();
  });

  it("never clears a warning or an error, whatever the caller asks for", () => {
    // The person still has something to do about these, so the prop is refused rather than obeyed.
    for (const tone of ["warning", "danger"] as const) {
      const { unmount } = render(
        <InlineNotice tone={tone} autoDismiss>
          Still here
        </InlineNotice>
      );
      act(() => void vi.advanceTimersByTime(30_000));
      expect(screen.getByText("Still here")).toBeTruthy();
      unmount();
    }
  });

  it("leaves a notice alone unless it was asked to clear it", () => {
    render(<InlineNotice tone="success">Standing</InlineNotice>);
    act(() => void vi.advanceTimersByTime(30_000));
    expect(screen.getByText("Standing")).toBeTruthy();
  });
});
