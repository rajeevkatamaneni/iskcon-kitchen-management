"use client";

import { useCallback, useId, useLayoutEffect, useRef, useState } from "react";

/**
 * A short explanation attached to a control, reachable by mouse, keyboard and touch.
 *
 * <p>Written for the one place that needed it first: the Edit button on a library recipe, which is
 * unavailable until the temple has taken its own copy. A genuinely `disabled` button was the obvious
 * way to show that and is the wrong one — it fires no pointer events in Safari and takes no focus
 * anywhere, so the explanation exists in the markup and never reaches the person who needs it. The
 * button that wears this is `aria-disabled` instead: it looks the same, still refuses the press, and
 * stays focusable and hoverable.
 *
 * <p>Touch gets an answer too. There is no hover on a phone, so the first tap shows the tip rather
 * than doing nothing, and a tap anywhere else dismisses it.
 *
 * <p><strong>It stays on the screen</strong> (T-256, for R-INV-5). It used to be centred under its
 * control and nothing else, so a control near the right edge — the tick beside an invoice's Grand
 * total, the "i" on the last field of a row, anything at 390px — pushed half the tip past the edge
 * of the window, where it could not be read and made the page scroll sideways. The requirement was
 * explicit that this be fixed here, once, rather than worked around on each screen that hit it.
 * So, measured just after it opens and before it is painted:
 *
 * <ul>
 *   <li>Its width is capped at the window less a margin either side, so it can always fit.</li>
 *   <li>If centring puts an edge past that margin, it slides along until it does not. It still
 *       opens from its control; only its horizontal position moves.</li>
 *   <li>If there is no room below the control and there is room above, it opens above.</li>
 * </ul>
 *
 * <p>It is measured when it opens, from the centred position, and again whenever its control moves
 * sideways or the window changes size while it is open. Nothing is measured while it is closed.
 *
 * <p><strong>Why it watches its control, not just the window</strong> (T-310). Refitting on the
 * window's `resize` event alone was not enough. Measured at 390 on an invoice: with the tick's tip
 * open while the window narrowed from 1280, `resize` fired with the tick still at x 309, and only
 * afterwards did the page re-render for its narrow layout and move the tick to x 325. The tip kept the
 * slide it had worked out for x 309 and ran 7.9px off the right edge. Any control can be moved by
 * something the tip never hears about (a re-render, a banner appearing, data arriving), so while it
 * is open it checks once a frame whether its control has moved sideways or the window has changed
 * size, and refits only when one has. That is one `getBoundingClientRect` a frame, only while a tip is
 * showing. Scrolling is deliberately not a trigger: it moves the control up and down, which does not
 * change the slide, and re-deciding above-or-below mid-scroll would make the tip jump. It is never laid out off the
 * screen even for a moment, because on a phone that moment alone zooms the page out (T-273; see
 * `fit`).
 */
/** The least space kept between a tip and the edge of the window, in pixels. */
const EDGE = 8;
/** The space between the control and its tip (`mt-2` / `mb-2`). */
const GAP = 8;

export function Tooltip({
  text,
  children,
}: {
  text: string;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tip = useRef<HTMLSpanElement | null>(null);
  /*
    Placed by writing its inline position directly rather than through React state. Kept out of
    `style={…}` on purpose: React would write its own value back over the fitted one on the next
    render.

    It is measured from the centred-below position, but never laid out there (T-273). Measured at
    390 in Chrome's phone mode, laying the tip out centred beside the Grand total's tick, even for
    the moment before it was moved, widened the page to 451px: the phone zoomed the whole page out to
    fit it and the scroll position jumped 133px, so the tick the person had just tapped slid off the
    bottom of the screen. So the centred position is read from a copy of the tip inside a probe the
    size of the control, whose `overflow: hidden` keeps the copy from ever counting towards the
    page's width (a clipped box still reports where it is), and the tip itself is kept out of layout
    (`display: none`) until its final place is written. All in one synchronous pass before paint.
  */
  const fit = useCallback(() => {
    const el = tip.current;
    const wrap = el?.parentElement;
    if (!el || !wrap) return;
    el.style.display = "none";
    el.style.top = "100%";
    el.style.bottom = "auto";
    el.style.marginTop = `${GAP}px`;
    el.style.marginBottom = "0";
    el.style.transform = "translateX(-50%)";

    const probe = document.createElement("span");
    probe.setAttribute("aria-hidden", "true");
    probe.style.cssText =
      "position:absolute;left:0;top:0;width:100%;height:100%;overflow:hidden;visibility:hidden;pointer-events:none";
    const copy = el.cloneNode(true) as HTMLSpanElement;
    copy.removeAttribute("id");
    copy.style.display = "";
    probe.appendChild(copy);
    wrap.appendChild(probe);
    const r = copy.getBoundingClientRect();
    probe.remove();

    const trigger = wrap.getBoundingClientRect();
    // clientWidth, not innerWidth: innerWidth counts a vertical scrollbar the tip cannot sit under.
    const vw = document.documentElement.clientWidth || window.innerWidth;
    const vh = window.innerHeight;
    let shift = 0;
    if (r.left < EDGE) shift = EDGE - r.left;
    else if (r.right > vw - EDGE) shift = vw - EDGE - r.right;
    el.style.transform = `translateX(calc(-50% + ${Math.round(shift)}px))`;
    const roomAbove = trigger.top;
    if (r.bottom > vh - EDGE && roomAbove >= r.height + GAP + EDGE) {
      el.style.top = "auto";
      el.style.bottom = "100%";
      el.style.marginTop = "0";
      el.style.marginBottom = `${GAP}px`;
    }
    el.style.display = "";
  }, []);

  useLayoutEffect(() => {
    if (!open) return;
    fit();
    let last = where();
    let frame = requestAnimationFrame(function watch() {
      const now = where();
      if (now !== last) {
        last = now;
        fit();
      }
      frame = requestAnimationFrame(watch);
    });
    window.addEventListener("resize", fit);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("resize", fit);
    };

    /** What decides the slide: the control's left edge and width, and the window's size. */
    function where() {
      const r = tip.current?.parentElement?.getBoundingClientRect();
      const vw = document.documentElement.clientWidth || window.innerWidth;
      return r ? `${r.left}|${r.width}|${vw}|${window.innerHeight}` : "";
    }
  }, [open, fit]);

  function show() {
    if (timer.current) clearTimeout(timer.current);
    setOpen(true);
  }

  function hide() {
    // A short grace period, so moving the pointer from the control onto the tip itself — or between
    // two controls that each have one — does not flicker.
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setOpen(false), 80);
  }

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
      onTouchStart={() => setOpen((was) => !was)}
      onKeyDown={(e) => {
        if (e.key === "Escape") setOpen(false);
      }}
    >
      <span aria-describedby={open ? id : undefined}>{children}</span>

      {open && (
        <span
          id={id}
          ref={tip}
          role="tooltip"
          // `normal-case`, a regular weight and no tracking: a tip inside a table heading would
          // otherwise inherit the heading's 11px uppercase and read as shouting.
          className="absolute left-1/2 top-full z-10 mt-2 w-max max-w-[min(20rem,calc(100vw-16px))] -translate-x-1/2 rounded-control bg-ink px-3 py-2 text-left text-sm font-normal normal-case tracking-normal whitespace-normal text-ink-inverse shadow-overlay"
        >
          {text}
        </span>
      )}
    </span>
  );
}
