# Design System

**Status:** v1.5 — colour became the temple's choice, and the focus ring got a token of its own, 2026-08-28 (§2, §4). v1.4 — the accent darkened to clear AA on button text, and the words and the geometry of a form settled, 2026-08-21 (§2, §4, §9). v1.3 — contrast made a floor and badges set in semibold, 2026-08-20 (§2, §3). v1.2 added the `info` family and moved Ekadasi onto it, 2026-08-19. v1.1 revised the palette to terracotta/charcoal, 2026-08-10 (§2). v1.0 established 2026-08-04, before the first UI story (E1-S6). See CHANGELOG for each.
**Applies to:** every screen in the application.

Grounded in reference sites Rajeev selected (cocoon.com, stripe.com, docs.stripe.com, apple.com, melaniedaveid.com) and one explicit anti-reference (Google Cloud Console). The v1.1 palette takes its terracotta/charcoal direction from ISKCON's own saffron-orange identity (iskconsv.com); the spacing, type, and restraint are unchanged.

---

## 1. What we are aiming for

The brief, in Rajeev's words: *"VERY nicely crafted, intentional and very thoughtfully designed to help the user."*

The most useful principle to come out of the reference review:

> **Subtlety that carries information — not subtlety as decoration.**

The detail he singled out on melaniedaveid.com was a faint line appearing when you scroll past the end of the page. It isn't ornamental; it *tells you something* ("that's the end") without interrupting. That is the standard: quiet, and genuinely useful. A detail that exists only to look nice fails this test.

Applied here, that means things like a low-stock badge you can read without labels once you've learned it, a shift card whose fill state is legible at a glance, an Ekadashi day that looks quietly different in the planner so staff stop having to check.

### What we are avoiding

From the Google Cloud Console critique — an explicit list to design against:

| Anti-pattern | Our rule |
|---|---|
| Everything the same visual weight | A real type scale; hierarchy must be obvious at a squint |
| One colour doing four unrelated jobs | Terracotta means "primary action". Nothing else. |
| Borders everywhere | Separate with surface tone and space, not 1px lines |
| Components assembled without rhythm | Fixed spacing scale; no arbitrary values |
| No typographic scale | Defined below, and it is the whole hierarchy mechanism |

### Craft by audience

Discoverable-over-time craft and immediately-obvious clarity pull against each other. Resolved by **frequency of use**, not user capability:

| Surface | Users | Approach |
|---|---|---|
| Kitchen, ordering, admin | Daily, trained | Craft-forward. Reward fluency. Depth can reveal itself over weeks. |
| Volunteer shifts | Occasional, well-educated but unfamiliar | Primary path unmissable; craft lives underneath it |
| Donation, wish list | Strangers, once | Clarity absolute. Craft here means speed and trust, never cleverness. |

**Who never sees a screen:** full-time cooks and vendors. Neither reads English comfortably, and both receive *outputs* rather than using the app — cooks work from translated printed recipes, vendors receive translated purchase orders over WhatsApp. Both are Phase 1 features. This is why multilingual UI can remain Phase 2 without stranding anyone.

---

## 2. Colour

**This section names roles. A temple chooses the values.**

That changed on 2026-08-28, and it changed for a reason worth recording. At the demo on 22 August the terracotta was loved by part of the room and disliked by another part, and there was no palette that was going to satisfy both. The conclusion was not that we had picked the wrong colour. It was that colour is the one part of this interface where the temple's own taste should decide, and that everything else in this document — the type, the spacing, the geometry of a field, the restraint — is about legibility and rhythm and stays ours.

So: **twenty-three roles, fixed. Their values, chosen.** A temple administrator picks a *theme pack* under Settings, and it applies to everybody who serves at that temple. Nothing in the application refers to a colour by name — every one of about two thousand usages resolves through a semantic token to a CSS custom property, which is why this was a change to one config file and no screens.

The packs live in `frontend/lib/theme-packs.ts`, not in the database, and the reason is worth stating because the first attempt got it wrong. Nothing writes a pack at run time: they are produced by the build tool, contrast-checked, reviewed and deployed. A table would have been code wearing a table's clothes, and would have dragged in a migration per change, a policy set, an endpoint, a permission and an operator screen to administer sixteen rows. What the database holds is one column on `tenant_settings` (V72) carrying the identifier of the pack a temple picked, and it has no opinion about which identifiers are real — an unknown one resolves to the default in the browser rather than being refused at the boundary.

The values below are the **default pack, `temple-terracotta`** — the palette this application was designed in, what a temple wears before it chooses, what an unrecognised choice falls back to, and what it can return to by name afterwards. Read them as one pack's answers, not as the system.

A pack is never deleted, only **retired**: it stops being offered to anybody choosing and goes on resolving for temples already wearing it. Deleting is almost never the right operation on a colour scheme — a bad pack gets its colours corrected in place, and every temple on it is corrected with them.

**Provenance.** The first version cloned Cocoon's olive on warm beige. Replaced 2026-08-10 (Rajeev) with a terracotta/charcoal scheme drawn from ISKCON's own saffron-orange identity: the accent is a *softened* (desaturated) terracotta so it reads calm, and the neutrals are a near-neutral warm-grey so the orange never overwhelms the surfaces. See CHANGELOG.

### Surfaces

Warm-grey, a hair off neutral so the surfaces sit under the terracotta without reading as cream. Surfaces separate by tone, not by borders.

| Token | Value | Use |
|---|---|---|
| `canvas` | `#FFFFFF` | Page background |
| `raised` | `#FAF8F7` | Cards, panels, sidebar |
| `sunken` | `#F1EDEB` | Inputs, wells, table header rows |
| `border` | `#E7E1DD` | Hairline, only where tone alone is insufficient |
| `border-strong` | `#DAD1CB` | Hover, focus, emphasised dividers |

`raised` differs from white by only a few points per channel — a distinct surface that never announces itself.

### Text — warm charcoal

Never pure black. A trace of warmth ties the text to the terracotta accent and the warm-grey surfaces.

| Token | Value | Use |
|---|---|---|
| `text-primary` | `#2B2621` | Body, headings |
| `text-secondary` | `#6E6660` | Supporting text, labels |
| `text-muted` | `#716B65` | Placeholders, metadata. Darkened from `#9C948C` on 2026-08-20 — the original failed WCAG AA on every surface it was used on (2.99 on canvas, 2.82 on raised, 2.57 on sunken). |
| `text-inverse` | `#FCF8F5` | On terracotta or dark fills |

### Accent — terracotta

**One job: primary actions.** Buttons that commit something, the active navigation item, focus rings. Nothing decorative, ever. If terracotta appears somewhere that isn't the main thing to do on that screen, it is a bug.

| Token | Value | Note |
|---|---|---|
| `accent-bg` | `#F6EBE4` | Pale wash for selected rows and tint badges |
| `accent-border` | `#ECD9CF` | Secondary-button border, focus ring |
| `accent` | `#AE5838` | Softened terracotta — the primary. Darkened from `#BE6444` on 2026-08-21: the primary button sets `ink-inverse` on this fill and that pair measured **3.90:1**, under the 4.5 AA asks of body-size button text. `#AE5838` measures **4.68:1**. |
| `accent-hover` | `#94482D` | Darkened one step, moved with the accent |
| `accent-text` | `#8A4A2F` | Terracotta text on pale fills |

### The focus ring

A role of its own since 2026-08-28, and the reason is a defect rather than a preference.

| Token | Value | Note |
|---|---|---|
| `focus-ring` | `#BE775E` | The 3px ring on `:focus-visible`, and the only shadow this system allows |

It had been drawing its colour from `accent-border`, whose job is the quiet hairline on a secondary button and of which no contrast is asked. Measured while building the theme packs: **`#ECD9CF` on the page is 1.36:1**, against the **3:1** WCAG 2.2 SC 1.4.11 asks of a focus indicator. The one thing a keyboard user has to tell them where they are has, in practice, been invisible.

Separating the two is what made it fixable at all — raising the shared value to 3:1 would have put a dark line around every secondary button on every screen. `#BE775E` is the *lightest* terracotta clearing the floor on all three surfaces (3.51 canvas, 3.31 raised, 3.01 sunken): the smallest change that is still a correct one.

### Semantic — status only

Never decorative. If one of these appears, something is genuinely low, wrong, overdue, or complete. **Warning is gold, not orange**, so it never reads as the terracotta accent.

| Role | Fill | Text | Meaning here |
|---|---|---|---|
| `danger` | `#F7E7E3` | `#9B2C1F` | Overdue invoice, rejected delivery, sattvic violation |
| `warning` | `#F4EAD1` | `#87641A` | Low stock, expiring soon, under-filled shift. Nudged from `#8F6A1C` on 2026-08-20: it sat at 4.13 on its own wash, just under the 4.5 a badge needs. |
| `success` | `#E7EFE8` | `#3E6B48` | Paid, received, shift fully staffed |

### Accessibility

All text/background pairs meet WCAG AA (4.5:1 body, 3:1 large). Status is **never** conveyed by colour alone — every badge carries text, because kitchens are bright, screens are cheap, and roughly 1 in 12 men has some colour vision deficiency.

**A temple's choice is a choice about taste, and it cannot become a choice about legibility.** That is not a hope about how the packs were picked — it is a contract, and it is checked.

`tools/theme/build_theme_pack.py` holds the **thirty-four pairings** this interface actually puts in front of somebody, each with the floor it has to clear: body text on all three surfaces, the button label on its fill at rest and on hover, the accent as text on its own wash, the focus ring on all three surfaces, and each status colour on both its own wash and the page. Every lightness in a pack is *solved* against those floors rather than chosen and then tested, working in OKLCH and giving up chroma before it gives up contrast. A pack that fails one pairing does not build — and `__tests__/theme-contract.test.ts` runs the same thirty-four against every pack in the catalogue on every commit, so a pack that fails one does not ship either, including one edited by hand after the tool produced it.

This matters because it is precisely how the two contrast failures in this project's history happened. `ink-muted` in August and the focus ring above were both introduced by somebody picking a colour they liked and not thinking to check a pairing. Fifteen packs is forty times more opportunity to do that, and no amount of care survives it — so the care is in the tool.

Two consequences fall out of the contract, and both are deliberate:

- **Status hues are fixed across every pack.** Red means wrong and green means done in every palette. A temple that could recolour those could make its own interface lie. Their *saturation* barely moves between families either (0.13–0.17, against 0.075–0.185 for the accent) — a muted pack is muted in its accent and its surfaces, not in its alarms.
- **Vibrancy comes from chroma, not lightness.** A pale bright yellow cannot be a primary fill in a light interface, because nothing white enough to be "inverse" can be read on it. A saturated blue at the same lightness can, and reads every bit as vivid.

---

## 3. Typography

**Anek**, by [Ek Type](https://ektype.in/anek-family.html), a type collective in Mumbai. Open source, on Google Fonts, variable.

Anek covers ten scripts — Latin, Devanagari, Telugu, Tamil, Kannada, Bengali, Gujarati, Gurmukhi, Malayalam, Odia — and all ten were **drawn simultaneously from scratch** rather than one being adapted from another. Proportions, weight and rhythm are shared by design.

That distinction does real work here. Recipes and purchase orders are translated in Phase 1, so a single screen routinely carries an English label beside a Hindi ingredient name. With two unrelated families that seam is visible; with Anek it isn't.

```
var(--font-anek-latin), var(--font-anek-devanagari),
var(--font-anek-telugu), var(--font-anek-tamil),
system-ui, sans-serif
```

Browsers resolve missing glyphs family by family, so English renders in Anek Latin and Devanagari on the same screen picks up Anek Devanagari. Nothing in application code switches fonts.

**Rejected: Cocoon's own faces.** Akkurat and GT Ultra Fine are commercially licensed and cover no Indic script. Licensing them would cost real money and still leave translated pages falling back to a different typeface — paying for consistency that breaks exactly where it matters.

**Rejected: Inter with Noto fallback.** Inter is an excellent neutral UI face and the closest free match to Akkurat, but it supports no Indic script, so every translated screen would show two unrelated type designs meeting at a glyph boundary. Anek trades a little of Inter's neutrality for coherence across the scripts this app actually renders.

**Rejected: serif headings.** More distinctive, closer to Cocoon, but serif plus Indic is genuinely unsolved — headings would silently change face the moment content is translated.

**Currently loaded:** Latin, Devanagari, Telugu, Tamil, at weights 400/500/600. Kannada and Bengali are added when a temple configures those languages — loading every script upfront would cost bandwidth for scripts a temple never uses, which matters on the mid-range Android phones most volunteers carry.

### Scale — 16px base

Line heights are generous (~1.6 at body size), following the airiness common to every reference site.

| Token | Size / line-height | Use |
|---|---|---|
| `xs` | 12 / 16 | Timestamps, table metadata |
| `sm` | 14 / 20 | Labels, secondary text, badges |
| `base` | 16 / 26 | Body — the default |
| `lg` | 18 / 28 | Card titles, emphasised body |
| `xl` | 22 / 30 | Section headings |
| `2xl` | 28 / 36 | Page titles |
| `3xl` | 36 / 44 | Rare — empty states, donation amounts |

16px chosen over Stripe's 14px because of bright kitchen lighting, older devotees doing seva, and mid-range Android screens; and over Apple's 17px to preserve enough density for inventory and order tables.

**Badges and pills are set in 600 semibold.** A badge carries a whole fact in one or two words, on a
coloured ground, at the smallest size in the scale. It has to be taken in at a glance or it has
failed at its only job — and contrast alone makes a thing legible, not instant. Body text and hints
stay at their own weights; this is the exception that earns itself.

**Three weights only:** 400 regular, 500 medium, 600 semibold. More produces mush. Navigation hierarchy is carried by weight rather than colour or indentation, following Stripe's docs.

Large headings use slight negative letter-spacing (`-0.02em` at 28px and above) — the light-and-tight combination that makes big text read as considered rather than shouty.

### Contrast is a floor, not a preference

Every text-on-surface pair the app renders clears **WCAG AA — 4.5:1** for body-sized text. This is
checked by arithmetic rather than by eye: the palette above is the input, and the check reads the
tokens from `frontend/tailwind.config.ts` so it can never certify a value the app does not actually
use.

The rule that follows from it, and the reason two tokens moved on 2026-08-20: *muted* must mean
quiet, never faint. A hint nobody can read is not a subtle hint, it is a missing one.

**One known exception, deliberately left standing:** the primary button's label — `ink-inverse` on
`accent` — measures **3.90:1**. Fixing it means darkening the terracotta to about `#AB5A3D`, and
that colour is the product's identity rather than a utility token; it was chosen softened and
desaturated on purpose (v1.1). Recorded here as a decision awaiting Rajeev rather than a defect
nobody noticed.

---

## 4. Spacing, radii, motion

**Spacing:** 4, 8, 12, 16, 24, 32, 48, 64. No arbitrary values — inconsistent gaps are a large part of why the Cloud Console feels unconsidered.

**Radii:** `sm` 8px (inputs, badges), `md` 12px (buttons, small cards), `lg` 16px (panels, main cards), `pill` 9999px (status chips only).

Cocoon uses 50px on section cards. Beautiful on a marketing page, wrong for an application — at the size of an inventory row it turns everything into a lozenge and costs usable width. 16px keeps the softness at our scale.

**Motion:** 150ms for state changes, 200ms for entrances, `ease-out`. Nothing longer. Deliberately no blur-and-slide navigation like Stripe's — it is a marketing pattern for 40 destinations, it does not exist on touch, and it feels sluggish on a mid-range Android. Navigation is a persistent sidebar with no animation.

**Shadows:** none, except the focus ring, which has its own token — see §2. Depth comes from surface tone.

---

### The geometry of a field

Settled 2026-08-21, items 10, 11 and 23 of the UAT round-2 brief.

**A label, a hint and an error indent to `field-inset` — 13px.** That is `px-3` plus the 1px border
every input carries: exactly where the text *inside* the box begins. Set flush left they line up
with the box rather than with its contents, so a label floats 13px to the left of the very word it
names. One vertical line runs through the label, the value and the note about it.

`field-inset` is a named token, not an arbitrary value, and it is deliberately not on the spacing
scale. It is not a spacing choice — it is a measurement of another component.

**One label style: `text-sm font-medium text-ink`.** A step darker and a step heavier than the hint
under it. Hints are `text-ink-secondary`, errors are `text-danger`.

**A row of fields declares three tracks — label, control, hint — and every child takes its rows from
the row.** `FieldRow` does this with `grid-rows-subgrid`, and it wraps its own children so a caller
cannot opt a field out. `align-items` cannot do this job: it lines up the outer edges of each child,
and the outer edges are not what anybody is looking at — the boxes are. Bottom-aligning puts one
field's box level with its neighbour's hint text; top-aligning floats a field with no label a whole
line above the rest. Both have shipped here as the fix, twice each, and both are wrong.

A field with no hint leaves its hint cell empty. **Never type a `&nbsp;` to reserve the line** — that
only works until somebody adds a field and does not know to.

A readout — a figure the form worked out rather than asked for — is a peer of the fields beside it:
label above the box, not inside it. A label inside the box is what made the `Scales to` pill
impossible to align.

### One screen, one task

Settled 2026-08-21, Q1. Anywhere a button used to open a form on top of a list, the form is its own
screen. `FocusScreen` carries the pattern so a screen cannot half-follow it.

1. **Its own URL.** Linkable, reloadable, and the browser back button does the obvious thing.
2. **The sidebar stays.** Trapping someone on a form is worse than the distraction.
3. **The task is the `h1`**, and one line under it says whose record this is — "Terminate employment"
   over "Madhava Das · Kitchen assistant · joined 4 March 2024".
4. **Actions top right, together, secondary first: `[Cancel] [Primary]`.**
5. **The header is sticky.** Measured on live, terminating a member of staff with a ban ticked: the
   heading sits at 180px, the commit button ends at 1232px, the window is 836px. There is no scroll
   position where the person's name and the button that ends their employment are both on screen.
6. **No second copy of the buttons at the foot.** Two commit buttons is two answers to "where do I
   press". The header's button reaches the form with `form={id}`.
7. **No `← Back to` link anywhere.** The arrow disappears rather than being restyled. Cancel is the
   way out of every one of these.
8. **Committing returns to the list**, with the confirmation waiting there — and clearing itself.

**`Cancel`, not `Close`,** on anything that commits: it says what happens to what you typed. `Close`
survives only on a read-only record, where there is nothing to cancel. Two words, two acts.

**Where it stops: four.** A form of four fields or more becomes a screen. Three or fewer stays
inline. Sending somebody to another screen to type one word is friction, not focus. The number
matters more than the judgement: a form that grows a fourth field converts on its own rather than by
anybody's opinion.

### A table row answers the pointer

`hover:bg-raised/60` on every `<tr>` in every `<tbody>`, read-only and editable alike. One step of
tone. No border, and no cursor change on a row that is not clickable.

### A confirmation clears itself

A warning, an error or a piece of standing context stays until it is dealt with; every one of those
has something in it for the reader to do. A confirmation does not — the thing it confirms has
already happened — and it goes on sitting at the top of a list somebody is now working down. Pass
`autoDismiss` to `InlineNotice` on those and they fade after five seconds. It is honoured on
`success` and on the neutral `info`, and ignored on `warning` and `danger`.

---

## 5. Layout

**Sidebar 280px** (Stripe docs' value), fixed, with the working area filling the remainder. Collapses to a bottom bar or drawer under 768px.

**Content max-width 1200px** for dense screens; 640px for forms and reading. Full-width tables of a kitchen inventory are unreadable on a wide monitor.

**Touch targets:** minimum 44×44px. Non-negotiable — wet hands, phones, bright light.

---

## 6. Icons

**Tabler**, outline only, one consistent stroke weight. Around 5,800 icons, MIT licensed.

**Navigation only, and never without text.** Icons appear in the sidebar and in genuinely universal affordances (search). Every action elsewhere — Edit, Delete, Send, Download — is a text label.

The reasoning: people navigate by shape and position before they read, so after a week a kitchen manager reaches for the box icon without processing the word "Inventory". That is real speed for daily users, and it makes a collapsed mobile navigation bar workable where six text labels would be cramped.

But the benefit is specific to navigation. A pencil meaning "edit" is a convention, not a fact — a volunteer opening the app three times a year may hesitate, and icon-only controls are smaller touch targets, which matters with wet hands in a kitchen. Icons also add visual noise, and noise is much of what made Cloud Console feel unconsidered.

**No icon ever carries meaning alone** — the same rule as colour. If removing the icon makes something ambiguous, the icon was doing work that text should be doing.

Filled and outline styles are never mixed. Active navigation state is carried by background and weight, not by swapping to a filled icon.

---

## 7. Error messages

Plain language about what went wrong and what to do, followed by a short reference code.

```
We couldn't send this purchase order to Govind Wholesale.
Their WhatsApp number may be wrong or unreachable.

You can download the PDF and share it manually.        KMS-4172
```

**Rules:**

- Say what happened and what the person can do next. Never surface a stack trace, exception class, or SQL error.
- No blame, no apology theatre, no exclamation marks.
- Every error carries a **unique reference code** the user can quote to whoever is supporting them. The code maps to the specific failure in the logs, so support can find the exact event without asking the user to describe what they saw.
- Codes are stable and greppable — the same failure always produces the same code.
- The technical detail goes to the logs and to Sentry, keyed by that code. It does not go on screen.

This matters more than usual here: the people hitting errors are temple staff, not engineers, and the person they call for help may be a volunteer with no access to the system. A code turns "it didn't work" into something diagnosable.

---

## 8. Open items

- Empty-state illustration approach undecided. Likely none — plain, well-written text.
- **Dark mode: not doing it.** Decided 2026-08-04. No user demand established, and supporting it doubles the surface area of every subsequent UI story. Revisit only if users actually ask.

---

## 9. Words

Settled 2026-08-21, item 13 and Q12 of the UAT round-2 brief. Before this the document settled
colour, type, spacing, icons and error messages — and not case, so fifty pages each chose. Hints
came out written three ways.

### Case

**Sentence case everywhere.** Buttons, labels, hints, headings, table headers, nav, empty states.

- Capital on the first word and on names only.
- Full stop only on a complete sentence. Under a field, almost never.
- **No ALL CAPS in content.** The sidebar eyebrows stay; they are CSS, not markup.

Title case was expected and argued against: the buttons were already consistent in sentence case, so
title case for labels would have meant re-casing every button or living with the site reading two
ways.

### Grammar and punctuation

- One clause per line of copy. **Twelve words maximum.**
- No semicolons. No em dash where a full stop works.
- One apostrophe character, not two encodings.
- No emoji.

### What to cut

- **Delete** anything that describes what is already on the screen. *"Everyone your temple employs"*
  under a page titled Staff, above a table of staff, tells nobody anything.
- **Delete** reassurance.
- **Keep, cut to one line,** anything that changes what somebody does.
- **Empty states keep one sentence.** An empty screen with no words looks broken.

**Four kinds of text are exempt from the twelve-word ceiling and are never deleted.** They are
tightened only where tightening costs nothing: consent wording on the devotee register; the DPDP
line on PAN; the warning above a ban; and anything stating that money moved or is about to.

### One word per thing

Site-wide, no synonyms:

| Use | Never |
|---|---|
| Staff | Employees |
| Preparation | Dish, item |
| Devotee | User, member |
| Temple | Organisation, tenant |
