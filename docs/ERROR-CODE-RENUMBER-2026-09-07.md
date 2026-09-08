# The error-code renumber — 2026-09-07

Every `KMS-nnnn` code became a `KMS-nnnnnn` code on 2026-09-07. This is the record of it, and the
table at the bottom is the whole point of the file: git history, old screenshots, the UAT defect
logs and Rajeev's own review notes all quote four-digit codes, and this is where anybody resolves
one afterwards.

Signed off by Rajeev on 2026-09-07. Nothing about a code changed except its number — not the enum
constant, not the words the user reads, not the next step, not the HTTP status.

## What the numbers are now

Six digits. **Client errors from `400001` upward, server errors from `500001` upward**, allocated in
the order the constants are declared in `ErrorCode.java` and in no other order. 128 codes moved: 123
client, 5 server. They land on `KMS-400001`–`KMS-400123` and `KMS-500001`–`KMS-500005`.

Only the **first digit** carries meaning, and all it says is which HTTP family the failure answers
with — the same thing the `httpStatus` field beside it has always said. The hundreds carry nothing.
A code added tomorrow takes the next free number in its family, wherever in the file it happens to
sit.

## Why the old scheme went

The four-digit numbers encoded the *kind* of 4xx in the hundreds: 4000s validation, 4100s
authentication, 4300s authorisation, 4400s not found, 4900s conflict, 5000s internal, 5200s an
external service. Two things were wrong with that.

**It duplicated a field.** Every constant already carries its `httpStatus`. Two places holding the
same fact is two places that can disagree, and they did: `EQUIPMENT_SERIAL_ALREADY_USED` is a 409
that took 4015 because 4014 was gone, `COST_PERIOD_NOT_VALID` is a 400 sitting at 4988 in the middle
of the conflict band, `NOT_YOUR_INGREDIENT_REQUEST` is a 403 at 4978. Each of those needed a
paragraph of comment explaining why the number lied, and each paragraph said the same thing: the
number is a convention and the status field is the truth. Once you have written that three times you
have established that the convention is not carrying its weight.

**The band it mattered most in had run out.** 92 of the 128 codes were conflicts, in a 4900s band
with room for 99. It was already spilling — 4014, 4015, 4016 are conflicts and validations
interleaved — and the next epic would have burst it outright. A band that cannot hold its contents
is not organising anything.

So the grouping is abandoned rather than re-cut. A flat sequence is honest about what the number is:
a unique handle, nothing more.

## Why six digits, not a flat four

This is the part worth keeping. A flat four-digit scheme would have landed the client family on
4001–4123 — and **4102 already means `SESSION_EXPIRED`**, has meant it since Epic 1, and is sitting
in whatever screenshots and support notes exist. A renumber into the same width makes every old code
a plausible new code: quote `KMS-4102` after the change and you get a confident, wrong answer.

Six digits removes the possibility. No four-digit code is a valid six-digit code, so the two
namespaces are disjoint and **no number ever means two different things**. A four-digit code is,
unambiguously and forever, an old-scheme code, and it resolves here. That property is the entire
reason for the width, which is why no four-digit number is reused anywhere — not for a new code, not
as an example in a doc comment.

Codes are permanent. This is the one renumber; the permanence rule governs from here.

## What was deliberately not swept

Roughly 700 literal `KMS-nnnn` mentions were rewritten from the enum itself rather than by hand.
These were left alone, on purpose:

- **Applied Flyway migrations** (`backend/src/main/resources/db/migration/`). Eleven comments and one
  `COMMENT ON` string quote old codes. Editing an applied migration changes its checksum and the
  application then refuses to boot against any database that already ran it — staging included. V67
  set the precedent for this in the tree already: *"the comment cannot be edited in place — V22 is
  applied, and rewriting an applied migration breaks its checksum."* The stale numbers are safe to
  leave precisely because of the disjointness above: a four-digit code in a migration comment is
  visibly historical. The one that reaches a live database is the comment on
  `employment_ban_raising_tenant(uuid)` in V65, which still names `KMS-4307` (now `KMS-400027`); a
  future migration can restate it, the way V67 restated V22's, if it is ever worth one of its own.
- **Git history and commit messages.** They cannot be rewritten and are not meant to be.
- **`docs/CHANGELOG.md`** (4 mentions) and **`docs/WORK_QUEUE.md`** (3). Both belong to the release
  agent; it rewrites them at commit time from this table.
- **`docs/work/`** — the work machine's own ledger, including proposed codes for tasks not yet
  built. The work manager re-plans those against the new scheme.
- **`.claude/agents/work-manager.md`**, which uses `KMS-4936` as an illustration of a collision, and
  **`CLAUDE.md`**, which says `KMS-nnnn`. Both are configuration for how the project is worked on
  rather than product documentation, and neither is a builder's to edit.
- **`KMS-0000`**, the frontend's sentinel for *"the server gave us no code at all"* — a dropped
  connection, a proxy's HTML error page. It is not an `ErrorCode` and never came from this enum, so
  it had no number to renumber and inventing one would be taking a code that was not allocated. It
  is now the only four-digit reference the application can put on a screen. **Worth a decision:** it
  should probably become a real six-digit code, and that is a small piece of work, not a sweep.
- **Numbers that never shipped.** `KMS-4017` (`SERVICE_PROVIDER_IN_USE`, reversed by V90 before it
  deployed), `KMS-4927` (`USER_NOT_KITCHEN_STAFF`, retired), and `KMS-4969` and `KMS-4972` (proposed
  in the Epic 2 library design, never built as described). These stay four-digit, marked in the docs
  as old-scheme, because they have no six-digit counterpart and never will. `KMS-4972` in particular
  was *not* mechanically rewritten: the number was later allocated to `KITCHEN_NAME_TAKEN`, and
  mapping it would have made that design document say something false.

## One illustrative code was corrected on the way past

`KMS-4172` appeared four times as a made-up example — twice in `ErrorCode`'s own Javadoc, once in
`ErrorResponse`, and once in `DESIGN_SYSTEM.md` §7. No such code ever existed. The design-system
example is verbatim `WHATSAPP_SEND_FAILED` ("download the PDF and share it manually"), so all four
now read `KMS-500002`, which is that code. An example a reader can look up is worth more than one
they cannot.

## The mapping

New numbers run in **declaration order**, so the table below — sorted by the old number, which is
what somebody holding a screenshot has — is very nearly but not exactly ascending on both sides.

### Client errors — HTTP 4xx

| Old | New | Constant | HTTP |
|---|---|---|---|
| `KMS-4001` | **`KMS-400001`** | `VALIDATION_FAILED` | 400 |
| `KMS-4002` | **`KMS-400002`** | `INVALID_COORDINATES` | 400 |
| `KMS-4003` | **`KMS-400003`** | `INVALID_PHONE_NUMBER` | 400 |
| `KMS-4004` | **`KMS-400004`** | `INVALID_PAN` | 400 |
| `KMS-4005` | **`KMS-400005`** | `LEAVE_DATES_INVALID` | 400 |
| `KMS-4006` | **`KMS-400006`** | `HALF_DAY_IS_ONE_DAY` | 400 |
| `KMS-4007` | **`KMS-400007`** | `AMOUNT_NOT_POSITIVE` | 400 |
| `KMS-4008` | **`KMS-400008`** | `PAYMENT_REFERENCE_REQUIRED` | 400 |
| `KMS-4009` | **`KMS-400009`** | `SERVINGS_NOT_VALID` | 400 |
| `KMS-4010` | **`KMS-400010`** | `BAN_REASON_REQUIRED` | 400 |
| `KMS-4011` | **`KMS-400011`** | `VENDOR_DEACTIVATION_REASON_REQUIRED` | 400 |
| `KMS-4012` | **`KMS-400012`** | `CONDUCT_NOTE_EMPTY` | 400 |
| `KMS-4013` | **`KMS-400013`** | `INCOMPATIBLE_UNIT` | 400 |
| `KMS-4014` | **`KMS-400014`** | `NEEDED_BY_BEFORE_ORDER_DATE` | 400 |
| `KMS-4015` | **`KMS-400015`** | `EQUIPMENT_SERIAL_ALREADY_USED` | 409 |
| `KMS-4016` | **`KMS-400016`** | `SERVICE_DATE_IN_FUTURE` | 400 |
| `KMS-4101` | **`KMS-400017`** | `NOT_AUTHENTICATED` | 401 |
| `KMS-4102` | **`KMS-400018`** | `SESSION_EXPIRED` | 401 |
| `KMS-4103` | **`KMS-400019`** | `ACCOUNT_DISABLED` | 401 |
| `KMS-4104` | **`KMS-400020`** | `NO_ACCOUNT_AT_TEMPLE` | 401 |
| `KMS-4301` | **`KMS-400021`** | `NOT_PERMITTED` | 403 |
| `KMS-4302` | **`KMS-400022`** | `CANNOT_CHANGE_OWN_ROLE` | 403 |
| `KMS-4303` | **`KMS-400023`** | `CANNOT_ASSIGN_SUPER_ADMIN` | 403 |
| `KMS-4304` | **`KMS-400024`** | `CANNOT_DISABLE_SELF` | 403 |
| `KMS-4305` | **`KMS-400025`** | `ADJUSTMENT_REQUIRES_ADMIN` | 403 |
| `KMS-4306` | **`KMS-400026`** | `NOT_YOUR_LEAVE_REQUEST` | 403 |
| `KMS-4307` | **`KMS-400027`** | `NOT_THE_RAISING_TEMPLE` | 403 |
| `KMS-4308` | **`KMS-400028`** | `NOTICE_NOT_YOURS_TO_WITHDRAW` | 403 |
| `KMS-4401` | **`KMS-400029`** | `TENANT_NOT_FOUND` | 404 |
| `KMS-4402` | **`KMS-400030`** | `RESOURCE_NOT_FOUND` | 404 |
| `KMS-4403` | **`KMS-400031`** | `NO_STAFF_RECORD` | 404 |
| `KMS-4901` | **`KMS-400032`** | `SLUG_ALREADY_TAKEN` | 409 |
| `KMS-4902` | **`KMS-400033`** | `EMAIL_ALREADY_REGISTERED` | 409 |
| `KMS-4903` | **`KMS-400034`** | `INGREDIENT_ALREADY_EXISTS` | 409 |
| `KMS-4904` | **`KMS-400035`** | `INGREDIENT_IN_USE` | 409 |
| `KMS-4905` | **`KMS-400036`** | `RECIPE_ALREADY_EXISTS` | 409 |
| `KMS-4906` | **`KMS-400037`** | `SATTVIC_INGREDIENT_BLOCKED` | 409 |
| `KMS-4907` | **`KMS-400038`** | `CATEGORY_ALREADY_EXISTS` | 409 |
| `KMS-4908` | **`KMS-400039`** | `MOVEMENT_ALREADY_CORRECTED` | 409 |
| `KMS-4909` | **`KMS-400040`** | `INVENTORY_ITEM_ALREADY_EXISTS` | 409 |
| `KMS-4910` | **`KMS-400041`** | `STOCK_WOULD_GO_NEGATIVE` | 409 |
| `KMS-4911` | **`KMS-400042`** | `INSUFFICIENT_STOCK` | 409 |
| `KMS-4912` | **`KMS-400043`** | `EQUIPMENT_SCRAPPED` | 409 |
| `KMS-4913` | **`KMS-400044`** | `OCCASION_ALREADY_EXISTS` | 409 |
| `KMS-4914` | **`KMS-400045`** | `CANNOT_CANCEL_COOKED_MEAL` | 409 |
| `KMS-4915` | **`KMS-400046`** | `MEAL_PLAN_NOT_OPEN` | 409 |
| `KMS-4916` | **`KMS-400047`** | `MEAL_KIND_ALREADY_EXISTS` | 409 |
| `KMS-4917` | **`KMS-400048`** | `EKADASHI_NOT_ACKNOWLEDGED` | 409 |
| `KMS-4918` | **`KMS-400049`** | `VENDOR_ALREADY_EXISTS` | 409 |
| `KMS-4919` | **`KMS-400050`** | `PO_NOT_EDITABLE` | 409 |
| `KMS-4920` | **`KMS-400051`** | `PO_INVALID_TRANSITION` | 409 |
| `KMS-4921` | **`KMS-400052`** | `RECEIPT_LINE_NOT_ON_PO` | 409 |
| `KMS-4922` | **`KMS-400053`** | `RECEIPT_LINE_EMPTY` | 409 |
| `KMS-4923` | **`KMS-400054`** | `INVOICE_DIRECT_NEEDS_DESCRIPTION` | 409 |
| `KMS-4924` | **`KMS-400055`** | `PO_NOT_SENDABLE` | 409 |
| `KMS-4925` | **`KMS-400056`** | `PO_WHATSAPP_RATE_LIMITED` | 409 |
| `KMS-4926` | **`KMS-400057`** | `PERSON_ALREADY_EMPLOYED` | 409 |
| `KMS-4928` | **`KMS-400058`** | `SHIFT_NOT_OPEN` | 409 |
| `KMS-4929` | **`KMS-400059`** | `SHIFT_ALREADY_STARTED` | 409 |
| `KMS-4930` | **`KMS-400060`** | `ALREADY_SIGNED_UP` | 409 |
| `KMS-4931` | **`KMS-400061`** | `SHIFT_FULL` | 409 |
| `KMS-4932` | **`KMS-400062`** | `NOT_ON_SHIFT` | 409 |
| `KMS-4933` | **`KMS-400063`** | `ALREADY_ON_WAITLIST` | 409 |
| `KMS-4934` | **`KMS-400064`** | `SHIFT_NOT_FULL` | 409 |
| `KMS-4935` | **`KMS-400065`** | `BROADCAST_RATE_LIMITED` | 409 |
| `KMS-4936` | **`KMS-400066`** | `DONOR_80G_NOT_AVAILABLE` | 409 |
| `KMS-4937` | **`KMS-400067`** | `DONOR_CONSENT_REQUIRED` | 409 |
| `KMS-4938` | **`KMS-400068`** | `WISHLIST_ITEM_UNAVAILABLE` | 409 |
| `KMS-4939` | **`KMS-400069`** | `INVOICE_OVERPAYMENT` | 409 |
| `KMS-4940` | **`KMS-400070`** | `INVOICE_ALREADY_PAID` | 409 |
| `KMS-4941` | **`KMS-400081`** | `EXPORT_REQUIRED_BEFORE_DELETE` | 409 |
| `KMS-4942` | **`KMS-400071`** | `MEAL_KIND_UNKNOWN` | 409 |
| `KMS-4943` | **`KMS-400072`** | `READY_BY_TIME_REQUIRED` | 409 |
| `KMS-4944` | **`KMS-400073`** | `MEAL_CLIENT_REQUIRED` | 409 |
| `KMS-4945` | **`KMS-400074`** | `MEAL_VENUE_REQUIRED` | 409 |
| `KMS-4946` | **`KMS-400082`** | `PAYMENT_CREDENTIALS_REJECTED` | 409 |
| `KMS-4947` | **`KMS-400083`** | `PAYMENT_PROVIDER_UNSUPPORTED` | 409 |
| `KMS-4948` | **`KMS-400084`** | `PAYMENT_NOT_CONFIGURED` | 409 |
| `KMS-4949` | **`KMS-400085`** | `EMPLOYMENT_ALREADY_ENDED` | 409 |
| `KMS-4950` | **`KMS-400088`** | `STAFF_ACCESS_NEEDS_CONTACT` | 409 |
| `KMS-4951` | **`KMS-400086`** | `COMMUNICATION_ALREADY_SENT` | 409 |
| `KMS-4952` | **`KMS-400087`** | `COMMUNICATION_HAS_NO_AUDIENCE` | 409 |
| `KMS-4953` | **`KMS-400089`** | `LEAVE_OVERLAPS_EXISTING` | 409 |
| `KMS-4954` | **`KMS-400090`** | `LEAVE_ALREADY_DECIDED` | 409 |
| `KMS-4955` | **`KMS-400091`** | `LEAVE_NOT_APPROVED` | 409 |
| `KMS-4956` | **`KMS-400092`** | `CANNOT_SCHEDULE_OVER_LEAVE` | 409 |
| `KMS-4957` | **`KMS-400093`** | `SWAP_NEEDS_TWO_DAYS` | 409 |
| `KMS-4958` | **`KMS-400094`** | `DEDUCTIONS_EXCEED_GROSS` | 409 |
| `KMS-4959` | **`KMS-400095`** | `DEDUCTION_EXCEEDS_ADVANCE` | 409 |
| `KMS-4960` | **`KMS-400096`** | `ADVANCE_ALREADY_RECOVERED` | 409 |
| `KMS-4961` | **`KMS-400097`** | `STAFF_PAYMENT_NOT_VOIDABLE` | 409 |
| `KMS-4962` | **`KMS-400098`** | `MEAL_ALREADY_RECORDED` | 409 |
| `KMS-4963` | **`KMS-400099`** | `MEAL_NOT_RECORDABLE` | 409 |
| `KMS-4964` | **`KMS-400100`** | `BAN_ALREADY_EXISTS` | 409 |
| `KMS-4965` | **`KMS-400101`** | `BAN_ALREADY_RETRACTED` | 409 |
| `KMS-4966` | **`KMS-400123`** | `NOTICE_ALREADY_WITHDRAWN` | 409 |
| `KMS-4967` | **`KMS-400102`** | `RECIPE_IN_USE` | 409 |
| `KMS-4968` | **`KMS-400103`** | `RECIPE_ALREADY_ADDED` | 409 |
| `KMS-4970` | **`KMS-400104`** | `RECIPE_NEEDS_PROHIBITED_INGREDIENT` | 409 |
| `KMS-4971` | **`KMS-400105`** | `MASTER_RECIPE_NOT_FOUND` | 404 |
| `KMS-4972` | **`KMS-400106`** | `KITCHEN_NAME_TAKEN` | 409 |
| `KMS-4973` | **`KMS-400107`** | `KITCHEN_IN_USE` | 409 |
| `KMS-4974` | **`KMS-400108`** | `KITCHEN_NOT_FOUND` | 404 |
| `KMS-4975` | **`KMS-400109`** | `KITCHEN_ARCHIVED` | 409 |
| `KMS-4976` | **`KMS-400110`** | `KITCHEN_PLANS_ITS_OWN_MEALS` | 409 |
| `KMS-4977` | **`KMS-400111`** | `INGREDIENT_REQUEST_NOT_FOUND` | 404 |
| `KMS-4978` | **`KMS-400112`** | `NOT_YOUR_INGREDIENT_REQUEST` | 403 |
| `KMS-4979` | **`KMS-400113`** | `INGREDIENT_REQUEST_NOT_EDITABLE` | 409 |
| `KMS-4980` | **`KMS-400114`** | `INGREDIENT_REQUEST_ALREADY_DECIDED` | 409 |
| `KMS-4981` | **`KMS-400115`** | `INGREDIENT_REQUEST_NOT_APPROVED` | 409 |
| `KMS-4982` | **`KMS-400116`** | `INGREDIENT_REQUEST_ALREADY_ISSUED` | 409 |
| `KMS-4983` | **`KMS-400117`** | `INGREDIENT_REQUEST_EMPTY` | 409 |
| `KMS-4984` | **`KMS-400121`** | `INGREDIENT_REQUEST_NEEDS_DISHES` | 409 |
| `KMS-4985` | **`KMS-400120`** | `KITCHEN_MAIN_MOVED` | 409 |
| `KMS-4986` | **`KMS-400119`** | `INGREDIENT_REQUEST_NOT_SUBMITTED` | 409 |
| `KMS-4987` | **`KMS-400118`** | `INSUFFICIENT_STOCK_TO_ISSUE` | 409 |
| `KMS-4988` | **`KMS-400122`** | `COST_PERIOD_NOT_VALID` | 400 |
| `KMS-4989` | **`KMS-400080`** | `MEAL_HEAD_COUNT_REQUIRED` | 409 |
| `KMS-4990` | **`KMS-400075`** | `EVENT_NAME_REQUIRED` | 409 |
| `KMS-4991` | **`KMS-400076`** | `EVENT_CONTACT_REQUIRED` | 409 |
| `KMS-4992` | **`KMS-400077`** | `EVENT_DELIVERY_DETAILS_REQUIRED` | 409 |
| `KMS-4993` | **`KMS-400078`** | `DELIVERY_ADDRESS_NOT_FOUND` | 409 |
| `KMS-4994` | **`KMS-400079`** | `DELIVERY_CANNOT_ARRIVE_IN_TIME` | 409 |
### Server errors — HTTP 5xx

| Old | New | Constant | HTTP |
|---|---|---|---|
| `KMS-5001` | **`KMS-500001`** | `UNEXPECTED_FAILURE` | 500 |
| `KMS-5201` | **`KMS-500002`** | `WHATSAPP_SEND_FAILED` | 502 |
| `KMS-5202` | **`KMS-500003`** | `TRANSLATION_FAILED` | 502 |
| `KMS-5203` | **`KMS-500004`** | `DOCUMENT_GENERATION_FAILED` | 502 |
| `KMS-5204` | **`KMS-500005`** | `PAYMENT_GATEWAY_ERROR` | 502 |
### Four-digit numbers with no successor

| Old | Was | Why there is no new number |
|---|---|---|
| `KMS-4017` | `SERVICE_PROVIDER_IN_USE` | The managed service-provider list was reversed by V90 on 2026-09-04, before it was ever deployed. Removed rather than retired — nobody had seen it. |
| `KMS-4927` | `USER_NOT_KITCHEN_STAFF` | Shipped, then retired at E6-S8 when hiring became what grants a role. Burned in the old namespace and never reused. |
| `KMS-4969` | *(proposed)* | The Epic 2 library design wanted a second "name already taken". `RECIPE_ALREADY_EXISTS` already said it, so this was never built. |
| `KMS-4972` | *(proposed)* | The Epic 2 library design wanted "only a platform operator can change the recipe library". Never built; the number was later allocated to `KITCHEN_NAME_TAKEN`, which is a different failure entirely. |

### Six-digit numbers retired after the renumber

These are gaps in the **new** namespace. They were allocated, shipped, and then the code that could
raise them was deleted. The rule is the same in both namespaces and in both directions: **a number
that has been declared never comes back meaning something else.**

| Number | Was | Why it is retired |
|---|---|---|
| `KMS-400018` | `SESSION_EXPIRED` | Declared with finished copy and thrown nowhere — `TokenVerifier` deliberately refuses to say *why* a token failed, and the browser already handles the case a real person meets. Ruled by Rajeev, 2026-09-07, **D-9**. |
| `KMS-400023` | `CANNOT_ASSIGN_SUPER_ADMIN` | Thrown by exactly one guard, in `RoleChangeService`, which was deleted as dead code on 2026-09-07 (T-040) because its endpoint had no caller anywhere in the product. **D-9's precedent applied, not a new decision.** The protection it gave did not go with it: `staff/SystemAccess.java` has three constants and cannot express `SUPER_ADMIN`, so the only remaining role-assigning path cannot represent the value — structural now rather than checked. |
| `KMS-400037` | `SATTVIC_INGREDIENT_BLOCKED` | The service-layer hard stop on saving a recipe that names a sattvic-prohibited ingredient. Retired 2026-09-08 by **D-18**, which deleted the flag itself: no ingredient row can carry it, so `applySattvicEnforcement` had nothing left to find. **D-9's precedent applied.** |
| `KMS-400104` | `RECIPE_NEEDS_PROHIBITED_INGREDIENT` | The recipe import's half of the same block, refusing a library recipe whose resolved rows included a flagged one. Retired 2026-09-08 by **D-18**, and unlike the two above this one is a guard being *given up* rather than one that had already stopped working: an imported recipe naming garlic now imports cleanly, and D-18 records that as accepted, because the shared library is the temple's own and should not contain them. |

Why retiring rather than reusing, stated once for all four: the catalogue's entire value is that a number
means one thing for ever, and somebody quoting a code off an old screenshot must get the right answer
or none. Retiring costs a fresh number if the capability ever returns — and numbers are never reused
anyway, so it costs nothing. Keeping a code no path can raise costs a permanent entry that describes
a refusal the application cannot make, which is worse than a gap because it will be believed.
