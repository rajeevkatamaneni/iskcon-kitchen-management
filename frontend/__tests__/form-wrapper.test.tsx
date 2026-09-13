import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { Field } from "@/components/Field";
import { Form } from "@/components/ds/Form";
import { messageFor, type ControlFacts } from "@/components/ds/formMessages";

/**
 * The shared form wrapper: a sentence in red beside every box the browser refuses (T-160).
 *
 * <p>Rajeev, 2026-09-11: *"Ideally, we should say 'Quantity is required' OR 'Note is required'."*
 *
 * <p><b>What jsdom does and does not do, established before these were written</b>, because two of
 * the proofs below depend on it:
 *
 * <ul>
 *   <li>jsdom <b>enforces constraint validation on a button-driven submit</b>. Clicking a submit
 *       button in a form without `novalidate` whose required box is empty fires no submit event at
 *       all — for a button inside the form and for one outside it with `form="…"` alike. So every
 *       blank-submit test here clicks a button rather than calling `fireEvent.submit`, and a form
 *       that lost `noValidate` goes red in this file: the submit never reaches the wrapper, so no
 *       sentence ever appears.</li>
 *   <li>jsdom <b>never reports `badInput`</b>: assigning "1e" to a number box stores "" instead. And
 *       neither jsdom nor a browser reports `tooLong` for a value set by code — the spec only
 *       applies it to a value a person edited. Those two are proved by giving one element a
 *       `validity` of its own (the wrapper reads `validity`, so it sees it), and by testing the
 *       sentence mapping directly.</li>
 * </ul>
 */

const VALID: ValidityState = {
  valid: true,
  valueMissing: false,
  typeMismatch: false,
  patternMismatch: false,
  tooLong: false,
  tooShort: false,
  rangeUnderflow: false,
  rangeOverflow: false,
  stepMismatch: false,
  badInput: false,
  customError: false,
};

/** Gives one element a refusal jsdom cannot produce on its own. Labelled as simulated wherever used. */
function simulateValidity(el: HTMLElement, refusal: Partial<ValidityState>) {
  Object.defineProperty(el, "validity", {
    configurable: true,
    get: () => ({ ...VALID, valid: false, ...refusal }),
  });
}

/** The sentence for a box, asserted as red, as described by the box, and as sitting right after `anchor`. */
function expectSentence(control: HTMLElement, sentence: string, anchor: HTMLElement = control) {
  const message = screen.getByText(sentence);
  expect(message).toHaveClass("text-danger");
  expect(control).toHaveAttribute("aria-invalid", "true");
  expect(control.getAttribute("aria-describedby")?.split(" ")).toContain(message.id);
  expect(control).toHaveAccessibleDescription(sentence);
  expect(anchor.nextElementSibling).toContainElement(message);
}

/** One box on its own, with a submit button, for the per-check sentences. */
function renderOne(input: React.ReactNode) {
  const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
  render(
    <Form aria-label="Test form" onSubmit={onSubmit}>
      {input}
      <button type="submit">Save</button>
    </Form>
  );
  return { onSubmit, save: () => fireEvent.click(screen.getByRole("button", { name: "Save" })) };
}

/** A Field-based box, a hand-rolled box named by aria-label, and one wrapped in its label. */
function ThreeKinds({ onSubmit }: { onSubmit: (e: React.FormEvent<HTMLFormElement>) => void }) {
  const [quantity, setQuantity] = useState("");
  const [note, setNote] = useState("");
  const [donor, setDonor] = useState("");
  return (
    <Form aria-label="Three kinds" onSubmit={onSubmit}>
      <Field id="quantity" label="Quantity" required>
        {(props) => (
          <input {...props} type="number" required min={1} value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        )}
      </Field>
      <textarea aria-label="Note" required value={note} onChange={(e) => setNote(e.target.value)} />
      <label>
        Donor name
        <input type="text" required value={donor} onChange={(e) => setDonor(e.target.value)} />
      </label>
      <button type="submit">Save</button>
    </Form>
  );
}

describe("Form wrapper", () => {
  it("names every blank required box in red beside it, focuses the first, and does not submit", () => {
    const onSubmit = vi.fn();
    render(<ThreeKinds onSubmit={onSubmit} />);

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const quantity = screen.getByRole("spinbutton");
    const note = screen.getByRole("textbox", { name: "Note" });
    const donor = screen.getByRole("textbox", { name: "Donor name" });

    // Field's own "(required)" marker is dropped from the name; the Field box's slot follows the box.
    expectSentence(quantity, "Quantity is required");
    expect(screen.queryByText(/\(required\) is required/)).not.toBeInTheDocument();
    // Named by aria-label; the slot follows the box.
    expectSentence(note, "Note is required");
    // Named by its wrapping label; the slot follows the label, so the sentence is not in the name.
    expectSentence(donor, "Donor name is required", donor.closest("label")!);
    expect(donor).toHaveAccessibleName("Donor name");

    expect(quantity).toHaveFocus();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("says a 0 in a min=1 box must be at least 1", () => {
    const { onSubmit, save } = renderOne(<input aria-label="Quantity" type="number" min={1} defaultValue="0" />);
    save();
    expectSentence(screen.getByRole("spinbutton"), "Quantity must be at least 1");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("says a number over max can be at most max", () => {
    const { save } = renderOne(<input aria-label="Quantity" type="number" min={1} max={500} defaultValue="501" />);
    save();
    expectSentence(screen.getByRole("spinbutton"), "Quantity can be at most 500");
  });

  // Step tests type their value rather than using defaultValue. jsdom checks the step only of a value
  // set through the property, while a browser checks any value, so a defaultValue here would pass
  // without being step-checked at all. A person typing is the real case anyway.
  it("says a step of 0.01 allows two decimal places", () => {
    const { save } = renderOne(<input aria-label="Amount" type="number" step="0.01" />);
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "1.005" } });
    save();
    expectSentence(screen.getByRole("spinbutton"), "Amount can have at most 2 decimal places");
  });

  it("says a step of 1 wants a whole number, and so does a number box with no step", () => {
    const { save } = renderOne(
      <>
        <input aria-label="Servings" type="number" step="1" />
        <input aria-label="Day" type="number" />
      </>
    );
    fireEvent.change(screen.getByRole("spinbutton", { name: "Servings" }), { target: { value: "1.5" } });
    fireEvent.change(screen.getByRole("spinbutton", { name: "Day" }), { target: { value: "2.5" } });
    save();
    expectSentence(screen.getByRole("spinbutton", { name: "Servings" }), "Servings must be a whole number");
    expectSentence(screen.getByRole("spinbutton", { name: "Day" }), "Day must be a whole number");
  });

  it("says step any never refuses", () => {
    const { onSubmit, save } = renderOne(<input aria-label="Weight" type="number" step="any" />);
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "1.23456" } });
    save();
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("says too long in characters (tooLong simulated — no browser reports it for a value set by code)", () => {
    const { save } = renderOne(<input aria-label="Note" maxLength={10} defaultValue="far too long a note" />);
    simulateValidity(screen.getByRole("textbox"), { tooLong: true });
    save();
    expectSentence(screen.getByRole("textbox"), "Note can be at most 10 characters");
  });

  it("gives an example of an email address", () => {
    const { save } = renderOne(<input aria-label="Email" type="email" defaultValue="rajeev" />);
    save();
    expectSentence(screen.getByRole("textbox"), "Enter an email address like name@example.com");
  });

  it("prints date limits day-first with the month's name, never as 2026-09-13", () => {
    const { save } = renderOne(
      <>
        <input aria-label="Start date" type="date" min="2026-09-13" defaultValue="2026-09-01" />
        <input aria-label="End date" type="date" max="2026-09-30" defaultValue="2026-10-02" />
      </>
    );
    save();
    // "Sep" or "Sept": dateWithYear pins en-GB and the ICU data decides the abbreviation.
    const after = screen.getByText(/^Start date must be on or after 13 Sept? 2026$/);
    const before = screen.getByText(/^End date must be on or before 30 Sept? 2026$/);
    expect(after).toHaveClass("text-danger");
    expect(before).toHaveClass("text-danger");
    expect(screen.queryByText(/2026-09/)).not.toBeInTheDocument();
  });

  it("says a time before min or after max in HH:mm", () => {
    const { save } = renderOne(
      <>
        <input aria-label="Ready by" type="time" min="06:00" defaultValue="05:30" />
        <input aria-label="Finish by" type="time" max="22:00" defaultValue="23:15" />
      </>
    );
    save();
    expect(screen.getByText("Ready by must be 06:00 or later")).toBeInTheDocument();
    expect(screen.getByText("Finish by must be 22:00 or earlier")).toBeInTheDocument();
  });

  it("says a half-typed number and a half-typed date (badInput simulated — jsdom never reports it)", () => {
    const { onSubmit, save } = renderOne(
      <>
        <input aria-label="Quantity" type="number" />
        <input aria-label="Start date" type="date" />
      </>
    );
    simulateValidity(screen.getByRole("spinbutton"), { badInput: true });
    simulateValidity(screen.getByLabelText("Start date"), { badInput: true });
    save();
    expectSentence(screen.getByRole("spinbutton"), "Quantity must be a number");
    expectSentence(screen.getByLabelText("Start date"), "Start date must be a complete date");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("checks a submit from a button outside the form that names it with form=", () => {
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(
      <>
        <Form id="outside-form" aria-label="Outside" onSubmit={onSubmit}>
          <input aria-label="Vendor name" required />
        </Form>
        <button type="submit" form="outside-form">
          Create vendor
        </button>
      </>
    );

    fireEvent.click(screen.getByRole("button", { name: "Create vendor" }));
    expectSentence(screen.getByRole("textbox"), "Vendor name is required");
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.change(screen.getByRole("textbox"), { target: { value: "Sri Balaji Traders" } });
    fireEvent.click(screen.getByRole("button", { name: "Create vendor" }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("clears a box's sentence once it is put right, and gives the box back as it was", () => {
    render(<ThreeKinds onSubmit={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    const quantity = screen.getByRole("spinbutton");
    const note = screen.getByRole("textbox", { name: "Note" });

    // Wrong in a new way first: the sentence follows the box's current problem.
    fireEvent.change(quantity, { target: { value: "0" } });
    expect(screen.getByText("Quantity must be at least 1")).toBeInTheDocument();
    expect(screen.queryByText("Quantity is required")).not.toBeInTheDocument();

    fireEvent.change(quantity, { target: { value: "3" } });
    expect(screen.queryByText(/^Quantity /)).not.toBeInTheDocument();
    // Field rendered aria-invalid="false"; that is what it gets back.
    expect(quantity).toHaveAttribute("aria-invalid", "false");
    expect(quantity).not.toHaveAttribute("aria-describedby");

    fireEvent.change(note, { target: { value: "Leave at the back gate" } });
    expect(screen.queryByText("Note is required")).not.toBeInTheDocument();
    // The hand-rolled box had no aria-invalid of its own, so none is left behind.
    expect(note).not.toHaveAttribute("aria-invalid");
    expect(note).not.toHaveAttribute("aria-describedby");

    // The box nobody touched still says so.
    expect(screen.getByText("Donor name is required")).toBeInTheDocument();
  });

  it("calls onSubmit once for a valid form, and leaves disabled boxes out of it", () => {
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(
      <Form aria-label="Valid" onSubmit={onSubmit}>
        <input aria-label="Quantity" type="number" required min={1} defaultValue="4" />
        <input aria-label="Reason" required disabled />
        <button type="submit">Save</button>
      </Form>
    );
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/is required/)).not.toBeInTheDocument();
  });

  it("keeps exactly one sentence beside its box while the form re-renders around it", () => {
    function Busy() {
      const [other, setOther] = useState("");
      const [showQuantity, setShowQuantity] = useState(true);
      return (
        <Form aria-label="Busy" onSubmit={vi.fn()}>
          <div>
            {/* Appears only once somebody types, so React inserts it next to the refused box. */}
            {other && <span>Typed {other}</span>}
            {showQuantity && <input aria-label="Quantity" required />}
            {other && <span>After</span>}
          </div>
          <input aria-label="Other" value={other} onChange={(e) => setOther(e.target.value)} />
          <button type="button" onClick={() => setShowQuantity(false)}>
            Hide quantity
          </button>
          <button type="submit">Save</button>
        </Form>
      );
    }
    render(<Busy />);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    const quantity = screen.getByRole("textbox", { name: "Quantity" });
    expectSentence(quantity, "Quantity is required");

    fireEvent.change(screen.getByRole("textbox", { name: "Other" }), { target: { value: "x" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Other" }), { target: { value: "xy" } });
    expect(screen.getAllByText("Quantity is required")).toHaveLength(1);
    expectSentence(quantity, "Quantity is required");
    expect(screen.getByText("After")).toBeInTheDocument();

    // The box unmounts: its sentence goes with it, rather than being left floating.
    fireEvent.click(screen.getByRole("button", { name: "Hide quantity" }));
    expect(screen.queryByText("Quantity is required")).not.toBeInTheDocument();
  });
});

/**
 * The name a wrapping `<label>` gives its box when the label holds more than the question (T-171).
 *
 * <p>Each fixture copies a real screen's markup and classes, because the classes are what `Form`
 * reads: a question is `text-ink` or uncoloured, a hint or note `text-ink-secondary` or
 * `text-ink-muted`, an error `text-danger`. Before T-171 the first fixture read "Serviced onA service
 * dated next Tuesday has not happened yet. is required".
 */
describe("a label's name, without its hint, error or note", () => {
  it("a question span and a hint span: the question alone (Record a service)", () => {
    const { save } = renderOne(
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Serviced on</span>
        <input name="servicedOn" type="date" required />
        <span className="pl-field-inset text-sm text-ink-secondary">
          A service dated next Tuesday has not happened yet.
        </span>
      </label>
    );
    save();
    const box = screen.getByLabelText(/serviced on/i);
    expectSentence(box, "Serviced on is required", box.closest("label")!);
  });

  it("a question span and an inline error: the question alone (a kitchen's duplicate name)", () => {
    const { save } = renderOne(
      <label className="flex flex-col gap-1 text-sm text-ink-secondary">
        <span className="pl-field-inset font-medium text-ink">Name</span>
        <input required />
        <span className="pl-field-inset text-danger">Another kitchen here already goes by this name.</span>
      </label>
    );
    save();
    const box = screen.getByRole("textbox");
    expectSentence(box, "Name is required", box.closest("label")!);
  });

  it("plain text only, in a label coloured as secondary itself: the text (the label's own colour does not count)", () => {
    const { save } = renderOne(
      <label className="flex items-center gap-2 text-sm text-ink-secondary">
        <input type="checkbox" required />
        Direct, with no purchase order
      </label>
    );
    save();
    const box = screen.getByRole("checkbox");
    expectSentence(box, "Direct, with no purchase order is required", box.closest("label")!);
  });

  it("a tick-box's words and the grey note after them: the words alone (meal kinds)", () => {
    const { save } = renderOne(
      <label className="flex items-baseline gap-2 text-sm">
        <input type="checkbox" required />
        <span>
          <span className="text-ink">This kind of meal is a feast</span>{" "}
          <span className="text-ink-muted">it must name the festival it is for, such as Janmastami</span>
        </span>
      </label>
    );
    save();
    const box = screen.getByRole("checkbox");
    expectSentence(box, "This kind of meal is a feast is required", box.closest("label")!);
  });

  it("a label whose only words are coloured as a note keeps them, rather than being left nameless", () => {
    const { save } = renderOne(
      <label>
        <span className="text-ink-secondary">Reference</span>
        <input required />
      </label>
    );
    save();
    const box = screen.getByRole("textbox");
    expectSentence(box, "Reference is required", box.closest("label")!);
  });
});

describe("form sentences", () => {
  const facts = (over: Omit<Partial<ControlFacts>, "validity"> & { validity?: Partial<ValidityState> }): ControlFacts => ({
    type: "text",
    min: "",
    max: "",
    step: "",
    maxLength: -1,
    ...over,
    validity: { ...VALID, ...over.validity },
  });

  it.each([
    ["required", facts({ validity: { valueMissing: true } }), "Quantity is required"],
    ["half-typed number", facts({ type: "number", validity: { badInput: true } }), "Quantity must be a number"],
    ["half-typed date", facts({ type: "date", validity: { badInput: true } }), "Quantity must be a complete date"],
    ["half-typed time", facts({ type: "time", validity: { badInput: true } }), "Quantity must be a complete time"],
    ["too long", facts({ maxLength: 80, validity: { tooLong: true } }), "Quantity can be at most 80 characters"],
    ["step 0.01", facts({ type: "number", step: "0.01", validity: { stepMismatch: true } }), "Quantity can have at most 2 decimal places"],
    ["step 0.1", facts({ type: "number", step: "0.1", validity: { stepMismatch: true } }), "Quantity can have at most 1 decimal place"],
    ["step 1", facts({ type: "number", step: "1", validity: { stepMismatch: true } }), "Quantity must be a whole number"],
    ["unused step", facts({ type: "number", step: "5", validity: { stepMismatch: true } }), "Quantity must go up in steps of 5"],
    ["unmapped refusal", facts({ validity: { patternMismatch: true } }), "Quantity is not valid"],
  ])("%s", (_kind, f, sentence) => {
    expect(messageFor("Quantity", f)).toBe(sentence);
  });
});
