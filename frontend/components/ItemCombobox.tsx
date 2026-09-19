"use client";

import { useId, useState } from "react";

/**
 * A type-ahead for choosing an item, or typing something the catalogue has never heard of
 * (R-PO-3, design A of the `dev-po` mock; T-263).
 *
 * <p><b>Why it is shared, and why nothing in it is about orders.</b> The purchase-order form is its
 * first caller and the invoice form (R-INV-3) is its second. Both put "what is being bought" into a
 * row of a table, both want one party's own items first (the vendor's), and both need a way out for
 * the thing that is not in the catalogue at all. So the caller says which items come first, what
 * that group is called for a screen reader, and what each item's detail line reads — a list price
 * on an order, perhaps the last price paid on an invoice — and this component knows none of it.
 * The words that are left here ("Other ingredients", the one-off offer) are the document's own and
 * mean the same thing on either screen.
 *
 * <p><b>Two groups, then the one-off.</b> Typing lists the first group's matches, then every other
 * item under an "Other ingredients" divider. Only when the text matches nothing at all does the
 * last line offer "Add ‘<text>’ as a one-off item" (R-PO-3's wording, in the mock's curly quotes:
 * the document's straight ones were typing, not wording — conductor ruling, 2026-09-19, and curly
 * quotes apply across the app from then on). The offer is withheld while
 * anything matches on purpose: it is how a thing gets onto a bill without inventing an ingredient
 * for it, and offering it beside "Tomato, ripe" would invite a second, free-text tomato — the
 * near-duplicate §9A exists to prevent.
 *
 * <p><b>Keyboard.</b> The ARIA 1.2 combobox pattern with a listbox popup: focus never leaves the
 * input, and the highlighted option is named by `aria-activedescendant`. Down opens the list from an
 * empty box to browse, and moves within it once open; Up moves back; Enter picks; Escape closes.
 * Enter with the list closed is left alone, so it still submits the form the box sits in, as Enter
 * in any other text box on that form would.
 *
 * <p><b>Mouse.</b> Options are picked on `mousedown`, with the default prevented, so the input does
 * not blur — and close the list — before the pick lands.
 *
 * <p>Matching is a case-insensitive "contains" on the name, as the mock does it. Aliases are not
 * searched: the option shows the name only, and a hit on a word the row does not show would read
 * as a wrong match.
 */

/** One thing the box can offer. `detail` is printed after the name, after a middle dot. */
export interface ComboItem {
  id: string;
  name: string;
  /** "₹32/Kg", "Kg" — whatever the caller wants said beside the name, or nothing. */
  detail?: string | null;
}

/** What a pick turns into: an item from one of the lists, or text typed as a one-off. */
export type ComboChoice<T extends ComboItem> = { kind: "item"; item: T } | { kind: "oneOff"; text: string };

export function ItemCombobox<T extends ComboItem>({
  label,
  firstItems,
  firstGroupLabel,
  otherItems,
  exclude,
  onChoose,
  placeholder = "Type an ingredient…",
}: {
  /** The box's accessible name. It has no visible label: it sits in a table under an "Item" heading. */
  label: string;
  /** Listed first, in the order given — the vendor's own items. */
  firstItems: T[];
  /** Names the first group for a screen reader ("Sold by Kalasipalya Vegetable Mandi"). */
  firstGroupLabel: string;
  /** Everything else, under the "Other ingredients" divider. An id also in `firstItems` is dropped. */
  otherItems: T[];
  /** Ids not to offer, usually because they are already on the form. */
  exclude?: ReadonlySet<string>;
  onChoose: (choice: ComboChoice<T>) => void;
  placeholder?: string;
}) {
  const baseId = useId();
  const listId = `${baseId}-list`;
  const otherHeadId = `${baseId}-other`;
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const [browse, setBrowse] = useState(false);
  const [active, setActive] = useState(0);

  const q = text.trim().toLowerCase();
  const firstIds = new Set(firstItems.map((i) => i.id));
  const hit = (i: T) => !exclude?.has(i.id) && (q === "" || i.name.toLowerCase().includes(q));
  const firsts = firstItems.filter(hit);
  const others = otherItems.filter((i) => !firstIds.has(i.id) && hit(i));
  const oneOff = q !== "" && firsts.length === 0 && others.length === 0 ? text.trim() : null;

  const choices: ComboChoice<T>[] = [
    ...[...firsts, ...others].map((item): ComboChoice<T> => ({ kind: "item", item })),
    ...(oneOff ? [{ kind: "oneOff", text: oneOff } as ComboChoice<T>] : []),
  ];
  // The list opens as soon as something is typed, or on Down from an empty box. Focus alone does
  // not open it: tabbing through the form onto this row should not throw 200 ingredients over the
  // table.
  const visible = open && (q !== "" || browse) && choices.length > 0;
  const at = Math.min(active, choices.length - 1);
  const optionId = (i: number) => `${baseId}-opt-${i}`;

  function close() {
    setOpen(false);
    setBrowse(false);
  }

  function choose(c: ComboChoice<T>) {
    onChoose(c);
    setText("");
    close();
    setActive(0);
  }

  function option(c: ComboChoice<T>, i: number) {
    const on = i === at;
    return (
      <div
        key={optionId(i)}
        id={optionId(i)}
        role="option"
        aria-selected={on}
        onMouseDown={(e) => {
          e.preventDefault();
          choose(c);
        }}
        onMouseEnter={() => setActive(i)}
        className={`cursor-pointer px-3 py-2 text-sm ${on ? "bg-sunken" : ""}`}
      >
        {c.kind === "oneOff" ? (
          <span className="text-ink">{`Add ‘${c.text}’ as a one-off item`}</span>
        ) : (
          <>
            <span className="text-ink">{c.item.name}</span>
            {c.item.detail && <span className="tabular-nums text-ink-secondary">{` · ${c.item.detail}`}</span>}
          </>
        )}
      </div>
    );
  }

  return (
    <div className="relative min-w-64">
      <input
        role="combobox"
        aria-label={label}
        aria-expanded={visible}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={visible ? optionId(at) : undefined}
        autoComplete="off"
        value={text}
        placeholder={placeholder}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
          setActive(0);
        }}
        onBlur={close}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            if (!visible) {
              setOpen(true);
              setBrowse(true);
              setActive(0);
            } else setActive(Math.min(at + 1, choices.length - 1));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            if (visible) setActive(Math.max(at - 1, 0));
          } else if (e.key === "Enter") {
            if (visible && choices[at]) {
              e.preventDefault();
              choose(choices[at]);
            }
          } else if (e.key === "Escape") {
            if (visible) {
              e.preventDefault();
              close();
            }
          }
        }}
        className="min-h-touch w-full rounded-control border border-hairline px-3"
      />
      {visible && (
        <div
          id={listId}
          role="listbox"
          aria-label="Matching ingredients"
          className="absolute left-0 top-full z-20 mt-1 max-h-80 w-full min-w-64 overflow-y-auto rounded-control border border-hairline bg-raised py-1 shadow-overlay"
        >
          {firsts.length > 0 && (
            <div role="group" aria-label={firstGroupLabel}>
              {firsts.map((_, i) => option(choices[i], i))}
            </div>
          )}
          {others.length > 0 && (
            <div role="group" aria-labelledby={otherHeadId}>
              <div
                id={otherHeadId}
                role="presentation"
                className={`px-3 pb-1 pt-2 text-xs font-semibold text-ink-muted ${firsts.length > 0 ? "mt-1 border-t border-hairline" : ""}`}
              >
                Other ingredients
              </div>
              {others.map((_, j) => option(choices[firsts.length + j], firsts.length + j))}
            </div>
          )}
          {oneOff && option(choices[choices.length - 1], choices.length - 1)}
        </div>
      )}
    </div>
  );
}
