"use client";

import {
  forwardRef,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ForwardedRef,
  type FormEvent,
  type FormHTMLAttributes,
} from "react";
import { createPortal } from "react-dom";

import { FIELD_ERROR } from "@/components/Field";
import { messageFor, UNNAMED_FIELD, type ControlFacts } from "@/components/ds/formMessages";

/**
 * A `<form>` that says what is wrong with each box in words, under the box, in red (T-160).
 *
 * <p>Rajeev's ruling, 2026-09-11: *"Required fields should carry `required` on the element and if
 * left unfilled, we should at least show 'Required' in red on form submit. Ideally, we should say
 * 'Quantity is required' OR 'Note is required'."* Until this component every form left that job to
 * the browser, whose grey bubble names no field, disappears after a few seconds, is worded by the
 * device's language rather than by us, and is invisible to anybody who looked away as it appeared.
 *
 * <p>So the form is `noValidate` — the bubble never shows — and on submit this component reads the
 * browser's own verdict on every control and puts one sentence beside each box it refused. **It does
 * not reimplement a single check.** Whether a box is wrong is `ValidityState`, as the browser
 * computed it from the attributes the form already carries; what to *say* about it is
 * `formMessages.ts`. That is the whole division of labour, and it is why adopting this on a form is
 * swapping the tag and nothing else: the `required`, `min`, `max`, `step` and `maxLength` a form
 * already has are the rules.
 *
 * <h3>How the sentence gets beside a box it does not own</h3>
 *
 * <p>Most forms in this application hand-roll their controls — an `aria-label` here, a wrapping
 * `<label>` there — and only a minority use `Field`, whose own error slot is filled by its caller
 * and cannot be reached from outside. Rewriting 46 forms to route errors through props is the thing
 * this component exists to avoid. So it works from the DOM the form already renders:
 *
 * <ul>
 *   <li>For each refused control it creates one empty `<span>` — a *slot* — and places it
 *       immediately after the box, or after the `<label>` wrapping the box (inside the label, the
 *       sentence would become part of the box's own accessible name).</li>
 *   <li>The sentence itself is rendered **by React, through a portal into that slot**. Nothing here
 *       writes text or builds elements by hand; React mounts, updates and unmounts the sentence like
 *       any other element.</li>
 * </ul>
 *
 * <p>Why React does not fight the slot, since that is the question to ask of any node placed into a
 * tree React renders: React never re-reads the DOM, and it only ever inserts, moves and removes
 * nodes it created itself. A foreign sibling is invisible to its reconciliation — it is not in the
 * fiber tree, so it cannot be duplicated, and React has no reason to remove it. The two ways it can
 * still be disturbed are both handled rather than hoped about: React may insert a new node of its
 * own between the box and the slot, or unmount the box altogether (a form that swaps one set of
 * fields for another). A `MutationObserver` on the form, live only while a sentence is showing,
 * re-seats a displaced slot and drops the sentence of a box that has gone. The same observer
 * re-applies `aria-invalid` and `aria-describedby` if a re-render overwrites them, which is the only
 * other thing this component writes onto an element it does not own.
 *
 * <p>The alternative the task named — `Field` reading an error from context — would have covered
 * the minority of controls inside a `Field` and none of the hand-rolled ones, which is most of them.
 *
 * <h3>What it does, in order</h3>
 *
 * <ol>
 *   <li>On submit — from a button inside the form or from one outside it that points at it with
 *       `form="…"`, since a submit event fires on the form either way — every control belonging to
 *       the form is read: `form.elements`, which includes controls outside the tag that name it.</li>
 *   <li>A control is refused when `willValidate && !validity.valid`, which is exactly what
 *       `checkValidity()` computes, read without firing `invalid` events — plus the two refusals
 *       below that the browser has no attribute for. Disabled, read-only and hidden controls have
 *       `willValidate` false and are skipped by the browser, not by a rule of ours.</li>
 *   <li>Anything refused: `preventDefault`, the caller's `onSubmit` is **not** called, a sentence
 *       appears beside each refused box, and focus moves to the first of them once its sentence is on
 *       the page, so a screen reader reads the box and its problem together.</li>
 *   <li>Nothing refused: the caller's `onSubmit` is called with the event, exactly as a plain form
 *       would call it.</li>
 *   <li>While a sentence is showing, each keystroke or change re-reads the boxes that have one. A box
 *       that is now valid loses its sentence; a box that is wrong in a new way ("is required" becoming
 *       "must be at least 1") gets the new sentence. Boxes without a sentence gain none until the
 *       next submit — nobody is told off mid-word.</li>
 * </ol>
 *
 * <h3>The two checks the browser does not make (T-203)</h3>
 *
 * <p>"It does not reimplement a single check" above is still true; these are not reimplementations
 * but two rules HTML has no attribute for, and both used to end in a press that did nothing and said
 * nothing (T-172's proof lists the six dialogs). Rajeev decided they must say so in red, like any
 * other refusal, and the work manager decided there would be one rule for every form rather than a
 * sentence per dialog:
 *
 * <ul>
 *   <li><b>A required text box holding only spaces is blank.</b> The browser counts "   " as filled
 *       in, so `required` passes; every server column behind these boxes refuses it. The box gets
 *       "&lt;name&gt; is required", the same words as an empty box, because to the person it is the same
 *       mistake. Only `<textarea>` and the free-text `<input>` types — a select or a number box has no
 *       value of only spaces to have.</li>
 *   <li><b>`data-more-than="0"` is an exclusive floor.</b> `min="0"` lets exactly 0 through, and a
 *       credit of 0 is not a credit. A value at or below the attribute is reported as a
 *       `rangeUnderflow`, and `formMessages` words it "&lt;name&gt; must be more than 0". A data attribute
 *       rather than a prop because every other fact this component reads — `required`, `min`, `step` —
 *       is read off the element, and a box outside a `Field` has no props to give.</li>
 * </ul>
 *
 * <p>Both are folded into the validity this component reads, so everything downstream — the
 * sentence, re-checking as the person types, clearing when put right — treats them exactly as it
 * treats the browser's own refusals. The pages keep their own trim and amount checks as well, as the
 * twin guard: `Form` is how a refusal is said, not the only thing between a press and the server.
 */

type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

interface Problem {
  control: Control;
  /** The sentence's element id, which the control's aria-describedby points at. */
  id: string;
  message: string;
}

export interface FormProps extends Omit<FormHTMLAttributes<HTMLFormElement>, "noValidate" | "onSubmit"> {
  /** Called only when every control passes the browser's checks. */
  onSubmit?: (event: FormEvent<HTMLFormElement>) => void;
}

const CONTROL_TAGS = new Set(["INPUT", "SELECT", "TEXTAREA"]);
/** Marks a slot, so a label's text can be read without any sentence that happens to sit inside it. */
const SLOT_ATTRIBUTE = "data-form-error-slot";
/** `Field` appends "(required)" to its label. A sentence that already says "is required" drops it. */
const REQUIRED_MARKER = /\s*\(required\)\s*$/i;
/**
 * The text colours that mark words inside a `<label>` as *about* the box rather than the box's name
 * (T-171): `text-ink-secondary` is `FIELD_HINT`'s colour and every grey note under a question,
 * `text-ink-muted` is `Field`'s "(required)" and the grey half of a tick-box's line, and
 * `text-danger` is `FIELD_ERROR`'s and every inline error. Every question in a label is `text-ink`
 * or carries no colour of its own, so the colour is the one mark the two already reliably differ by.
 */
const NOT_THE_NAME = ["text-ink-secondary", "text-ink-muted", "text-danger"];

function isControl(el: Element | EventTarget | null): el is Control {
  return el instanceof Element && CONTROL_TAGS.has(el.tagName);
}

/** Input types whose value is free text a person types, where a value of only spaces can arise. */
const TEXT_INPUT_TYPES = new Set(["text", "search", "tel", "url", "email", "password"]);
/** The exclusive lower bound a number box can carry (T-203); see the class comment. */
const MORE_THAN_ATTRIBUTE = "data-more-than";

/** A required text box whose value is spaces and nothing else: filled in to the browser, blank to us. */
function onlySpaces(control: Control): boolean {
  const textual =
    control.tagName === "TEXTAREA" || (control.tagName === "INPUT" && TEXT_INPUT_TYPES.has(control.type));
  return textual && control.required && control.value !== "" && control.value.trim() === "";
}

/**
 * A box holding a number at or below its `data-more-than`. An empty box is left to `required` and a
 * half-typed one to `badInput`, so neither is also told it is under the floor.
 */
function atOrBelowFloor(control: Control): boolean {
  if (control.tagName !== "INPUT" || control.validity.badInput) return false;
  const floor = control.getAttribute(MORE_THAN_ATTRIBUTE)?.trim();
  if (!floor || control.value.trim() === "") return false;
  const value = Number(control.value);
  const limit = Number(floor);
  return Number.isFinite(value) && Number.isFinite(limit) && value <= limit;
}

/**
 * The browser's verdict on a control with this component's two additions folded in: spaces count as
 * `valueMissing` and a value at or below the floor as `rangeUnderflow`. Read fresh every time, never
 * cached, because the value is what changes between one press and the next.
 */
function verdictOf(control: Control): ControlFacts["validity"] & { valid: boolean } {
  const native = control.validity;
  const blank = onlySpaces(control);
  const underFloor = atOrBelowFloor(control);
  return {
    valid: native.valid && !blank && !underFloor,
    valueMissing: native.valueMissing || blank,
    typeMismatch: native.typeMismatch,
    badInput: native.badInput,
    rangeUnderflow: native.rangeUnderflow || underFloor,
    rangeOverflow: native.rangeOverflow,
    stepMismatch: native.stepMismatch,
    tooLong: native.tooLong,
  };
}

function isRefused(control: Control): boolean {
  return control.willValidate && !verdictOf(control).valid;
}

/** A node's text on one line, with `Field`'s "(required)" taken off the end. */
function textOf(node: Node): string {
  return (node.textContent ?? "").replace(/\s+/g, " ").replace(REQUIRED_MARKER, "").trim();
}

/**
 * The field's name, as the reader sees it: its aria-label, else its label's text, else its `name`.
 *
 * <p>A label's text is read from a copy with the controls taken out, because a wrapping label holds
 * its own box — and a `<select>` inside a label would otherwise lend the label every option it has.
 *
 * <p>Then anything inside the label coloured as a hint, a note or an error is taken out too (T-171).
 * Screens put a question and the sentence under it in one `<label>` — "Serviced on" and "A service
 * dated next Tuesday has not happened yet." — and `textContent` joins the two spans with nothing
 * between them, so the refusal read "Serviced onA service dated next Tuesday has not happened yet. is
 * required". Adding a space would only have made the same wrong name legible: the hint is not the
 * name at all.
 *
 * <p>Why the colour and not the position. "The words before the box" fits a question above its box,
 * and fails every tick-box and radio, whose words come after the box and are often followed by a grey
 * note of their own ("This kind of meal is a feast" then "it must name the festival it is for"). And
 * no hint inside a label carries an id or a role to go by: the one hint in these forms pointed at by
 * `aria-describedby`, on closing an order, was put outside its label on purpose. The colour is on
 * every hint and error, and on no question.
 *
 * <p>Only an element's own classes count, never the label's: most labels are `text-ink-secondary`
 * themselves, and a tick-box whose words sit straight in the label ("Preferred") would lose its name.
 * If taking the marked pieces out leaves nothing, the label's whole text is the name, as before.
 */
function nameOf(control: Control): string {
  const ariaLabel = control.getAttribute("aria-label")?.trim();
  if (ariaLabel) return ariaLabel;

  const label = control.labels?.[0];
  if (label) {
    const copy = label.cloneNode(true) as HTMLElement;
    copy.querySelectorAll(`input, select, textarea, button, [${SLOT_ATTRIBUTE}]`).forEach((n) => n.remove());
    const whole = textOf(copy);
    copy.querySelectorAll("*").forEach((n) => {
      if (NOT_THE_NAME.some((token) => n.classList.contains(token))) n.remove();
    });
    const text = textOf(copy) || whole;
    if (text) return text;
  }

  return control.getAttribute("name")?.trim() || UNNAMED_FIELD;
}

function factsOf(control: Control): ControlFacts {
  return {
    type: control.type,
    validity: verdictOf(control),
    min: control.getAttribute("min") ?? "",
    max: control.getAttribute("max") ?? "",
    step: control.getAttribute("step") ?? "",
    maxLength: "maxLength" in control ? control.maxLength : -1,
    moreThan: control.getAttribute(MORE_THAN_ATTRIBUTE)?.trim() ?? "",
  };
}

function describe(control: Control): string {
  return messageFor(nameOf(control), factsOf(control));
}

/** The element the slot follows: the wrapping label where there is one, so the sentence stays out of the name. */
function anchorOf(control: Control): Element {
  return control.closest("label") ?? control;
}

function tokens(value: string | null): string[] {
  return (value ?? "").split(/\s+/).filter(Boolean);
}

/** A layout effect in the browser, and a no-op effect on the server, where there is no layout. */
const useBrowserLayoutEffect = typeof window === "undefined" ? useEffect : useLayoutEffect;

export const Form = forwardRef(function Form(
  { onSubmit, children, ...rest }: FormProps,
  ref: ForwardedRef<HTMLFormElement>
) {
  const baseId = useId();
  const formRef = useRef<HTMLFormElement | null>(null);
  const [problems, setProblems] = useState<Problem[]>([]);

  // Mirrors of state and bookkeeping that the observer and the document listener read. They live
  // in refs because both callbacks are registered once and must see the latest render.
  const problemsRef = useRef<Problem[]>(problems);
  const slots = useRef(new Map<Control, HTMLElement>());
  const ids = useRef(new WeakMap<Control, string>());
  const nextId = useRef(0);
  /** What aria-invalid said before this component set it, so clearing puts back the owner's value. */
  const originalInvalid = useRef(new Map<Control, string | null>());
  const pendingFocus = useRef<Control | null>(null);

  const setRef = useCallback(
    (node: HTMLFormElement | null) => {
      formRef.current = node;
      if (typeof ref === "function") ref(node);
      else if (ref) ref.current = node;
    },
    [ref]
  );

  const idFor = useCallback(
    (control: Control) => {
      let id = ids.current.get(control);
      if (!id) {
        id = `${baseId}-error-${nextId.current++}`;
        ids.current.set(control, id);
      }
      return id;
    },
    [baseId]
  );

  /** The slot for a control, created on first need. Placed into the page by {@link sync}, never here. */
  const slotFor = useCallback((control: Control) => {
    let slot = slots.current.get(control);
    if (!slot) {
      slot = control.ownerDocument.createElement("span");
      slot.setAttribute(SLOT_ATTRIBUTE, "");
      slot.className = "block";
      slots.current.set(control, slot);
    }
    return slot;
  }, []);

  /**
   * Makes the page agree with `problems`: each refused box has its slot right after it and carries
   * aria-invalid and a describedby naming its sentence; every other slot is gone and every other box
   * is as its owner left it. Idempotent — it touches nothing already right — which is what lets the
   * observer call it on its own mutations without looping.
   */
  const sync = useCallback(() => {
    const live = new Set<Control>();
    let gone = false;

    for (const { control, id } of problemsRef.current) {
      if (!control.isConnected) {
        gone = true;
        continue;
      }
      live.add(control);

      const slot = slotFor(control);
      const anchor = anchorOf(control);
      if (anchor.nextSibling !== slot) anchor.after(slot);

      if (!originalInvalid.current.has(control)) {
        originalInvalid.current.set(control, control.getAttribute("aria-invalid"));
      }
      if (control.getAttribute("aria-invalid") !== "true") control.setAttribute("aria-invalid", "true");
      const describedBy = tokens(control.getAttribute("aria-describedby"));
      if (!describedBy.includes(id)) control.setAttribute("aria-describedby", [...describedBy, id].join(" "));
    }

    for (const [control, slot] of Array.from(slots.current)) {
      if (live.has(control)) continue;
      slot.remove();
      slots.current.delete(control);

      if (originalInvalid.current.has(control)) {
        const original = originalInvalid.current.get(control) ?? null;
        if (original === null) control.removeAttribute("aria-invalid");
        else control.setAttribute("aria-invalid", original);
        originalInvalid.current.delete(control);
      }
      const id = ids.current.get(control);
      const describedBy = tokens(control.getAttribute("aria-describedby")).filter((t) => t !== id);
      if (describedBy.length) control.setAttribute("aria-describedby", describedBy.join(" "));
      else control.removeAttribute("aria-describedby");
    }

    if (gone) setProblems((current) => current.filter((p) => p.control.isConnected));
  }, [slotFor]);

  // After every render: bring the page into line, then move focus once the sentence exists.
  useBrowserLayoutEffect(() => {
    problemsRef.current = problems;
    sync();
    const focusTarget = pendingFocus.current;
    if (focusTarget) {
      pendingFocus.current = null;
      if (focusTarget.isConnected) focusTarget.focus();
    }
  });

  // The guard against a re-render that the form's owner causes without re-rendering this component:
  // a node React slips between a box and its slot, a box unmounted, an attribute written over.
  const showing = problems.length > 0;
  useEffect(() => {
    const form = formRef.current;
    if (!form || !showing) return;
    const observer = new MutationObserver(() => sync());
    observer.observe(form, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["aria-invalid", "aria-describedby"],
    });
    return () => observer.disconnect();
  }, [showing, sync]);

  // Clearing a sentence once its box is put right. Listened for on the document, in the capture
  // phase, for two reasons: a control outside the <form> tag that names it with form="…" does not
  // bubble through the form, and a handler that stops propagation cannot hide the keystroke.
  useEffect(() => {
    const doc = formRef.current?.ownerDocument;
    if (!doc) return;

    function recheck(event: Event) {
      const target = event.target;
      if (!isControl(target) || !formRef.current || target.form !== formRef.current) return;
      const current = problemsRef.current;
      if (current.length === 0) return;

      let changed = false;
      const next: Problem[] = [];
      for (const problem of current) {
        if (!isRefused(problem.control)) {
          changed = true;
          continue;
        }
        const message = describe(problem.control);
        if (message !== problem.message) {
          changed = true;
          next.push({ ...problem, message });
        } else {
          next.push(problem);
        }
      }
      if (changed) setProblems(next);
    }

    doc.addEventListener("input", recheck, true);
    doc.addEventListener("change", recheck, true);
    return () => {
      doc.removeEventListener("input", recheck, true);
      doc.removeEventListener("change", recheck, true);
    };
  }, []);

  // Leaving the page: no slot outlives the form that placed it.
  useEffect(() => {
    const placed = slots.current;
    return () => {
      placed.forEach((slot) => slot.remove());
      placed.clear();
    };
  }, []);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    // React carries a submit up the component tree, across portals, so a dialog's form rendered
    // inside this form's component reaches this handler too. The DOM does not, and neither does
    // this: another form's submit is that form's business, and running this form's checks or its
    // owner's handler on it would submit the wrong thing.
    if (event.target !== event.currentTarget) return;

    const refused = Array.from(event.currentTarget.elements).filter(isControl).filter(isRefused);

    if (refused.length > 0) {
      event.preventDefault();
      pendingFocus.current = refused[0];
      setProblems(refused.map((control) => ({ control, id: idFor(control), message: describe(control) })));
      return;
    }

    if (problemsRef.current.length > 0) setProblems([]);
    onSubmit?.(event);
  }

  return (
    <form {...rest} ref={setRef} noValidate onSubmit={handleSubmit}>
      {children}
      {problems.map((problem) =>
        createPortal(
          <span id={problem.id} className={`mt-1.5 block ${FIELD_ERROR}`}>
            {problem.message}
          </span>,
          slotFor(problem.control),
          problem.id
        )
      )}
    </form>
  );
});
