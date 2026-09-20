import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import type { DeliveryLineView } from "@/lib/api";

/**
 * A counted box will not take a fraction, on the screens (T-424).
 *
 * <p>Two of them, chosen because between them they cover both halves of the task: the delivery panel
 * is **outside** the shared `<Form>` and says the sentence itself, and the planner's kitchen band is
 * **inside** one and lets `Form` say it from `stepMismatch`. The other screens are proved in their
 * own files, beside the rest of their behaviour.
 *
 * <p><b>Why every fractional value here is typed rather than prefilled.</b> jsdom checks a number
 * box's step only for a value set through the property, not for a `defaultValue` — the note at the
 * top of `form-wrapper.test.tsx` establishes this. A prefilled 1.5 would therefore sail through and
 * the test would pass while proving nothing. The one place a prefilled figure IS asserted is the
 * "still shows what is already on file" test, which is about what the box renders and not about
 * whether it is refused.
 */

const { api, auth } = vi.hoisted(() => ({
  api: { recordDelivery: vi.fn() },
  auth: {
    status: "signed-in",
    appUser: { role: "KITCHEN_MANAGER", userId: "me" },
    getToken: async () => "test-token",
  },
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => auth }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, ...api } };
});

import { Form } from "@/components/ds/Form";
import { KitchenBand, type BandDish } from "@/components/planner/KitchenSections";
import { RecordDeliveryPanel } from "@/components/RecordDeliveryPanel";

const TODAY = "2026-09-19";

function line(o: Partial<DeliveryLineView>): DeliveryLineView {
  return {
    poLineId: "l1",
    poId: "p41",
    poNumber: "PO-0041",
    vendorId: "v1",
    vendorName: "Sri Balaji Traders",
    ingredientId: "i1",
    itemName: "Toor dal",
    unit: "KG",
    orderedQty: 50,
    receivedQty: 0,
    rejectedQty: 0,
    returnedQty: 0,
    stillToCome: 50,
    neededBy: "2026-09-17",
    completedOn: null,
    packLabel: null,
    packQuantity: null,
    packCount: null,
    parts: [],
    ...o,
  };
}

/** The defect's own items: an apron and a broom are counted, toor dal is weighed. */
const APRONS = line({ poLineId: "a1", ingredientId: "i9", itemName: "Apron", unit: "PIECES", orderedQty: 90, stillToCome: 90 });
const DAL = line({});
/** Ordered as 4 × Bag (25 Kg): weighed in stock, but counted in whole bags at the gate. */
const RICE = line({
  poLineId: "r1",
  ingredientId: "i2",
  itemName: "Rice, Sona Masoori",
  orderedQty: 100,
  stillToCome: 100,
  packLabel: "Bag (25 Kg)",
  packQuantity: 25,
  packCount: 4,
});

function panel(lines: DeliveryLineView[]) {
  const onSaved = vi.fn();
  render(
    <RecordDeliveryPanel
      vendorId="v1"
      vendorName="Sri Balaji Traders"
      lines={lines}
      today={TODAY}
      onCancel={vi.fn()}
      onSaved={onSaved}
    />,
  );
  /*
   * `save()` awaits a token before it calls the API, so the call lands in a microtask. Asserting
   * straight after the click would read the spy before it was ever touched.
   */
  const save = async () =>
    act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save delivery" }));
    });
  return { onSaved, save };
}

function box(name: RegExp) {
  return screen.getByRole("spinbutton", { name });
}

describe("recording a delivery — the sentence this screen says for itself", () => {
  // The spy is module-level and these tests assert on call counts, so it is reset per test.
  beforeEach(() => api.recordDelivery.mockClear());

  it("steps a counted line by 1 and a weighed line by any", () => {
    panel([APRONS, DAL]);
    expect(box(/Apron received now/)).toHaveAttribute("step", "1");
    expect(box(/Toor dal received now/)).toHaveAttribute("step", "any");
  });

  /*
   * Ordering is where a vendor sells whole bags. Receiving is not: part of a bag comes off a van,
   * and this screen has said "2.8 bags (70 Kg)" on purpose since T-266. Stepping the pack box by 1
   * would have left the gate unable to write down what actually arrived.
   */
  it("leaves a pack line free, because part of a bag really does arrive", () => {
    panel([RICE]);
    expect(box(/Rice, Sona Masoori received now/)).toHaveAttribute("step", "any");
  });

  /*
   * So for a counted ingredient sold in packs the rule lands on the stock amount instead: 2.8 boxes
   * of a dozen aprons is 33.6 aprons, and it is the aprons that cannot be fractional.
   */
  it("refuses part of a pack when the pack holds counted things", async () => {
    const BOXED = line({ ...APRONS, poLineId: "b1", itemName: "Apron", unit: "PIECES",
      orderedQty: 120, stillToCome: 120, packLabel: "Box (12)", packQuantity: 12, packCount: 10 });
    const { save } = panel([BOXED]);
    fireEvent.change(box(/Apron received now/), { target: { value: "2.8" } });
    await save();
    expect(screen.getByText("Apron received now must be a whole number")).toHaveClass("text-danger");
    expect(api.recordDelivery).not.toHaveBeenCalled();
  });

  it("takes a whole number of boxes of a counted thing", async () => {
    const BOXED = line({ ...APRONS, poLineId: "b1", itemName: "Apron", unit: "PIECES",
      orderedQty: 120, stillToCome: 120, packLabel: "Box (12)", packQuantity: 12, packCount: 10 });
    const { save } = panel([BOXED]);
    fireEvent.change(box(/Apron received now/), { target: { value: "3" } });
    await save();
    expect(screen.queryByText(/must be a whole number/)).toBeNull();
    expect(api.recordDelivery).toHaveBeenCalledTimes(1);
  });

  it("refuses 1.5 aprons in the words every other box uses, and does not save", async () => {
    const { onSaved, save } = panel([APRONS]);
    fireEvent.change(box(/Apron received now/), { target: { value: "1.5" } });
    await save();
    expect(screen.getByText("Apron received now must be a whole number")).toHaveClass("text-danger");
    expect(box(/Apron received now/)).toHaveAttribute("aria-invalid", "true");
    expect(api.recordDelivery).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it("says the same thing about the rejected box, which had no sentence of its own before", async () => {
    const { save } = panel([APRONS]);
    fireEvent.change(box(/Apron received now/), { target: { value: "10" } });
    fireEvent.change(box(/Apron rejected on delivery/), { target: { value: "0.5" } });
    await save();
    expect(screen.getByText("Apron rejected on delivery must be a whole number")).toHaveClass("text-danger");
    expect(api.recordDelivery).not.toHaveBeenCalled();
  });

  /*
   * The negative control's other half. This asserts an absence, so it would pass on its own if the
   * rule were deleted entirely — which is why the counted tests above sit beside it and why the
   * proof file records breaking `isCountedUnit` and watching exactly those fail.
   */
  it("still takes 1.5 Kg of dal, because a weight is not a count", async () => {
    const { save } = panel([DAL]);
    fireEvent.change(box(/Toor dal received now/), { target: { value: "1.5" } });
    await save();
    expect(screen.queryByText(/must be a whole number/)).toBeNull();
    expect(api.recordDelivery).toHaveBeenCalledTimes(1);
  });

  /*
   * T-424 is explicit that history is not rewritten. A line part-delivered against the bad seed
   * arrives holding 88.5 in stock; the panel must go on saying 88.5 rather than rounding it.
   */
  it("still shows a fraction that is already on file rather than rounding it", () => {
    // The seeding defect left an apron at 88.5. Pressing Everything arrived fills the box from
    // what is owed, and it must fill it with 88.5 — the figure is wrong and hiding it is worse.
    panel([line({ ...APRONS, orderedQty: 90, receivedQty: 1.5, stillToCome: 88.5 })]);
    fireEvent.click(screen.getByRole("button", { name: "Everything arrived" }));
    expect(box(/Apron received now/)).toHaveValue(88.5);
  });
});

/* ------------------------------------------------------------------ inside the shared <Form> */

function dish(o: Partial<BandDish>): BandDish {
  return { recipeId: "r1", name: "Kheer", category: "Sweets", unit: "L", target: 20, max: 50000, ...o };
}

/**
 * The band in a `<Form>`, holding its own amounts.
 *
 * <p><b>Why the state and not a `vi.fn()`.</b> `KitchenBand`'s amount box is fully controlled: it
 * renders `d.target` and reports keystrokes through `onAmount`. A spy swallows them, so the box
 * stays empty, nothing ever mismatches its step, and a test written that way passes while proving
 * nothing at all. This feeds the typed figure back, exactly as `MealComposer` does.
 */
function Band({ dishes: given }: { dishes: BandDish[] }) {
  /*
   * Only the typed amounts are held. The rest of each dish — its unit above all — is read from the
   * props on every render, so a rerender with a different unit reaches the box. Holding the whole
   * dish in state would freeze the first render's unit and the "starts refusing" test below would
   * pass or fail for the wrong reason.
   */
  const [targets, setTargets] = useState<Record<string, number | null>>(() =>
    Object.fromEntries(given.map((d) => [d.recipeId, d.target])),
  );
  const dishes = given.map((d) => ({ ...d, target: targets[d.recipeId] ?? null }));
  return (
    <Form aria-label="Plan a meal" onSubmit={(e) => e.preventDefault()}>
      <KitchenBand
        kitchen={{ kitchenId: "k1", kitchenName: "Main kitchen" }}
        dishes={dishes}
        offered={[]}
        needed={4}
        confirming={false}
        readout={{ value: "4", tone: "neutral" }}
        names={null}
        action={null}
        footer={null}
        onNeeded={vi.fn()}
        onAdd={vi.fn()}
        onAmount={(recipeId, raw) =>
          setTargets((cur) => ({ ...cur, [recipeId]: raw === "" ? null : Number(raw) }))
        }
        onDrop={vi.fn()}
        onRemove={null}
        onConfirmRemove={vi.fn()}
        onKeep={vi.fn()}
      />
      <button type="submit">Save</button>
    </Form>
  );
}

const saveBand = () => fireEvent.click(screen.getByRole("button", { name: "Save" }));

describe("planning a dish — the sentence Form says from stepMismatch", () => {
  it("steps a dish measured in pieces by 1 and one measured in litres by any", () => {
    render(<Band dishes={[dish({ unit: "PIECES", name: "Ladoo" }), dish({ recipeId: "r2", unit: "L" })]} />);
    expect(box(/Amount of Ladoo/)).toHaveAttribute("step", "1");
    expect(box(/Amount of Kheer/)).toHaveAttribute("step", "any");
  });

  it("refuses 1.5 ladoos in the shared sentence", () => {
    render(<Band dishes={[dish({ unit: "PIECES", name: "Ladoo", target: null })]} />);
    fireEvent.change(box(/Amount of Ladoo/), { target: { value: "1.5" } });
    saveBand();
    expect(screen.getByText("Amount of Ladoo must be a whole number")).toHaveClass("text-danger");
    expect(box(/Amount of Ladoo/)).toHaveAttribute("aria-invalid", "true");
  });

  /*
   * Asserts an absence, so it would pass on its own with the rule deleted. The counted cases above
   * are what give it meaning; the proof file records breaking `isCountedUnit` and watching those
   * fail while this one stays green.
   */
  it("still takes 1.5 litres of kheer", () => {
    render(<Band dishes={[dish({ target: null })]} />);
    fireEvent.change(box(/Amount of Kheer/), { target: { value: "1.5" } });
    saveBand();
    expect(screen.queryByText(/must be a whole number/)).toBeNull();
    expect(box(/Amount of Kheer/)).toHaveValue(1.5);
  });

  /*
   * "A box whose unit can change while the form is open must follow it." The recipe's unit arrives
   * as a prop here, so the change is a re-render — the same path a `useState` unit picker takes.
   */
  it("starts refusing the moment a dish's unit becomes a count", () => {
    const { rerender } = render(<Band dishes={[dish({ unit: "KG", name: "Ladoo", target: null })]} />);
    expect(box(/Amount of Ladoo/)).toHaveAttribute("step", "any");
    rerender(<Band dishes={[dish({ unit: "PIECES", name: "Ladoo", target: null })]} />);
    expect(box(/Amount of Ladoo/)).toHaveAttribute("step", "1");
  });

  it("still shows a planned amount that is already a fraction of a counted dish", () => {
    render(<Band dishes={[dish({ unit: "PIECES", name: "Ladoo", target: 88.5 })]} />);
    expect(box(/Amount of Ladoo/)).toHaveValue(88.5);
  });
});

/* ----------------------------------------------------------- the keypad a phone is asked for */

describe("the keypad", () => {
  it("asks for digits only on a counted box and a decimal point on a weighed one", () => {
    panel([APRONS, DAL]);
    expect(box(/Apron received now/)).toHaveAttribute("inputmode", "numeric");
    expect(box(/Toor dal received now/)).toHaveAttribute("inputmode", "decimal");
  });
});
