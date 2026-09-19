import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { PurchaseOrderEditor, revealNotice, type PurchaseOrderDraftLine } from "@/components/PurchaseOrderEditor";

/**
 * A refusal from the purchase-order edit form is brought into view and given focus (T-304).
 *
 * <p>Measured at 390 before this: the order page draws a refusal at the top of the page and Save is
 * at the foot of the form, so an emptied order refused off-screen, 1,000px or more above where the
 * person was looking. jsdom has no layout, so what is asserted here is the behaviour that decides
 * it: the notice the host drew is the one scrolled to (centred) and focused, it can hold focus
 * without becoming a tab stop, and a save that succeeded moves nothing. The positions themselves
 * were measured in a browser and are in `docs/work/proof/T-304.md`.
 */

const scrolled: { el: Element; arg: unknown }[] = [];
beforeEach(() => {
  scrolled.length = 0;
  Element.prototype.scrollIntoView = function (this: Element, arg?: unknown) {
    scrolled.push({ el: this, arg });
  } as Element["scrollIntoView"];
});
afterEach(() => {
  // @ts-expect-error jsdom has none of its own; put it back the way it was.
  delete Element.prototype.scrollIntoView;
});

const RICE: PurchaseOrderDraftLine = {
  key: "l1",
  ingredientId: "i1",
  ingredientName: "Rice",
  description: null,
  quantity: "5",
  unit: "KG",
  expectedPrice: null,
};

/** A host shaped like the order page: its own notice at the top, the form under it. */
function Host({ save }: { save: (setError: (m: string) => void) => Promise<boolean> }) {
  const [refusal, setRefusal] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(true);
  return (
    <div>
      {refusal && <div role="alert"><p>{refusal}</p></div>}
      {error && <div role="alert"><p>{error}</p></div>}
      {!editing && <p>Saved.</p>}
      {editing && (
        <PurchaseOrderEditor
          words={{ heading: "Edit this draft", formLabel: "Edit the draft order", emptyOrder: "An order needs at least one line.", dateBeforeFloor: "Too early." }}
          initialLines={[RICE]}
          initialNeededBy=""
          minNeededBy="2026-01-01"
          ingredients={[]}
          busy={false}
          onSave={async () => {
            setRefusal(null);
            setError(null);
            if (await save(setError)) setEditing(false);
          }}
          onCancel={() => setEditing(false)}
          onRefuse={(m) => {
            setError(null);
            setRefusal(m);
          }}
        />
      )}
    </div>
  );
}

describe("a refusal from the edit form is shown where the person is (T-304)", () => {
  it("scrolls the form's own refusal into view, centred, and moves focus to it", async () => {
    render(<Host save={async () => true} />);
    fireEvent.click(screen.getByRole("button", { name: /remove rice/i }));
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: "Edit the draft order" }));
    });
    const alert = screen.getByRole("alert");
    await waitFor(() => expect(alert).toHaveFocus());
    expect(alert).toHaveTextContent("An order needs at least one line.");
    expect(scrolled).toEqual([{ el: alert, arg: { block: "center" } }]);
    // Focusable by script, never a stop when tabbing through the page.
    expect(alert.getAttribute("tabindex")).toBe("-1");
  });

  it("does the same for a refusal from the server, which the host draws after the save returns", async () => {
    render(<Host save={async (setError) => (setError("We couldn’t save those changes."), false)} />);
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: "Edit the draft order" }));
    });
    const alert = await screen.findByRole("alert");
    await waitFor(() => expect(alert).toHaveFocus());
    expect(alert).toHaveTextContent("We couldn’t save those changes.");
    expect(scrolled.map((s) => s.el)).toEqual([alert]);
  });

  it("moves nothing when the save succeeded and the form closed", async () => {
    render(<Host save={async () => true} />);
    await act(async () => {
      fireEvent.submit(screen.getByRole("form", { name: "Edit the draft order" }));
    });
    expect(screen.getByText("Saved.")).toBeInTheDocument();
    // Longer than revealNotice would ever go on looking.
    await act(() => new Promise((r) => setTimeout(r, 400)));
    expect(scrolled).toEqual([]);
    expect(document.body).toHaveFocus();
  });

  it("looks only inside the panel when the form is in one, never at a notice on the page behind", async () => {
    render(
      <div>
        <div role="alert" id="behind"><p>Something on the page behind.</p></div>
        <div role="dialog" aria-label="Purchase order">
          <div role="alert" id="mine"><p>An order needs at least one line.</p></div>
        </div>
      </div>
    );
    revealNotice(screen.getByRole("dialog"));
    const mine = document.getElementById("mine")!;
    await waitFor(() => expect(mine).toHaveFocus());
    expect(scrolled.map((s) => s.el)).toEqual([mine]);
  });

  it("never picks Next.js's route announcer", async () => {
    render(
      <div>
        <div role="alert" id="__next-route-announcer__">Page</div>
        <div role="alert" id="real"><p>Refused.</p></div>
      </div>
    );
    revealNotice();
    await waitFor(() => expect(document.getElementById("real")).toHaveFocus());
  });
});
