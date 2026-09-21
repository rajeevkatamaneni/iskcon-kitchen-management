# Design System

**Status:** v1.16 — a text link is body ink with an underline at rest and takes its colour only on hover, because how far the accent stands out from body text is the temple’s choice and in one pack it does not at all, 2026-09-21 (§4). v1.15 — a record is opened from its list by its name, gets a screen of its own and is edited behind Edit with Save and Cancel, however few fields it has, replacing the five-field rule; applies to what is already built, 2026-09-20 (§4). v1.14 — price-trend arrows are the one written exception to the colour rule: red for a price that went up, green for one that went down; and the layout rules, tables included, state intent to follow by their logic rather than word for word, 2026-09-19 (§2, §5). v1.13 — secondary buttons are raised (style E) so they read as buttons, the press lives on the base button style so every button has it, and the recipe page's download icon is allowed, 2026-09-19 (§4, §6). v1.12 — every table reads left, no column is cut off, and spare width is shared evenly between the columns, replacing v1.11's table rule, 2026-09-18 (§5, *Tables*). v1.11 — how every table lays out its columns, and what a table becomes below 1024px; festivals get their own saffron colour, 2026-09-18 (§2, §5, *Tables*). v1.10 — what each status colour means, restated as one rule, and the sidebar becomes a drawer below 1024px rather than 768px, 2026-09-18 (§3, §5). v1.9 — nothing is a pill: badges, chips and controls take the buttons' corner, 2026-09-17 (§4). v1.8 — which forms get a screen of their own, restated: five fields, and a record from another part of the app opens as a layer, 2026-09-12 (§4). v1.7 — the `danger` row stopped naming a deleted feature, 2026-09-11 (§3). v1.6 — what lifts under the pointer and what only changes tone, 2026-08-30 (§4). v1.5 — colour became the temple's choice, and the focus ring got a token of its own, 2026-08-28 (§2, §4). v1.4 — the accent darkened to clear AA on button text, and the words and the geometry of a form settled, 2026-08-21 (§2, §4, §9). v1.3 — contrast made a floor and badges set in semibold, 2026-08-20 (§2, §3). v1.2 added the `info` family and moved Ekadasi onto it, 2026-08-19. v1.1 revised the palette to terracotta/charcoal, 2026-08-10 (§2). v1.0 established 2026-08-04, before the first UI story (E1-S6). See CHANGELOG for each.
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
| Donation, wish list | Signed-in devotees, occasionally | Clarity absolute. Craft here means speed and trust, never cleverness. |

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

Never decorative. **Each colour has one meaning, and anything that does not meet it is not coloured.** Rajeev's rule, 2026-09-18: *"Amber MUST be a warning and RED MUST be something that is serious and needs immediate attention. Green is GOOD but that is only reserved for when an action taken by the user yields a result they were expecting."* **Warning is gold, not orange**, so it never reads as the terracotta accent.

| Role | Fill | Text | Meaning here |
|---|---|---|---|
| `danger` | `#F7E7E3` | `#9B2C1F` | Serious, and needs attention now. An overdue invoice, an order that will not arrive in time, a message that failed to send, expired stock, a refusal from the server, a template Meta refused, a real deletion. Also everything that says "fix this before it can be saved": a field error and a form-level message about the same kind of fault are both red. |
| `warning` | `#F4EAD1` | `#87641A` | The reader should act, or take care. Low stock, expiring soon, a meal short of crew, "order today" wherever it appears (there is still time), a request waiting for an answer, a setup step not done yet, a staff member's hours changed. Nudged from `#8F6A1C` on 2026-08-20: it sat at 4.13 on its own wash, just under the 4.5 a badge needs. |
| `success` | `#E7EFE8` | `#3E6B48` | Only the confirmation that the reader's own action did what they expected: "Saved.", "Invoice recorded", the notice after recording a meal, a setup check straight after the administrator pressed Test and it passed. Never a standing state — paid, received, active, approved, covered, recorded and a setup check that is simply working are neutral. |
| `info` | `#E5F2FD` | `#326086` | Standing information worth reading: a fasting day, a settled record explained, a locked state and the way on. Also Ekadashi's mark in the planner and calendar (v1.2). |
| neutral | `sunken` | `ink-secondary` | Everything else: ordinary states, history, labels. The `Badge` default. |

A state that is good is not a success. It is what the reader expected, and colouring it green on every list teaches them to ignore the green that means their own save worked. A settled outcome, good or bad, is neutral: a declined leave request is not an emergency and an approved one is not a result of what the reader just did. A calendar fact is information, not a warning, even when it changes the menu: the warning comes when someone picks a grain dish on a fasting day, not when the day is shown. A confirmation that asks nothing dangerous is not red either: withdrawing a leave request is minor and can be asked again, so its confirm is the ordinary primary button, and red stays for real deletions.

*Amended 2026-09-18 (v1.10), on Rajeev's rule quoted above, given in chat that day. Until then the lead sentence read "If one of these appears, something is genuinely low, wrong, overdue, or complete", and the meanings read: `danger` "Overdue invoice, rejected delivery, a purchase order past its order-by date"; `warning` "Low stock, expiring soon, under-filled shift"; `success` "Paid, received, shift fully staffed". The `info` and neutral rows are new. The colour values did not change. T-227 audited every coloured element against the rule and recoloured the ones that broke it.*

**The one exception: price-trend arrows.** Beside a vendor's list price (procurement requirement R-VEN-3), a price that went up shows a red up arrow and a price that went down shows a green down arrow. A price that did not change keeps its neutral dash, and nothing is shown when there is no previous price to compare with. Red is right for a rise because it costs the temple money; green for a fall is a looser fit, because the reader did nothing to earn it, and it is allowed here and nowhere else. No other place may use red or green for a direction, a trend or a comparison. As everywhere, the colour is never the only signal: the arrow's shape and its spoken name ("Up from ₹60 on 12 Sept") say the direction in words.

*Added 2026-09-19 (v1.14), Rajeev's decision on the Decisions Desk (question Q-4). His answer: "Yes, red up and green down, written into the design system". The arrows were already built this way (`components/PriceTrend.tsx`); this writes the exception down.*

### Accessibility

All text/background pairs meet WCAG AA (4.5:1 body, 3:1 large). Status is **never** conveyed by colour alone — every badge carries text, because kitchens are bright, screens are cheap, and roughly 1 in 12 men has some colour vision deficiency.

**A temple's choice is a choice about taste, and it cannot become a choice about legibility.** That is not a hope about how the packs were picked — it is a contract, and it is checked.

`tools/theme/build_theme_pack.py` holds the **thirty-four pairings** this interface actually puts in front of somebody, each with the floor it has to clear: body text on all three surfaces, the button label on its fill at rest and on hover, the accent as text on its own wash, the focus ring on all three surfaces, and each status colour on both its own wash and the page. Every lightness in a pack is *solved* against those floors rather than chosen and then tested, working in OKLCH and giving up chroma before it gives up contrast. A pack that fails one pairing does not build — and `__tests__/theme-contract.test.ts` runs the same thirty-four against every pack in the catalogue on every commit, so a pack that fails one does not ship either, including one edited by hand after the tool produced it.

This matters because it is precisely how the two contrast failures in this project's history happened. `ink-muted` in August and the focus ring above were both introduced by somebody picking a colour they liked and not thinking to check a pairing. Fifteen packs is forty times more opportunity to do that, and no amount of care survives it — so the care is in the tool.

Two consequences fall out of the contract, and both are deliberate:

- **Status hues are fixed across every pack.** Red means wrong and green means done in every palette. A temple that could recolour those could make its own interface lie. Their *saturation* barely moves between families either (0.13–0.17, against 0.075–0.185 for the accent) — a muted pack is muted in its accent and its surfaces, not in its alarms.
- **Vibrancy comes from chroma, not lightness.** A pale bright yellow cannot be a primary fill in a light interface, because nothing white enough to be "inverse" can be read on it. A saturated blue at the same lightness can, and reads every bit as vivid.

---

**Festival (saffron).** Festivals and feasts on the Vaishnava calendar and the planner wear their own colour, saffron. It is not a status colour, because green means only that the person's own action succeeded, amber only a warning, and red only something serious (Rajeev, 2026-09-18). It is not the accent either, which on the calendar means a fasting day. Three tokens, in every pack: `festival-bg` is the tint behind a festival day's cell or chip, a pale saffron-yellow; `festival` is the solid dot beside the name and the event's left rule, a deep saffron-orange; `festival-text` is the festival's name set as text, on the tint or on the page. Floors: ink, ink-secondary and ink-muted at 4.5:1 on `festival-bg`; `festival-text` at 4.5:1 on `festival-bg`, canvas, raised and sunken; `festival` at 3:1 on `festival-bg`, canvas, raised and sunken. The Badge component has a `festival` tone. Calendar legend: blue for Ekadashi, accent for a fasting day, saffron for a festival or feast, muted grey for an observance. *(Added in v1.11, 2026-09-18, Rajeev choosing saffron over merging festivals into the accent.)*

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

**Radii:** nothing is a pill. Every badge, status chip, count chip, segmented control, tab list, button (built on `Button` or by hand), input and read-only value box takes the theme's **control corner**, `rounded-control` (`--radius-control`, 5px in the default pack), which is the buttons' own corner. A round status beside a square button reads as two design languages on one card; one corner for every small thing keeps them one. Full rounding (`rounded-full`) is kept only where a circle is the shape itself — initials, step numbers, the "i" hint, calendar dots and the today circle — and for meter bars, which are a line rather than a box. Cards use the **card corner**, `rounded-card` (`--radius-card`). Notice and alert boxes and card-like containers still use the older fixed steps (`sm` 8px, `DEFAULT` 12px, `lg` 16px); they were not part of this amendment and are left for a separate decision.

*Amended 2026-09-17 (v1.9), approved by Rajeev after comparing each element as it was and squared off. Until then this line read: "`sm` 8px (inputs, badges), `md` 12px (buttons, small cards), `lg` 16px (panels, main cards), `pill` 9999px (status chips only)."*

Cocoon uses 50px on section cards. Beautiful on a marketing page, wrong for an application — at the size of an inventory row it turns everything into a lozenge and costs usable width. 16px keeps the softness at our scale.

**Motion:** 120ms for a press, 150ms for state changes, 200ms for entrances, `ease-out`. Nothing
longer. Deliberately no blur-and-slide navigation like Stripe's — it is a marketing pattern for 40
destinations, it does not exist on touch, and it feels sluggish on a mid-range Android. Navigation is
a persistent sidebar, and moving between pages is not animated.

Answering the pointer is a different thing from moving between pages, and it is allowed. What moves,
and what does not, is settled below.

**Shadows:** the pack decides. `shadow-card`, `shadow-raised` and `shadow-overlay` are raw CSS a
theme carries, and a pack that asks for no shadow gets none — depth then comes from surface tone, as
it did when terracotta was the only palette. The focus ring is the exception and is never a pack's
to remove.

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

Settled 2026-08-21, Q1; restated by Rajeev 2026-09-12 (v1.8). **A record opened from its own list
gets its own screen** — pressing a vendor in Vendors, or New on Purchase orders, opens the form as a
screen of its own. `FocusScreen` carries the pattern so a screen cannot half-follow it.

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

**Where it stops: a record, not a field count.** Anything the application keeps a record of — an
ingredient, a supply, an inventory item, a vendor, a vendor's price for a supply, a kind of meal, a
festival occasion, a member of staff — is opened from its list **by pressing its name**, gets a
screen of its own showing everything about it, and is changed from there: **Edit**, then a form with
**Save and Cancel**. However few fields it has, and with no Edit button in the row. Inline editing
survives only where there is no record to open — a cell in a working table that exists for the
length of one task: a shopping-list line, a delivery's received quantity, a roster's attendance
mark.

**A record from another part of the app opens as a layer.** Sometimes a screen needs a record that
belongs somewhere else — a volunteer shift raised from the meal planner. That record's own screen
then opens as a layer over the screen you are on: the same form, never a cut-down copy, so the two
cannot drift apart. Saving or Cancel closes the layer and leaves you where you were, with anything
open on the page still open, and the screen underneath then shows the record so it can be opened
again to view or edit.

*Note: the field count was replaced on 2026-09-20 (v1.15), on Rajeev's instruction: "Inventory,
remove the edit button and move the functionality the current edit button provides into the edit
screen. When the user clicks on the Ingrident Name, it open in the view mode, then they see the edit
button, Click on that and it goes to the edit screen wchi shows save and cancel. The same pattern
should be applied to Inventory also." Unlike the change below, this one applies to what is already
built — Inventory, Ingredients, Supplies and Staff were converted the same day.*

*What it replaced: "Where it stops: five" — a form of four fields or fewer was edited inline, five
or more became a screen. That rule was itself the change of 2026-09-12 (v1.8), from "four fields or
more becomes a screen" and "anywhere a button used to open a form on top of a list, the form is its
own screen"; Rajeev asked then that it not be applied retroactively — "Just update the rule for the
future. DOESNOT have to be applied retroactivly."*

### A link is underlined, and its colour is not the signal

Settled 2026-09-21, measured across all fifteen theme packs rather than judged on one.

**A text link is body ink with a 1px underline, offset 3px. Colour arrives on hover, not before.**
The class is `.link` in `globals.css`, and it is the only place a text link is styled.

**Why the colour stopped being the signal.** A link used to be `--accent-text` with the underline
arriving on hover, so at rest colour and weight were the whole signal. How far `--accent-text`
stands out from the body text is the *temple's* choice, not ours, and across the packs it ranges
from 4.2x more colourful than the surrounding ink down to **1.9x in Graphite**, whose own
description is "flat neutral greys throughout". At 1.9x a link and a sentence are the same grey.

**Luminance contrast does not catch this, which is why it was nearly missed.** Every pack scores
between 2.07 and 2.44 for link-against-body-text, so by that number they all look equally poor —
yet Kumkum, at 2.09, is bright red on near-black and unmistakable. The measure that separates them
is OKLCH chroma, and on that measure fourteen packs are fine and one is broken.

**So the rule is not "fix Graphite".** Colour-as-signal means every pack has to be checked one at a
time, and a temple that picks a bad one gets an invisible link with nobody noticing. An underline
survives any palette, so a pack can be added without anyone auditing its links. Rajeev chose this
over fixing the single pack, knowing the cost, which is that tables carry more rule than before.

**What is not a link for this purpose.** Buttons, cards, sidebar rows and tiles are anchors too, and
underlining them would be wallpaper — the rule is a class on the 46 text links, never `a { }` on the
element. `--accent-text` keeps its other work: badges, chips, the sidebar eyebrow, avatar initials
and the active nav row, none of which are links.

**Where a link carries its own meaning, that meaning wins.** A banned former employee's name stays
in the danger ink, because which record this is matters more than which link colour.

### What lifts, and what only changes tone

Settled 2026-08-30, watching all three on the live site rather than in a diff.

**Things you pick up lift; surfaces you read do not.** A tile and a sidebar item are discrete
objects with space around them, hovered deliberately and once, on the way to a click. Under the
pointer they rise `translate-y-0.5` — two pixels — and take the pack's `shadow-raised`. That is the
whole gesture: no scale, no colour beyond the tone step they already had.

**A table row only changes tone.** `hover:bg-sunken` on every `<tr>` in every `<tbody>`, read-only
and editable alike. One step. No border, no lift, and no cursor change on a row that is not
clickable.

Rows were tried lifted, on the live site, and the reasons not to are not aesthetic:

- A table is a continuous surface, not a set of objects with gaps. A lifted row throws its shadow
  onto the text of the rows above and below — the exact numbers somebody is reading.
- Row hover fires dozens of times a screen while the eye is scanning. That is the tier where motion
  has to be near-imperceptible or absent, and two pixels of travel on a line of figures is neither.
- It cannot even be drawn cleanly as the tables stand. Under `border-collapse: collapse` the
  separators belong to the table rather than to the row, so a row's shadow cannot paint over them
  and the lift renders as two hard dark lines. Making it work would mean `border-collapse: separate`
  and re-doing the separators in all two dozen tables, to make a row behave like something it is
  not.

**A press is felt, not watched.** Every button scales to `0.96` for 120ms while it is held. It is
the one piece of motion on a control that is hit hundreds of times a day, and it earns that by being
the only confirmation a touch device gives that the tap landed at all.

**The press lives on the base button style, `.btn`, not on the `Button` component.** While held, a
button scales to the `press` token (0.96) and drops 1px, over the `press` duration (120ms, `ease-out`),
and springs back on release. A disabled button does not move. With reduced motion asked for, it does
not move either and keeps only the inset shade a pressed button already had. Any button drawn with
the `btn` class gets this, whether it comes through `Button`, `ButtonLink` or is written by hand.
A button that uses neither does not, so a new button is built on `btn`.

*Added 2026-09-19 (v1.13), Rajeev's decision on the Decisions Desk. Until then the press was written
into the `Button` component alone, and about sixty hand-made buttons across twenty-nine screens never
pressed.*

### Secondary buttons are raised

Every secondary-level button, the neutral second action (`Button variant="ghost"`, `.btn-secondary`)
and the quiet one (`Button variant="secondary"`, `.btn-quiet`, as on "Ask for volunteers") alike,
takes one look: the card colour (`raised`) as its fill, dark `ink` text, a 1px `ink-muted` border with
a 2px `ink-secondary` bottom edge, and a small shadow, like a key. The same corner
(`rounded-control`) and the same 44px height as the primary button. Under the pointer the fill steps to
`sunken` and the shadow grows a little. Keyboard focus shows the focus ring. The primary, danger and
warning buttons are not affected.

Floors, measured in all 15 packs: text at least 4.5:1 on the fill, at rest and under the pointer;
the border at least 3:1 against the card and against the grey `sunken` boxes buttons sit beside.

*Added 2026-09-19 (v1.13). Rajeev chose this look, style E, from a mock of eight (T-238), because
neither secondary look read as something to press: the quiet one was accent text in a pale accent
hairline with no fill, and the neutral one a white fill in a pale grey hairline, and neither edge
reached 3:1 against the card. The glossy packs' gradient on the white button and the frosted packs'
blur are given up for it, as the mock showed.*

Not shipped: `hover:bg-raised/60` on rows, which is what the first eleven palettes made invisible.
It assumed `raised` was darker than `canvas`, which is true of terracotta and false of most of the
packs that followed — Terracotta's own `raised` is `#FEFEFF` on a `#FFF7F4` canvas. `sunken` is the
token that means *recessed* in every pack by construction, which is why it is the one used.

### A confirmation clears itself

A warning, an error or a piece of standing context stays until it is dealt with; every one of those
has something in it for the reader to do. A confirmation does not — the thing it confirms has
already happened — and it goes on sitting at the top of a list somebody is now working down. Pass
`autoDismiss` to `InlineNotice` on those and they fade after five seconds. It is honoured on
`success` and on the neutral `info`, and ignored on `warning` and `danger`.

---

## 5. Layout

**These rules state intent, not wording to be met letter by letter.** This covers the whole section, *Tables* included. When a rule can't be met literally, follow its logic: readable, nothing hidden, no dead space. Then choose the result that comes closest, the lesser evil.

*Added 2026-09-19 (v1.14), Rajeev's answer on the Decisions Desk (question Q-18), as the conductor relayed it: the table rule is "a guideline to think smart, NOT a rule set in stone… be faithful to the logic… if it is not possible exactly, stay as close to it as possible, pick the lesser of the evils and build it the best way possible… change [the rule] and mention it is the logic that is to be taken, not the literal rule word for word."*

**Sidebar 280px** (Stripe docs' value), fixed, with the working area filling the remainder. Below 1024px (Tailwind's `lg`) it collapses into a drawer opened from a menu button, so a portrait tablet gets the drawer and the working area keeps its full width.

*Amended 2026-09-18 (v1.10), on Rajeev's instruction the same day that the app be fully responsive with a collapsible menu. Until then this line read: "Collapses to a bottom bar or drawer under 768px." The menu had already been built to switch at `lg`, so the line described neither.*

**Content max-width 1200px** for dense screens; 640px for forms and reading. Full-width tables of a kitchen inventory are unreadable on a wide monitor.

**Touch targets:** minimum 44×44px. Non-negotiable — wet hands, phones, bright light.

### Tables

*v1.12, Rajeev's instruction of 2026-09-18 via the Decisions Desk. It replaces the rule of v1.11,
approved earlier the same day, which put short columns in a right-aligned group on the right and left
the spare width between the groups. His screenshot of the Shopping list showed what that produced:
Include, Ingredient and Why well spaced on the left ("that is how I want ALL the tables"), then a wide
empty block, then On hand, Suggested and Order by squeezed together so tightly that a quantity box
showed "2792" cut off.*

For every table in the application:

1. **Everything is left-aligned.** Every column, numbers included, and every header aligned the same
   as its cells. Quantities, money and counts keep tabular figures.
2. **Nothing is cut off.** Every column is at least as wide as its widest content: no truncation, no
   ellipsis, no clipping. That includes input boxes: a box must be wide enough to show its value
   ("2792" and its unit). Text wraps onto a second line only when the table genuinely lacks room.
3. **No dead block of empty space.** Width the columns do not need is shared out evenly between the
   gaps separating the columns, so the columns spread across the table's full width with the same
   generous space between each pair.
4. **Even edges.** 20px of padding at the left and right ends of every row, 12px either side of every
   other cell. The last column takes no share of the spare width, so its content ends 20px from the
   right edge just as the first column's starts 20px from the left.
5. **The actions column has no visible header.** The buttons say what they are. The word "Actions" is
   kept for screen readers only.
6. **Below 1024px** (Tailwind `lg`, where the sidebar becomes a drawer), each row becomes a compact
   card. Line one is the name, then any other text value (such as the category). Line two is the short
   values, then the buttons at the far edge. A short value that would be a bare number without its
   heading carries a small label in front of it ("On hand 12 kg"). The page never scrolls sideways.

**In code** the rule lives in `frontend/components/ds/table.ts` and the `.kms-table` block of
`frontend/app/globals.css`. Each column is still marked as text (`TH_PRIMARY`, `TH_SECOND`), a short
value (`TH_FIXED`, `TD_FIXED_NUM`), the leading reference or tick box (`TH_LEAD`) or the actions
(`TH_ACTIONS_FIXED`). The mark no longer decides alignment or position; it decides only whether the
column may wrap when the table is short of room, and where the value goes on a phone card. CSS cannot
share width evenly between columns, so `table.ts` measures each column and sets the widths. A number
box in a table grows to fit its figure.

**One table keeps its own layout:** the staff schedule's week grid, which Rajeev exempted on
2026-09-18 as a calendar whose seven days must be equal. Its columns already read left.

---

## 6. Icons

**Tabler**, outline only, one consistent stroke weight. Around 5,800 icons, MIT licensed.

**Navigation only, and never without text.** Icons appear in the sidebar and in genuinely universal affordances (search). Every action elsewhere — Edit, Delete, Send, Download — is a text label.

**One exception: the recipe page's download.** On a recipe, the small download icon beside the language picker, which saves the recipe as a PDF in the language chosen, may stay an icon. It carries a spoken name and a tooltip ("Download recipe as PDF") and is a full 44px touch target. Nowhere else.

*Added 2026-09-19 (v1.13), Rajeev's decision on the Decisions Desk (question 8). The icon was already built that way; the rule is changed to match it rather than the icon to match the rule.*

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

You can download the PDF and share it manually.        KMS-500002
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
