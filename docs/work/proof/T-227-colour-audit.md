# T-227 part 1 — colour audit (audit only, no source edited)

Audited 2026-09-18 against Rajeev's rule of the same day:

> "Amber MUST be a warning and RED MUST be something that is serious and needs immediate attention.
> Green is GOOD but that is only reserved for when an action taken by the user yields a result they
> were expecting."

Everything else is `neutral` (Badge default, `bg-sunken text-ink-secondary`) or `info` (InlineNotice
default, the blue family DESIGN_SYSTEM v1.2 added for Ekadashi).

**Method.** `grep -rnE 'tone=|tone:|"(success|warning|danger|info)"|(text|bg|border|fill)-(success|warning|danger|info|green|amber|red|…)'`
over `frontend/app`, `frontend/components`, `frontend/lib` (421 hits), then each hit read in context.
Excluded as not a colour decision: `lib/theme-packs.ts` and `lib/theme.ts` (they define the tokens),
`components/ds/*` (the primitives themselves), `Card tone="canvas|sunken"` (surface, not status),
`lib/session-timeout.ts` (`"warning"` is a state name; the idle modal is uncoloured). Backend PDFs
checked: the job card and PO sheet are ink-only; the work order's `.shortfall` chip is the only
status colour in print. InlineNotice with no `tone` is `info` and Badge with none is `neutral`, so
those are not listed.

Line numbers are frontend-relative and taken while another agent was editing wording; they may have
drifted a few lines. The element text is the reliable anchor.

DESIGN_SYSTEM §3 "Semantic — status only" says `success` means "Paid, received, shift fully
staffed". The rule contradicts that row, so the doc needs amending along with the code.

## CHANGE (61)

| file:line | element / text | now | proposed | reason |
|---|---|---|---|---|
| lib/vaishnava-day.ts:142 | `kitchenNote` "Fasting day: no grains, dal or beans…" (shown on Calendar day panel) | warning | info | An auspicious day is information; the temple cannot forget it |
| app/today/page.tsx:222 | Banner "Today is a fasting day (…). No grains, dal or beans today." | warning | info | The trigger for T-227 |
| app/today/page.tsx:238 | Banner "Tomorrow is a fasting day…" | warning | info | Same |
| app/today/page.tsx:266 | Look-ahead "X Ekadashi on …, in N days" (fast branch) | warning when fast | info always | Same; festival branch already info |
| app/planner/page.tsx:448 | Day view Ekadashi notice | warning | info | Same |
| components/planner/MealComposer.tsx:1723 | Badge "Fasting day — grain preparations will ask you to confirm" | warning | info | States the day, not a problem; the grain confirm at :1145 is the real warning |
| components/planner/DayView.tsx:113 | Badge "X Ekadashi — fasting day" | accent | info | Ekadashi is blue everywhere else (planner, calendar) |
| app/calendar/page.tsx:546 | `BORDER_TONES.ekadasi` (event list rule) | border-accent | border-info | Matches its own cell and dot, which are info |
| app/calendar/page.tsx:547 | `BORDER_TONES.fast` | border-warning | border-accent | A fast is not a warning; cell and dot already accent |
| app/calendar/page.tsx:367 | Badge "Corrected by hand — reason" | warning | neutral | A record of an admin's correction, nothing to do |
| components/planner/DayView.tsx:122 | Notice "This date was corrected by hand" | warning | info | Same |
| app/planner/reuse/page.tsx:486 | Badge with the fast's name on a copied day | danger | info | A fast is information, not an emergency |
| app/planner/reuse/page.tsx:439 | Tally "not copied" | danger | warning | Dishes left for the planner to fill; act, not emergency |
| app/planner/reuse/page.tsx:438 | Tally "days left alone" | warning | neutral | Nothing overwritten; that is the safe outcome |
| app/planner/reuse/page.tsx:462 | Badge "Left alone" | warning | neutral | Same |
| app/planner/reuse/page.tsx:353 | Badge "happened once" | warning | neutral | Fact about history |
| app/today/page.tsx:332 | Badge "Recorded" on a meal row | success | neutral | Passive state; the recording toast is the green moment |
| components/planner/MealServices.tsx:397 | Badge "Recorded" | success | neutral | Same |
| components/planner/MealServices.tsx:547 | Badge "Cooked" | success | neutral | Passive state |
| components/planner/MealServices.tsx:551 | Badge "Ingredients ready" | success | neutral | Named in the brief as passive |
| components/planner/MealServices.tsx:545 | Badge "Not made" (dish on a recorded meal) | warning | neutral | Past fact, nothing to act on |
| components/planner/MealServices.tsx:1259 | Crew chip when fully rostered | success | neutral | Passive good state |
| app/staff-schedule/page.tsx:408 | Day chip "Covered" | success | neutral | Passive good state |
| app/staff-schedule/page.tsx:434 | Hours in a day cell changed by an exception | text-warning | neutral (or info) | A changed shift is a fact to the admin who made it |
| app/staff-schedule/page.tsx:496 | Notice "On … leave" in the day layer | warning | info | Standing context; revoking is optional |
| app/leave/page.tsx:222 | Badge "Approved" | success | neutral | Passive state |
| app/leave/page.tsx:223 | Badge "Declined" | danger | neutral | Settled, not act-now |
| app/profile/page.tsx:451 | `statusTone` leave rows: APPROVED / DECLINED | text-success / text-danger | neutral both | Same as leave badges |
| app/profile/page.tsx:186 | "✓ You agreed on …" consent line | text-success | neutral | Standing record, not a fresh result |
| app/profile/page.tsx:545 | ✓ tick on each consent category | text-success | neutral | Same |
| components/IngredientRequestStatus.tsx:32 | `TONE.DENIED` badge | danger | neutral | Settled outcome |
| components/IngredientRequestStatus.tsx:33 | `TONE.ISSUED` badge | success | neutral | Passive state |
| app/ingredient-requests/[id]/page.tsx:247 | Notice "This request was denied, and that is final." | warning | info | Explains a settled state |
| app/ingredient-requests/[id]/page.tsx:254 | Notice "The goods have gone over the counter." (standing, not a flash) | success | info | Shown every time a finished request is opened |
| app/ingredient-requests/[id]/edit/page.tsx:74 | Notice "… has been answered, so it can no longer be changed." | warning | info | A locked state, explained |
| app/orders/po-status.tsx:40 | PO chip RECEIVED | success | neutral | Passive state |
| app/orders/[id]/page.tsx:147 | Badge "Sent late" | warning | neutral | Past fact on a sent order |
| app/orders/[id]/page.tsx:680 | Badge "Never delivered" | warning | neutral | Past fact on a closed order |
| app/orders/[id]/page.tsx:710 | Badge "Vendor let us down" | warning | neutral | Past fact on a closed order |
| app/vendor-performance/page.tsx:201 | Badge "N never delivered" (abandoned) | warning | neutral | Historical finding in a report |
| app/invoices/page.tsx:163 | Chip "Paid" | success | neutral | Named in the brief as passive |
| app/invoices/[id]/page.tsx:98 | Chip "Paid" | success | neutral | Same |
| app/invoices/[id]/page.tsx:123 | Notice "This bill was struck as never owed." | warning | info | Settled state |
| app/donations/[id]/page.tsx:201 | Notice "This gift was struck as wrongly recorded" | warning | info | Settled state |
| app/vendors/page.tsx:137 | Chip "Active" | success | neutral | Named in the brief as passive |
| app/vendors/[id]/page.tsx:348 | Status history badge "Brought back" | success | neutral | History entry |
| app/users/page.tsx:146 | "Active" | text-success | neutral | Passive state |
| app/tenants/page.tsx:158 | Chip "Approved" (80G) | success | neutral | Passive state |
| app/wishlist/page.tsx:117 | Status chip FULFILLED | success | neutral | Passive state |
| app/shifts/page.tsx:131 | Chip "You're in" | success | neutral | Standing state on a list; the sign-up toast is the confirmation |
| app/communications/page.tsx:345 | Delivery status "Delivered" / "Sent" | text-success | neutral | Passive state; "Failed" stays red |
| app/communications/page.tsx:288 | Notice "N copies didn't arrive." with Retry | warning | danger | Failed send, named in the brief as red |
| app/communications/composer.tsx:318 | WhatsApp preview bubble | bg-success-bg | bg-sunken | Decorative imitation of WhatsApp green; §3 says status colours are never decorative |
| components/give/DonatePage.tsx:469 | Wish-list item "Fully covered" / "₹X to go" (public donor page) | success / warning | neutral both | Neither is a result of the donor's action nor a warning to them |
| components/RecipePeek.tsx:123 | Recipe master badges (badge, state) | success | neutral | Labels, not status |
| app/ingredients/page.tsx:390 | Chip "Prohibited" (Ekadashi-prohibited) | warning | neutral (or info) | A classification, like the fasting-day notices |
| app/ingredients/page.tsx:550 | Chip "Prohibited" | warning | neutral (or info) | Same |
| app/operations/page.tsx:77, :83 | Database / worker health when up | text-success | neutral | Passive good state; down stays red |
| app/inventory/[id]/page.tsx:571 | Negative quantity in the movements table | text-danger | neutral | A draw-down is the ordinary case; the minus sign carries it |
| app/donations/new/page.tsx:361, :412 | ✕ "Remove food/line" row buttons | text-danger | neutral | Removing an unsaved line is trivial and undoable |
| app/inventory/page.tsx:233; app/inventory/[id]/page.tsx:164, :254 | Chip when the word is "Expired" | warning | danger (keep warning for "Expiring soon") | Expired is named in the brief as red |

## KEEP (grouped where the same decision covers many lines)

| file:line | element / text | tone | reason |
|---|---|---|---|
| lib/vaishnava-day.ts:149, :153, :161 | kitchenNote for part-day fast, feast day, full/new moon | info | Already information |
| app/today/page.tsx:123 | StatTile "Items below reorder level" when > 0 | warning | Act: order |
| app/today/page.tsx:140 | StatTile "Working today" when nobody is in | warning | Act: nobody rostered |
| app/today/page.tsx:396 | Badge "Invoice overdue" | danger | Overdue |
| app/today/page.tsx:452 | Meal "rostered of required" when short of crew | text-warning | Act: roster |
| app/today/page.tsx:640 | Unrecorded meals nudge | info | Deliberately a nudge |
| app/today/page.tsx:689 | "N machines are past their service date" | danger | Overdue |
| app/today/page.tsx:749 | Draft orders due today / past order date | warning | Act: send today |
| components/planner/MealServices.tsx:219 | "Short · order by date" | warning | Short but time to order |
| components/planner/MealServices.tsx:222, :224 | "Short · order today", "Short · won't arrive in time" | danger | Act now / won't arrive |
| components/planner/MealServices.tsx:216 | "Short of ingredients" (no order-by date known) | danger | Fallback of the red cases |
| components/planner/MealServices.tsx:1203 | Crew chip when short | warning | Act: roster |
| components/planner/MealComposer.tsx:1145 | Grain dish on a fasting day, confirm | warning | Be careful |
| components/planner/MealComposer.tsx:1240, :1248 | Ready-by time that cannot arrive | danger | Won't arrive in time |
| components/planner/MealComposer.tsx:1240, :1250 | Ready-by time that is tight (loading squeeze) | warning | Be careful |
| components/planner/MealComposer.tsx:1618 | Target yield field out of range | border-warning | Check the value |
| components/planner/MealComposer.tsx:1664, :2004 | Readout "Rostered" when short | warning | Act: roster |
| components/planner/MealComposer.tsx:1549 | "Last X's menu has been added." after pressing it | success | User's action |
| app/shopping-list/page.tsx:545 | Badge "Order by date" | warning | Time to order |
| app/shopping-list/page.tsx:553, :560 | "Order today", "Won't arrive in time" | danger | Act now |
| app/shopping-list/page.tsx:362, :387 | "Not ordering — since", "shortfall" chip | warning | Be careful / act |
| app/orders/[id]/page.tsx:172 | Badge "Past the order-by date" | danger | Overdue |
| app/orders/po-status.tsx:35 | PO chip PARTIALLY_RECEIVED | warning | Goods still owed |
| app/orders/page.tsx:102 | Drafts at risk of lateness | warning | Act: send |
| app/vendor-performance/page.tsx:298 | Open orders overdue | warning | Still live |
| app/invoices/page.tsx:157; app/invoices/[id]/page.tsx:103 | Chip "Overdue" | danger | Overdue |
| app/invoices/page.tsx:139; app/invoices/[id]/page.tsx:182 | Variance against the PO | warning | Check it |
| app/invoices/page.tsx:74 | "Invoice recorded" when the number is a duplicate | warning | Check it |
| app/money/page.tsx:110 | Aging bucket past current | warning | Pay |
| app/vendors/page.tsx:142, :145; app/vendors/[id]/page.tsx:168 | "Recheck WhatsApp", contract ending | warning | Act |
| app/inventory/page.tsx:128, :134, :231; app/inventory/[id]/page.tsx:159, :164, :254 | Below reorder, "Low", "Expiring soon", "More committed than you hold" | warning | Act (Expired split out above) |
| components/EquipmentWords.tsx:82 | Condition NEEDS_REPAIR warning, IN_REPAIR accent | warning / accent | Act; accent is not a status tone |
| components/EquipmentWords.tsx:120; app/equipment/page.tsx:151 | Service overdue / due soon; overdue filter | danger / warning | Overdue / soon |
| app/staff-schedule/page.tsx:363, :405 | "N short" — red when nobody at all is rostered, amber otherwise | danger / warning | Act now / act |
| app/leave/page.tsx:209 | Approving leave leaves a meal short | text-warning | Be careful |
| app/leave/page.tsx:228; components/IngredientRequestStatus.tsx:30 | "Waiting", SUBMITTED | warning | Somebody has to answer it |
| app/staff/page.tsx:204, :200 | "Banned" | danger | Serious record against a person |
| app/staff/page.tsx:209 | "Job not recorded" | warning | Act: fill in |
| app/staff/[id]/page.tsx:161 | "There is a record against this person" before re-hire | warning | Be careful |
| components/staff/Ban.tsx:39-40, :70, :130, :286 | Warn other temples; consequences; findings at hire; retract | danger / warning | Serious and hard to undo; be careful |
| app/volunteers/moved-notice.tsx:25 | "That shift moved … have not been told" | warning | Act: tell them |
| app/communications/composer.tsx:223, :272 | "WhatsApp can't carry a letter", "This will reach N devotees" | warning | Notifies people |
| app/notices/new/page.tsx:104 | "This goes out immediately." | warning | Notifies people |
| app/settings/meal-kinds/page.tsx:497 | "Everything recorded as X is renamed too" | warning | Be careful |
| components/KitchenForm.tsx:179, :185, :197 | Archived kitchen; main kitchen moved while open; "Read this before it is saved" | warning | Be careful |
| app/recipes/page.tsx:187 | "Check imported ingredients for Ekadashi" | warning | Act |
| components/PurchaseOrderEditor.tsx:261 | Needed-by date warning | text-warning | Be careful |
| components/RecipeForm.tsx:309, :312 | Very few / a lot of people for one batch | warning | Check it |
| components/IngredientRequestForm.tsx:267; app/ingredient-requests/[id]/page.tsx:230, :480; app/register/page.tsx:236 | Form-level "problem" messages before submit | warning | Fix before continuing |
| app/settings/page.tsx:754 ("Not yet"), :1314 | Setup check not done; template issue | warning | Act |
| app/sign-in/page.tsx:105 | "Sign-in isn't configured on this environment" | warning | Developer-facing |
| components/PlatformNotices.tsx:36-46 | URGENT red, IMPORTANT amber | danger / warning | Severity chosen by the sender |
| app/communications/page.tsx:347; app/operations/page.tsx:70, :77/:83 (down), :117, :189 | "Failed", API unreachable, service down, failed-sends pulse | danger | Failed / serious |
| components/ErrorNotice.tsx:15, :20; every `role="alert" bg-danger-bg` block (settings ×7, sign-in ×3, LanguageSection, profile, unsubscribe, DonatePage ×2, operations) | Server refused / failed | danger | Refused, with a KMS code |
| app/tenants/[id]/page.tsx:109-138, :455-517 | Delete-temple section and dialog; export done / not done | danger / success | Irreversible; green confirms the export they just ran |
| Every `variant="danger"` button and red confirm heading (occasions, kitchens, meal-kinds, invoices, donations, vendors, ingredients, supplies, glossary, inventory, equipment, volunteers, staff terminate, PayPanel, ingredient-requests, recipes/[id], confirm-layer, orders cancel, notices withdraw, VendorStatusDialog) | Destructive action | danger | Deletes, voids or ends something |
| Every `InlineNotice tone="success" autoDismiss` flash and `text-success` "Saved." (≈40 sites: occasions, vendors, donations, kitchens, meal-kinds, staff-schedule, planner, notices, tenants, shopping-list, ingredients, inventory, ingredient-requests, volunteers, supplies, staff, equipment, shifts, communications, wishlist, orders, leave, settings, LanguageSection, sign-in "Your account is ready", DonatePage "Thank you — the kitchen has been told") | Confirms the user's own action | success | Exactly what green is for |
| app/staff/page.tsx:52 | "X's employment has ended" flash | info | Deliberately not green |
| Every `tone="info"` explainer (issued-from-store, cost-per-serving, vendor-performance, choose-temple, planner/reuse, planner/meal, recipes, ingredients, staff/bans, Reinstate, TerminateForm, DayView:68, MealServices:905/:1135) | Standing context | info | Already information |
| app/planner/page.tsx:433, :552, :665 (Ekadashi); app/calendar/page.tsx:66, :72, :205 | Ekadashi badge / cell / dot / legend | info | Already information |
| app/kitchens/page.tsx:165; app/planner/page.tsx:414; MealComposer:1185; IngredientRequestStatus APPROVED; calendar fast cell/dot/legend | Accent badges and marks | accent | Not a status tone; outside the rule |
| app/design-reference/page.tsx:81-87 | Swatches of the three status colours | all | Reference page |
| backend WorkOrderTemplate.java:457 | Printed `.shortfall` chip on a pick line | danger | The storekeeper cannot pick it |

## BORDERLINE (8)

| file:line | element / text | now | reason it is not obvious |
|---|---|---|---|
| app/calendar/page.tsx:68, :74, :207, :548; app/planner/page.tsx:435, :560, :665 | Festival/feast cell, dot, legend, event rule, badge, month-cell text | success (green) | Must stop being green, but the calendar needs three distinguishable kinds and info (Ekadashi) and accent (fast) are taken. Needs a fourth non-status colour or a different mark. |
| components/Field.tsx:65, :107 and every inline field error (register:353, KitchenForm:243, shift-form:233) | Field-level validation text and red border | danger | A blank or mismatched field is not serious, yet form-level "problem" messages for the same kind of fault are amber. Decide one tone for "fix this before saving". |
| app/orders/[id]/page.tsx:183 vs app/shopping-list/page.tsx:553 | "Order today" | warning on the order, danger on the shopping list | Same state, two colours. Pick one. |
| app/my-schedule/page.tsx:188 | Badge "Changed" on the staff member's own day | warning | The person should notice their hours moved (amber), but nothing is wrong (neutral). |
| app/ingredient-requests/[id]/page.tsx:488 | "Nothing was issued." after pressing Issue with stock short | warning | The action was refused (red?), but an ErrorNotice above it already carries the red. |
| app/whatsapp-templates/page.tsx:182 | "Meta refused this for its formatting" | warning | "Refused" suggests red, but it is fixed at leisure by the super admin, not act-now. |
| app/profile/page.tsx:381-396 | "Withdraw this leave" confirm, red panel and button | danger | Styled as destructive; withdrawing a leave request is minor and the person can ask again. |
| app/settings/page.tsx:754 (and :410, :425, :1028) | Setup check "Working" | success | A standing status (neutral), but it is the result the admin set up and came to check. |

## Counts

- CHANGE: 61 rows (about 70 individual sites)
- KEEP: 63 rows (grouped; about 230 individual sites)
- BORDERLINE: 8
- Also needed: DESIGN_SYSTEM §3 `success` row ("Paid, received, shift fully staffed") contradicts the rule and must be amended.
