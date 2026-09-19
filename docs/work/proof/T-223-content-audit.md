# T-223 — Content audit: Today, Meal planner, Recipes, Vaishnava calendar, Vendor performance

Audit only. No source file was changed. Checked on 2026-09-17 against the working tree.

Rules applied: `.claude/skills/ux-writing` (four standards: Purposeful, Concise, Conversational, Clear;
8–14 words a sentence; 7th–8th grade reading level; no jargon; `[Verb] [object]` buttons;
`[What failed]. [Why]. [What to do].` errors; empty state = title + one line + one button), and
`better-writing` (severity, table format, consistent terms). Audience: temple kitchen staff, many
reading English as a second language, on a phone.

Severity: **HIGH** misleads the user or hides how to recover. **MEDIUM** is over length, off-pattern,
or inconsistent with the rest of the app. **LOW** is polish.

Where a string explains *why* a rule exists, the After drops the reason. It belongs in a code comment.

Coverage: the page and every component it renders, plus the backend `ErrorCode` text the screen can
show, and `formMessages.ts` where a field's name becomes an error sentence. Not covered:
`app/planner/reuse/page.tsx` (the copy screen) and the shared `ShiftFields` form (it belongs to the
Volunteer shifts screen).

---

## 1. Today

`frontend/app/today/page.tsx`

| Severity | Location | Before | After | Why |
|---|---|---|---|---|
| HIGH | today/page.tsx:347 | "{amount} served" (under each dish, once recorded) | "{amount} cooked" | Clear. The figure is what was **cooked** (`actualServings`, entered in the Cooked box). The planner shows the same figure as "cooked" and the eaten figure separately. "Served" states the wrong fact. |
| HIGH | today/page.tsx:274-275 | "Plan a feast and extra volunteers." (any festival up to a month ahead) | Festival that is a feast: "Plan a feast and extra volunteers." Any other observance: nothing after the date. | Clear / Purposeful. `ahead` picks the first day with *any* festival text, including minor observances ("… Disappearance"). Telling the kitchen to plan a feast for those is wrong. Needs the same feast test the calendar uses (`isFeast`). |
| MEDIUM | today/page.tsx:120, :130 | Label "Items below par" · note "above its reorder level" | "Items below reorder level" | Clear: consistent terms. "Par" is jargon, and the same tile's note and the Inventory screen say "reorder level". |
| MEDIUM | today/page.tsx:137, :408 | Tile value "3 · 2" | "3 staff · 2 volunteers" | Clear. Two bare numbers. When meals have a crew count the note shows per-meal figures, so nothing on the tile says which number is which. |
| MEDIUM | today/page.tsx:228-229, :244-245, :274 | "Grains and beans are left out of every meal cooked on it." · "Grains and beans come off every meal on it." · "Grains, dal and beans come off every menu on that day." | One sentence everywhere: "No grains, dal or beans today." / "…tomorrow." / "…on that day." | Clear: consistent terms. Three wordings for one rule, and "are left out" suggests the app removes them (it only hides them in the picker and asks to confirm). |
| MEDIUM | today/page.tsx:589-592 | "It is needed today or tomorrow, so the store has little time to get it ready." | "1 is needed today or tomorrow." / "All are needed today or tomorrow." | Concise. The clause after "so" is the reason; the count is the message. |
| MEDIUM | today/page.tsx:616-619 | "It starts today or tomorrow, or has already started — the roster cannot bend around an answer that comes later." | "1 starts today or tomorrow, or has started." | Concise / Conversational. Reasoning after an em dash; "the roster cannot bend" is an idiom that won't translate. |
| MEDIUM | today/page.tsx:748-767 | Title "2 draft orders are waiting to be sent — today is the last day they can be." Body "A draft holds its ingredients off the shopping list, so one nobody sends stops them being ordered at all." | Title "2 draft orders must be sent today." (or "…are past their order date.") Body "Their items stay off the shopping list until you send them." | Concise. Title is 16+ words with a dash and ends on a dangling "can be". Body explains the mechanism in 20 words. |
| LOW | today/page.tsx:698 | "Book the engineer before it stops in the middle of a festival." | "Book a service visit." | Conversational: tone matches stakes. Dramatic for a routine nudge. |
| LOW | today/page.tsx:433 | "Nobody is down to work today" | "Nobody is rostered today" | Clear: "down to work" is an idiom. |
| LOW | today/page.tsx:77, :295; calendar/page.tsx:129 | "Open planner" · "Open the planner" · "Open the meal planner" | "Open planner" everywhere | Clear: one label for one action. |
| LOW | today/page.tsx:366 | Card meta "Against open purchase orders" | "From orders you have sent" | Clear: "against" is ledger language. |

---

## 2. Meal planner

`app/planner/page.tsx`, `components/planner/MealServices.tsx` (the day's meal blocks, Record actuals, Correct the figures), `components/planner/MealComposer.tsx` (plan / edit a meal), `app/planner/{compose,meal/[id],catch-up,[date]}`, `components/planner/{ShiftLayer,DayView}.tsx`, and the meal `ErrorCode` text.

| Severity | Location | Before | After | Why |
|---|---|---|---|---|
| HIGH | MealComposer.tsx:1151-1153 | Grain warning buttons "Plan it anyway" / "Leave it out" | "Plan it anyway" / "Change the menu" | Clear: button names the action. "Leave it out" only closes the warning; the grain dish stays ticked. A cook will think they removed it. |
| HIGH | ErrorCode.java:81-83 (SERVINGS_NOT_VALID) | "Those servings don't look right." / "Enter how many were actually served, or mark the dish as not made." | "An amount cooked or eaten can't be used." / "Enter 0 or more, or tick Not made." | Error pattern / Clear. The recording form asks for amounts **cooked** and **eaten** in Kg or L, not servings. The error sends the cook looking for a field that isn't there. |
| HIGH | MealComposer.tsx:1902-1920 (ⓘ on "Estimated travel time") | "A good-faith estimate from Google Maps as of now — 35–50 minutes in traffic at that hour. Traffic changes, so it is worked out again when…" (~45 words) | Auto: "From Google Maps: 35–50 minutes at that hour." Manual: "You set this. Google says 35–50 minutes." None: "Minutes to drive there." | Concise: a hint is one sentence. Three sentences of how the number is refreshed and printed. |
| HIGH | MealComposer.tsx:1380-1382 (label + ⓘ) | Label "Once you are there" · hint "The clubhouse, the block, which gate. Kept off the address on purpose — the van is routed to the main entrance…" (~35 words) | Label "Gate or building" · hint "The block, gate or hall the driver should look for." | Clear: the label doesn't say what to type. Concise: the hint explains a design choice. |
| MEDIUM | MealComposer.tsx:1357 (ⓘ) | "Pick from the list where you can — a chosen address is one the map can find, and it is what the travel estimate needs" | "Pick an address from the list so we can work out the travel time." | Concise: 24 words with a dash. |
| MEDIUM | MealComposer.tsx:1337 (ⓘ); ErrorCode.java:470-472 | "Both halves: a contact you cannot ring is not one" · error "…Both: a contact you can't ring isn't a contact." | Hint: remove (the label says it). Error: "Food going out needs a contact." / "Enter their name and phone number." | Purposeful: a saying, not an instruction. |
| MEDIUM | MealComposer.tsx:1171 (ⓘ, editing a meal) | "A meal is its date and its kind, so a correction cannot move it to another one." | "You can't change the meal type. Cancel this meal and plan a new one." | Clear: "kind" and "correction" are system words; tell the cook what to do instead. |
| MEDIUM | MealComposer.tsx:1472 (ⓘ, step 2) | "Optional for an event — the amounts below are what it is planned by" | "Optional for events. You set each dish's amount below." | Concise / Clear. |
| MEDIUM | MealComposer.tsx:1499 (ⓘ, step 3) | "Raise the ones that always run out" | "Increase any dish that usually runs out." | Clear: "raise the ones" reads as an idiom for a non-native reader. |
| MEDIUM | MealComposer.tsx:739-741 | "The food cannot get there in time: ready at 11:00 plus 45 minutes of driving arrives 15 minutes after the guests sit down at 11:30." | "The food arrives 15 minutes late. Make it ready earlier, or change when guests eat." | Error pattern: what failed + what to do. 27 words, arithmetic, no fix. |
| MEDIUM | MealComposer.tsx:748 | "Only 10 minutes between the food being ready and the van having to leave. Please account for loading time." | "Only 10 minutes to load the van. Make it ready earlier if you need more." | Concise: "Please account for" is filler and gives no action. |
| MEDIUM | MealComposer.tsx:1123-1126 (empty state) | "Choosing this temple's recipes comes before planning a meal. Open Recipes in the menu, find the ones this kitchen cooks — the shared library has…" | Title "No recipes yet" · "Add recipes before you plan a meal." · button "Open recipes" | Empty-state template: one line and one button, not directions to the menu. |
| MEDIUM | MealServices.tsx:816, :858, :1046, :1088, :571, :864 | Column "Consumed" · row "… eaten" · box "How much X was eaten" | "Eaten" in the column too | Clear: consistent terms. One figure called "Consumed" and "eaten" on the same row. |
| MEDIUM | MealServices.tsx:1037-1039 | "What this meal was recorded as, and what it should say. The figures on file are in the boxes — change the ones that were wrong." | "Change the figures that were wrong." | Concise. |
| MEDIUM | MealServices.tsx:1129-1131 | "Kept with your name and today's date. It is the only account of why this meal now says something else." | "Saved with your name and today's date." | Concise: the second sentence is the reason. |
| MEDIUM | MealServices.tsx:1135-1137; :608-610 | "The stock drawn against the old figures goes back, and the new figures are drawn in its place. The original recording stays…" · success "The stock drawn against the old figures has been put back…" | Notice "Stock is updated to match. The first recording stays on the meal." · success title only: "Lunch corrected." | Concise / Clear: "drawn against" is ledger language. |
| MEDIUM | MealServices.tsx:1070, :1094, :1120 (via formMessages.ts:56, :65) | Box names become errors: "How much Kosu Palya was actually eaten can be at most 40" · "Why the figures are being changed is required" | aria-labels "Kosu Palya eaten", "Kosu Palya cooked"; label "Reason for the change" → "Kosu Palya eaten can be at most 40" · "Reason for the change is required" | Validation pattern `[Field] [requirement]`: the field name must read as a noun. Same fix for MealComposer.tsx:1594 "How much X to make" → "Amount of X". |
| MEDIUM | MealServices.tsx:555 | "grains on a fasting day, acknowledged" | "Has grains · fasting day" | Clear: lower-case fragment with "acknowledged" is system language. |
| MEDIUM | MealServices.tsx:1440, :1471 | Buttons "Repeat it forward" then "Copy it forward" | "Repeat weekly" then "Repeat" | Clear: one verb for one action; "forward" is vague. |
| MEDIUM | MealServices.tsx:1424 | "3 skipped — a fast falls there that these preparations don't suit" | "3 skipped: fasting days" | Concise / Clear. |
| MEDIUM | MealServices.tsx:1374 | "No travel estimate: nobody has said when the guests eat." | "No travel time. Add when the guests eat." | Error pattern: say what to do. |
| MEDIUM | planner/page.tsx:568 | Week cell "12:00 Event" | "12:00 {event name}" (as Month and Today already do) | Clear: consistent terms. Two events on one day both read "Event" in Week but by name in Month. |
| MEDIUM | catch-up/page.tsx:114-117 | "You are all caught up." + "Every meal of the last week has been written down, and the store room agrees with the kitchen. Thank you — this is the part…" | Title "All meals recorded" · "Stock matches the last week's cooking." · button "Back to Today" | Empty-state (user-cleared) template: one line. |
| MEDIUM | ErrorCode.java:304-306 (MEAL_PLAN_NOT_OPEN) | "This meal can no longer be changed." / "Only a planned meal can be edited or cooked; this one is already cooked or cancelled." | "This meal can't be changed." / "It is already cooked or cancelled." | Error pattern: the second line restates a rule with a semicolon. |
| MEDIUM | ErrorCode.java:614-616 (MEAL_NOT_RECORDABLE) | "…A cancelled meal never went to the kitchen, so there is nothing to record against it." | "This meal was cancelled, so there is nothing to record." | Concise. |
| MEDIUM | ErrorCode.java:510-512 (MEAL_HEAD_COUNT_REQUIRED) | "This meal has something being cooked, so it needs to know how many people are expected." / "…Every preparation is worked out from that number." | "Enter how many people are expected." / "Add adults, children or seniors." | Concise: 17-word headline with reasoning. |
| MEDIUM | ErrorCode.java:500-502 (DELIVERY_CANNOT_ARRIVE_IN_TIME) | "Cook it earlier, serve it later, or check the travel allowance — and leave time to load it." | "Make it ready earlier, or change when guests eat." | Clear: "travel allowance" isn't what the form calls it ("Estimated travel time"). |
| MEDIUM | ErrorCode.java:300-302 (CANNOT_CANCEL_COOKED_MEAL) | "If the stock was wrong, correct it with an inventory adjustment." | "A Temple Admin can correct its figures from the day." | Clear: points to a different screen from the one the planner offers ("Correct the figures"). |
| LOW | MealServices.tsx:809 | "From the job card that came back. Both figures start at the plan — change what differed." | "Enter what the job card says. Both boxes start at the plan." | Conversational. |
| LOW | MealServices.tsx:904; :601 | "Recording draws the ingredients from stock, against what was cooked." | "Saving takes the cooked amount's ingredients out of stock." | Clear: "draws … against" is ledger language. |
| LOW | MealServices.tsx:458, :920 | "Record actuals" opens a form whose button is "Record this meal" | "Record actuals" / "Save actuals" | Clear: same action, same words. |
| LOW | MealServices.tsx:1467 (ⓘ) | "Each week is a copy you can edit or cancel on its own — nothing links them together." | "Each week is a separate copy you can edit or cancel." | Concise. |
| LOW | MealServices.tsx:1209 (screen reader) | "people rostered of the number this meal takes" | "people rostered of the number needed" | Clear. |
| LOW | ShiftLayer.tsx:137; MealComposer.tsx:1667 | Dialog title "Post a shift" opened by "Ask for volunteers"; its note says "Saved when you save the meal." | Title "Ask for volunteers" | Clear: "Post" says it goes out now; it doesn't. |
| LOW | MealComposer.tsx:1804 | "Not saved yet. It is saved with this meal." | "Saved when you save this meal." | Consistent with ShiftLayer.tsx:159. |
| LOW | MealComposer.tsx:1244 (ⓘ) | "The calendar's answer, or your own" | "Filled in from the calendar. You can change it." | Clear. |
| LOW | MealComposer.tsx:1309 (ⓘ) | "What decides whether we need an address" | Remove | Purposeful: adds nothing to "Pickup or delivery?". |
| LOW | planner/page.tsx:306 | Handover "Not said" | "Not set" | Clear. |
| LOW | ErrorCode.java:466-468, :435-437, :482-484 | EVENT_NAME_REQUIRED "…It is how you will find it again." · READY_BY "…Everyday meals suggest one; occasional meals always ask." · ADDRESS_NOT_FOUND "…a landmark and a pin code usually help." | Drop the second clause in each | Concise. |

---

## 3. Recipes

`app/recipes/page.tsx` (list), `app/recipes/[id]/page.tsx` (detail), `components/RecipeForm.tsx` (new and edit), `app/recipes/library/[id]/page.tsx`, `components/RecipePeek.tsx`, and the recipe `ErrorCode` text.

| Severity | Location | Before | After | Why |
|---|---|---|---|---|
| HIGH | recipes/[id]/page.tsx:177-181 | "This removes the recipe, its ingredients and any cards made from it. It cannot be undone. A recipe that has been cooked is archived instead, so the record keeps…" | "This deletes the recipe and its ingredient list. You can't undo this. If it has been cooked, you'll be offered Archive instead." | Clear: it says a cooked recipe "is archived instead", but the app refuses the delete and makes you press "Archive it instead". "Cards" is unexplained. |
| HIGH | RecipeForm.tsx:19, :380 | Label "How often it is cooked" · options Everyday, Moderate, Festival, Sustainable, Economical | Needs Rajeev: either label "Recipe label" with these options, or keep the label and offer only frequencies (Everyday, Weekly, Festival) | Clear: "Sustainable" and "Economical" are not how often something is cooked. The label and its options disagree. |
| MEDIUM | recipes/page.tsx:183-186 | Title "Imported ingredients arrive unflagged for Ekadashi" · "A recipe import adds any ingredient this temple doesn't have, and can't tell which are restricted on a fast day — so it flags none…" | Title "Check imported ingredients for Ekadashi" · "Imports can't tell which are restricted. Set the Ekadashi flag on each, or the planner will allow them." | Concise: a notice is one sentence plus the action. **The source comment says these are Rajeev's words, approved 2026-09-08, "Do not reword them". Needs his say before it changes.** |
| MEDIUM | recipes/page.tsx:221-223 | "An import creates any ingredient a recipe needs that this temple doesn't already have, and picks its category and unit itself. Check each one…" | "Check the category and unit of each one." (button "Review them" stays) | Concise. |
| MEDIUM | recipes/page.tsx:247-249 | "No recipes found" / "Recipes added to your temple will appear here." (no button) | Search: "No recipes match '{q}'" · "Try another name." First use: "No recipes yet" · "Add one, or pick from the shared library." · button "New recipe" | Empty-state template: name the query; give one button. |
| MEDIUM | recipes/[id]/page.tsx:404; library/[id]/page.tsx:164; RecipeForm.tsx:428 | Heading "Catering" · field "Catering note" | "For large batches" · "Large-batch note" | Clear: catering has been taken out of the product. The word now points to a feature that doesn't exist. |
| MEDIUM | RecipePeek.tsx:215; recipes/[id]/page.tsx:210; MealComposer.tsx:1510 | "Suits a fasting day" · "Ekadashi-friendly" · "suit the fast" | "Ekadashi-friendly" everywhere (the error text already uses it) | Clear: one term per thing. See cross-app list. |
| MEDIUM | library/[id]/page.tsx:84 | Button "Add" | "Add to my recipes" | Buttons `[Verb] [object]`. |
| MEDIUM | recipes/[id]/page.tsx:118 | Fallback error "That didn't work." | "Couldn't finish that. Try again." | Error pattern: no fix. Shown when scale, translate, PDF, archive or restore fail without a server message. |
| MEDIUM | RecipeForm.tsx:285 | Label "Yield note" | "Makes (in words)" (placeholder "300 idlis (3 per devotee)" stays) | Clear: "yield" is the system word the form already replaced with "This recipe makes". |
| MEDIUM | RecipeForm.tsx:388 | "Indicative cost (₹)" | "Rough cost per batch (₹)" — confirm the unit with Rajeev | Clear: "indicative" is jargon, and it doesn't say cost of what. |
| MEDIUM | ErrorCode.java:628-630 (RECIPE_IN_USE) | "Archive it instead — it will stop appearing when you plan a meal, and the record of what was cooked stays intact." | "Archive it instead. It will stop appearing in the planner." | Concise: 20 words with a dash. |
| LOW | recipes/[id]/page.tsx:345 | Button "Original" | "Show original" | Buttons `[Verb] [object]`. |
| LOW | recipes/[id]/page.tsx:214 | "Hindi · via Bhashini" | "Hindi · machine translation" | Clear: a service name means nothing to a cook. |
| LOW | RecipeForm.tsx:361, :408 | "Region tag" · "Start" | "State or region" · "Before you start" | Clear. |
| LOW | RecipeForm.tsx:306, :309 | "That is very few people for one batch — check the amounts and units." | "Very few people for one batch. Check the amounts and units." | Conversational: drop the dash. |

---

## 4. Vaishnava calendar

`app/calendar/page.tsx`, `lib/vaishnava-day.ts` (day notes and labels), `components/planner/DayView.tsx`.

| Severity | Location | Before | After | Why |
|---|---|---|---|---|
| HIGH | lib/vaishnava-day.ts:118-126 | On every day with a fast of any kind: "Fasting day: no grains, no dal, no beans. Cook sabudana, potato, peanut, fruit and buckwheat, and plan roughly a third of the usual number of servings." | Ekadashi: "No grains, dal or beans. Cook sabudana, potato, peanut or fruit. Plan about a third of usual servings." Other fasts ("Fast until noon" etc.): "{Fast until noon}. Plan the feast for after." — confirm with Rajeev | Clear: `dayKind` returns "fast" for a noon or sunset fast too, so an appearance day that ends in a feast tells the kitchen to cook a third of the food and no grains. |
| MEDIUM | calendar/page.tsx:205; lib/vaishnava-day.ts:104, :154 vs calendar/page.tsx:480, :485, planner, recipes | "Ekadasi" (legend, labels) beside "Ekadashi" (checkbox, field, planner, recipes) | One spelling. See cross-app list. | Clear: consistent terms. Both spellings appear on the same panel. |
| MEDIUM | calendar/page.tsx:331, :355, :693 | "Not computed for this temple yet" · "This temple's calendar has not been computed this far ahead." · "Nothing computed for this year yet." | "The calendar doesn't reach this date yet." · "…this year yet." | Clear: "computed" is system language. DayView.tsx:99 already says it plainly. |
| LOW | lib/vaishnava-day.ts:77 | "Vyanjuli Mahadvadashi — fasting from grains and beans, and the parana window is short" | "Vyanjuli Mahadvadashi. Break the fast early; the window is short." | Purposeful: says the window is short but not what to do. |
| LOW | calendar/page.tsx:470-471 | "Correct it only when you know it to be wrong here. Everyone will see the correction, and why." | "Only correct a date you know is wrong. Everyone will see your change and reason." | Conversational. |
| LOW | calendar/page.tsx:354 | "An ordinary day. The standard menu." | "No festival or fast. Cook the usual menu." | Conversational: two fragments. The planner says "No festival or fast on this day". |
| LOW | calendar/page.tsx:673 | "12 marked days in 2026" | "12 festivals and fasts in 2026" | Clear: matches the card title. |
| LOW | lib/vaishnava-day.ts:139 | "Higher darshan attendance than an ordinary day. Add about a fifth to the lunch count." | "More people come for darshan. Add about a fifth to lunch." | Conversational. |

---

## 5. Vendor performance

`app/vendor-performance/page.tsx`.

| Severity | Location | Before | After | Why |
|---|---|---|---|---|
| HIGH | vendor-performance/page.tsx:354-392 | "On time is scored item by item. Each thing on an order counts how much of it was there on or before the day it was needed, so eight of ten…" (~330 words, one paragraph) | **How these are worked out**<br>• **On time:** items that arrived by the needed-by date. 8 of 10 = 80%.<br>• **Filled:** how much of the order arrived in the end.<br>• Counts sent orders whose needed-by date has passed.<br>Left out: {n} sent inside the vendor's notice period · {n} the vendor made right · {n} with no needed-by date. | Concise: a paragraph nobody reads in a hot kitchen. It explains why each rule is fair, which belongs in a code comment. Title changes from "What these figures count". |
| MEDIUM | vendor-performance/page.tsx:89 | "Whether each supplier delivers when they said, brings what was ordered, and what is still outstanding with them." | "On-time delivery, fill and open orders for each vendor." | Concise: 19-word subtitle. |
| MEDIUM | vendor-performance/page.tsx:89, :103, :107 | "supplier" (subtitle, loading, empty state) beside "Vendor" (title, column, footer) | "vendor" | Clear: the app says vendor about 590 times and supplier about 40. |
| MEDIUM | vendor-performance/page.tsx:107-108 | "No orders with any supplier in this period" / "Send a purchase order and record what arrives, and this will say who delivers on time." (no button) | "No orders in this period" · "Figures appear once orders are sent and received." · button "Open purchase orders" | Empty-state template: one line and one button. |
| MEDIUM | vendor-performance/page.tsx:164 | Column "Fill rate" | "Filled" | Clear: "fill rate" is procurement jargon. |
| MEDIUM | vendor-performance/page.tsx:246-247 | "across 12 lines" | "across 12 items" | Clear: the On time column beside it counts "items". Same thing, two words. |
| MEDIUM | vendor-performance/page.tsx:478, :483 | "2 1–30 days overdue · 1 31+ days overdue" | "2 up to 30 days late · 1 over 30 days late" | Clear: the count and the range run together ("2 1–30"). |
| LOW | vendor-performance/page.tsx:445-458 | "1 order we sent late — not counted" · "1 order they made right — not counted" | "1 sent late (not counted)" · "1 made right (not counted)" | Concise. |
| LOW | vendor-performance/page.tsx:103 | "Reading what each supplier delivered…" | "Loading vendor figures…" | Clear. |

---

## Counts

| Screen | HIGH | MEDIUM | LOW | Total |
|---|---|---|---|---|
| Today | 2 | 6 | 4 | 12 |
| Meal planner | 4 | 24 | 11 | 39 |
| Recipes | 2 | 10 | 4 | 16 |
| Vaishnava calendar | 1 | 2 | 5 | 8 |
| Vendor performance | 1 | 6 | 2 | 9 |
| **All** | **10** | **48** | **26** | **84** |

Verdict: **Block**. Ten HIGH findings remain. Five of them state something untrue: "served" on
Today, "Leave it out" in the planner, the servings error, the recipe delete warning, and the
calendar's fasting note.

## Cross-app term changes that need Rajeev's say

Not rewritten above without asking, because each one appears on screens outside this audit.

1. **Ekadasi or Ekadashi.** About 9 strings say "Ekadasi" (the calendar legend, fast labels, the Today
   "ahead" fallback in `TodayService.java:291`, stored GCAL names) and about 16 say "Ekadashi"
   (planner, recipes, ingredients, errors). Stored names like "Pavitraropana Ekadasi" come from the
   calendar engine and would need converting on display.
2. **One name for "safe on a fasting day".** "Ekadashi-friendly" (recipe detail, the grain error),
   "Suits a fasting day" (recipe peek), the "Ekadashi flag" (ingredients), "fasting-compatible" (the
   field name). Suggest "Ekadashi-friendly" everywhere.
3. **Eaten or Consumed.** The recording form's column says "Consumed" and the meal row says "eaten".
   Check Cost per serving and the job card PDF before choosing.
4. **Vendor or supplier.** "Vendor" is the app's word. About 40 "supplier" strings remain across other
   screens.
5. **Catering.** The recipe fields "Catering note" and heading "Catering" came in with the library data.
   Catering has been taken out of the product, so the field needs a new name or should go.
6. **"Items below par".** Only Today uses "par". Everywhere else says "reorder level".
7. **Rajeev's approved wording on the Recipes Ekadashi warning.** The source says not to reword it. It's
   over the notice length, so it needs his decision.

Deliberately not flagged: the "We couldn't …" fallback errors (in 77 files). `better-writing` says to
avoid "we" in errors, but the `ux-writing` error template recommends exactly "We couldn't save". Now
that `ux-writing` leads, they stand.
