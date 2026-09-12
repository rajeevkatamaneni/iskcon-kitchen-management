import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { Button } from "@/components/ds/Button";

/**
 * A `Button` does not submit the form it sits in unless it says so (T-156).
 *
 * <p>The browser's default for a `<button>` is `type="submit"`, and `Button` used to pass that
 * default straight through. Every `Button` inside a `<form>` was therefore a save button, which is
 * how pressing "remove" on an order line also saved the order with the line still on it. The
 * default is now `"button"`, and a screen that means to submit writes `type="submit"`.
 *
 * <p>Pressed with `fireEvent.click`, not `fireEvent.submit`. A click is dispatched on the button and
 * jsdom's own activation behaviour decides whether the form submits, so the test proves what the
 * button does rather than calling the handler for it. `@testing-library/user-event` is not installed
 * in this project, and `fireEvent.click` is the real click that is available.
 *
 * <p>Enter in a text box cannot be pressed here: jsdom does not implement implicit submission. What
 * a browser does on Enter is defined in terms of the form's *default button*, the first submit
 * button in tree order, so the last test asserts that directly. It is the half of the old defect a
 * click never shows: a "remove" placed above the save button used to be the default button, so Enter
 * in any field of that form pressed "remove".
 */

function formWith(button: React.ReactNode) {
  const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
  const onReset = vi.fn();
  render(
    <form aria-label="An order" onSubmit={onSubmit} onReset={onReset}>
      <input aria-label="Quantity" defaultValue="4" />
      {button}
    </form>
  );
  return { onSubmit, onReset };
}

describe("Button inside a form", () => {
  it("does not submit when it gives no type", () => {
    const { onSubmit } = formWith(<Button variant="ghost">Remove this line</Button>);

    fireEvent.click(screen.getByRole("button", { name: /remove this line/i }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: /remove this line/i })).toHaveAttribute("type", "button");
  });

  it("submits when it says type=\"submit\"", () => {
    const { onSubmit } = formWith(<Button type="submit">Save order</Button>);

    fireEvent.click(screen.getByRole("button", { name: /save order/i }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("lets a caller's explicit type win over the default", () => {
    const { onSubmit, onReset } = formWith(<Button type="reset">Start again</Button>);

    fireEvent.click(screen.getByRole("button", { name: /start again/i }));

    expect(onReset).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("stays a plain button when a caller passes type={undefined}", () => {
    // The shape a wrapper produces when it forwards an optional `type` it was never given. Were the
    // default written ahead of `{...rest}`, this undefined would spread over it and the browser's
    // submit would be back.
    const { onSubmit } = formWith(<Button type={undefined}>Remove this line</Button>);

    fireEvent.click(screen.getByRole("button", { name: /remove this line/i }));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits a form it sits outside of, through form=, only when it says type=\"submit\"", () => {
    // Twenty-odd screens put the save button in the page header and point it at the form by id.
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(
      <>
        <Button form="order">Cancel</Button>
        <Button type="submit" form="order">
          Save order
        </Button>
        <form id="order" aria-label="An order" onSubmit={onSubmit}>
          <input aria-label="Quantity" defaultValue="4" />
        </form>
      </>
    );

    fireEvent.click(screen.getByRole("button", { name: /cancel/i }));
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: /save order/i }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it("leaves the save button, not an earlier remove, as the button Enter presses", () => {
    render(
      <form aria-label="An order" onSubmit={(e) => e.preventDefault()}>
        <input aria-label="Quantity" defaultValue="4" />
        <Button variant="ghost">Remove this line</Button>
        <Button type="submit">Save order</Button>
      </form>
    );
    const form = screen.getByRole("form", { name: /an order/i }) as HTMLFormElement;

    // The HTML spec's default button: the first submit button whose form owner is this form.
    const defaultButton = Array.from(form.elements).find(
      (el) => (el as HTMLButtonElement | HTMLInputElement).type === "submit"
    );

    expect(defaultButton).toBe(screen.getByRole("button", { name: /save order/i }));
  });
});
