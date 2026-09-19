import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { ItemCombobox, type ComboItem } from "@/components/ItemCombobox";

/**
 * The shared type-ahead (R-PO-3, T-263), on its own, without any screen around it.
 *
 * <p>It is shared because the invoice form (R-INV-3) will reuse it, so what is pinned here is the
 * contract a second caller relies on: the caller's first group before everything else, the "Other
 * ingredients" divider, the one-off offer only when nothing matches, and the ARIA combobox keys —
 * Down, Up, Enter, Escape — with focus never leaving the box. The purchase-order form's use of it
 * is tested in `po-create-form.test.tsx`.
 */

const THEIRS: ComboItem[] = [
  { id: "tom", name: "Tomato, ripe", detail: "₹32/Kg" },
  { id: "cur", name: "Curry leaves", detail: "₹60/Kg" },
];
const EVERYONE: ComboItem[] = [
  { id: "cas", name: "Cashew, whole", detail: "Kg" },
  { id: "tom", name: "Tomato, ripe", detail: "Kg" },
  { id: "tpu", name: "Tomato puree", detail: "Kg" },
];

function setup(exclude?: Set<string>) {
  const onChoose = vi.fn();
  render(
    <ItemCombobox
      label="Add an item"
      firstItems={THEIRS}
      firstGroupLabel="Sold by Kalasipalya"
      otherItems={EVERYONE}
      exclude={exclude}
      onChoose={onChoose}
    />
  );
  const box = screen.getByRole("combobox", { name: "Add an item" });
  return { box, onChoose };
}

const optionTexts = () => screen.getAllByRole("option").map((o) => o.textContent);

describe("what it lists", () => {
  it("is closed until something is typed, so tabbing onto it throws nothing over the table", () => {
    const { box } = setup();
    fireEvent.focus(box);
    expect(box).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("lists the first group with its detail, then the rest under an “Other ingredients” divider", () => {
    const { box } = setup();
    fireEvent.change(box, { target: { value: "tom" } });

    expect(box).toHaveAttribute("aria-expanded", "true");
    const list = screen.getByRole("listbox");
    // Tomato, ripe once only: it is in both lists and the first group wins.
    expect(optionTexts()).toEqual(["Tomato, ripe · ₹32/Kg", "Tomato puree · Kg"]);

    const groups = within(list).getAllByRole("group");
    expect(groups[0]).toHaveAccessibleName("Sold by Kalasipalya");
    expect(groups[1]).toHaveAccessibleName("Other ingredients");
    expect(within(groups[1]).getByText("Other ingredients")).toBeInTheDocument();
  });

  it("offers a one-off only when the text matches nothing, as the last line", () => {
    const { box } = setup();
    fireEvent.change(box, { target: { value: "cur" } });
    expect(optionTexts()).toEqual(["Curry leaves · ₹60/Kg"]);
    expect(screen.queryByText(/one-off/)).toBeNull();

    fireEvent.change(box, { target: { value: "  Plastic stool " } });
    expect(optionTexts()).toEqual(["Add ‘Plastic stool’ as a one-off item"]);
  });

  it("does not offer an item the caller excludes", () => {
    const { box } = setup(new Set(["tom"]));
    fireEvent.change(box, { target: { value: "tom" } });
    expect(optionTexts()).toEqual(["Tomato puree · Kg"]);
  });
});

describe("by keyboard", () => {
  it("moves with Down and Up, names the highlight with aria-activedescendant, and picks with Enter", () => {
    const { box, onChoose } = setup();
    fireEvent.change(box, { target: { value: "tom" } });

    const [first, second] = screen.getAllByRole("option");
    expect(first).toHaveAttribute("aria-selected", "true");
    expect(box).toHaveAttribute("aria-activedescendant", first.id);

    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(second).toHaveAttribute("aria-selected", "true");
    expect(box).toHaveAttribute("aria-activedescendant", second.id);
    // Down at the end stays at the end.
    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(second).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(box, { key: "ArrowUp" });
    expect(first).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(box, { key: "ArrowDown" });

    fireEvent.keyDown(box, { key: "Enter" });
    expect(onChoose).toHaveBeenCalledWith({ kind: "item", item: EVERYONE[2] });
    // Cleared and closed, ready for the next item.
    expect(box).toHaveValue("");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("opens on Down from an empty box, to browse everything", () => {
    const { box } = setup();
    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(optionTexts()).toEqual([
      "Tomato, ripe · ₹32/Kg",
      "Curry leaves · ₹60/Kg",
      "Cashew, whole · Kg",
      "Tomato puree · Kg",
    ]);
  });

  it("closes on Escape without choosing, and keeps what was typed", () => {
    const { box, onChoose } = setup();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(box).toHaveAttribute("aria-expanded", "false");
    expect(box).toHaveValue("tom");
    expect(onChoose).not.toHaveBeenCalled();
  });

  it("picks the one-off with Enter", () => {
    const { box, onChoose } = setup();
    fireEvent.change(box, { target: { value: "Plastic stool" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(onChoose).toHaveBeenCalledWith({ kind: "oneOff", text: "Plastic stool" });
  });

  it("leaves Enter alone when the list is closed, so it can still submit the form", () => {
    const { box, onChoose } = setup();
    const event = fireEvent.keyDown(box, { key: "Enter" });
    // fireEvent returns false only when the default was prevented.
    expect(event).toBe(true);
    expect(onChoose).not.toHaveBeenCalled();
  });
});

describe("by mouse", () => {
  it("picks an item on press, before the box can blur and close the list", () => {
    const { box, onChoose } = setup();
    fireEvent.change(box, { target: { value: "cur" } });
    const pressed = fireEvent.mouseDown(screen.getByRole("option", { name: /Curry leaves/ }));
    expect(pressed).toBe(false); // default prevented: the box keeps focus
    expect(onChoose).toHaveBeenCalledWith({ kind: "item", item: THEIRS[1] });
  });

  it("picks the one-off on press", () => {
    const { box, onChoose } = setup();
    fireEvent.change(box, { target: { value: "Gas cylinder" } });
    fireEvent.mouseDown(screen.getByRole("option", { name: "Add ‘Gas cylinder’ as a one-off item" }));
    expect(onChoose).toHaveBeenCalledWith({ kind: "oneOff", text: "Gas cylinder" });
  });

  it("closes when the box loses focus", () => {
    const { box } = setup();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.blur(box);
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});
