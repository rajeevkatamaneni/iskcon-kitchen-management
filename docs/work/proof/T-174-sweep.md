# T-174 phase 1 — sweep of every `Field` given `required`, and what a blank coordinate does today

Read-only. No source, test, config or doc was edited. No test was run: reading answered both
questions, and no existing test sends a 0 or a missing coordinate, so running them would show nothing
about it.

## Part A — the sweep

### How the list was found

- The files that import `Field` from `@/components/Field` (checked with grep, then each file read):
  `app/settings/meal-kinds/page.tsx`, `app/settings/occasions/page.tsx`, `app/settings/page.tsx`,
  `app/sign-in/page.tsx`, `app/tenants/new/page.tsx`, `app/tenants/[id]/edit/page.tsx`.
- **The dispatch list was wrong in one place.** `components/planner/MealComposer.tsx` imports only
  `FIELD_LABEL` (line 33) and renders no `Field`. `components/ds/Form.tsx` imports only `FIELD_ERROR`
  (line 17).
- Nothing re-exports `Field` or wraps it. `HintedField` (`components/ds/InfoHint.tsx:57`) is a
  separate component. `app/planner/reuse/page.tsx:537` and `app/vendors/[id]/page.tsx:390` each define
  their own local `function Field`, which is not this component.
- `required` is always passed as a literal. No `Field` gets it from an expression or a spread.
- `app/settings/page.tsx` has 4 `Field`s (lines 1377, 1522, 1549, 1576: the volunteer message cap and
  the three warning horizons). None is given `required`, so that screen does not change.

### Every `Field` given `required`

"Input required?" means whether the control already carries `required` itself today.

| # | Screen | Box | Input required? | What it is; is blank legitimate? | Tests that submit it blank | Verdict |
|---|---|---|---|---|---|---|
| 1 | `/sign-in`, email tab (`sign-in/page.tsx:225`) | Email address | No | Sign-in email. A blank is never an answer. | `signin.test.tsx` T-166 "refuses no blank box, because no box on the page carries required" (asserts `not.toBeRequired()` and no sentence): **flips** | Clearly required |
| 2 | `/sign-in`, email tab (`:231`) | Password | No | Sign-in password. Never blank. | Same T-166 test: **flips** | Clearly required |
| 3 | `/sign-in`, phone tab (`:312`) | Phone number | No | Number for the SMS code. Never blank. | None. "hands Firebase the bare number" fills it. | Clearly required |
| 4 | `/sign-in`, after the code is sent (`:334`) | Code | No | The SMS code. Never blank. | T-166 "hands the typed code to Firebase" asserts `expect(code).not.toBeRequired()` (line 118), then fills it: **that one assertion flips** | Clearly required |
| 5 | `/tenants/new` (`tenants/new/page.tsx:183`) | Name | No | Temple name. Server `@NotBlank` (`ProvisionTenantRequest.java:25`). | See the tenant-new list below | Clearly required |
| 6 | `/tenants/new` (`:288`) | Latitude | No | Needed for the calendar. Server `@NotNull`; a blank is sent as 0 today (Part B). | See below | Clearly required |
| 7 | `/tenants/new` (`:302`) | Longitude | No | Same as latitude. | See below | Clearly required |
| 8 | `/tenants/new` (`:317`) | Timezone (select) | No | Opens on `Asia/Kolkata`, and every option has a value, so it can never be blank. The attribute is inert. | None | Clearly required (never refuses) |
| 9 | `/tenants/new` (`:334`) | Currency (select) | No | Opens on `INR`, and every option has a value. Inert. | None | Clearly required (never refuses) |
| 10 | `/tenants/new` (`:369`) | Full name (administrator) | No | Server `@NotBlank` (`:59`). | See below | Clearly required |
| 11 | `/tenants/new` (`:373`) | Email address (administrator) | No | Server `@NotBlank @Email` (`:63`). | See below | Clearly required |
| 12 | `/tenants/new` (`:380`) | Phone number (administrator) | No | Server `@NotBlank` plus the E.164 pattern (`:67`). Provisioning cannot succeed without it. | See below | Clearly required |
| 13 | `/tenants/[id]/edit` (`edit/page.tsx:194`) | Name | No | Opens on the temple's name. Server `@NotBlank` (`UpdateTenantRequest.java:63`). | `tenant-edit.test.tsx` T-166 "refuses no box itself, and shows the server's answer to a cleared name" (clears it, expects the server call and no sentence): **flips** | Clearly required |
| 14 | `/settings/meal-kinds`, add form and edit dialog (`meal-kinds/page.tsx:334`) | Name | **Yes** (`:339`) | No change. | `meal-kinds.test.tsx` T-160's two tests already expect "Name is required": unchanged | Clearly required |
| 15 | `/settings/occasions`, add form and edit dialog (`occasions/page.tsx:404`) | Name | **Yes** (`:409`) | No change. | `occasions.test.tsx` T-160 blank test already expects it: unchanged | Clearly required |
| 16 | `/settings/occasions`, "calendar" kind only (`:419`) | Wording in the calendar | **Yes** (`:429`) | Rendered only for the calendar kind, so it is required only when it is shown. No change. | Same T-160 test: unchanged | Clearly required |
| 17 | `/settings/occasions`, "same date every year" kind only (`:440`) | Month (select) | No | The draft opens on `"1"` (`:114`), an edit opens on `String(o.fixedMonth ?? 1)` (`:125`), and every option has a value. Gains the attribute and never refuses. | None | Clearly required (never refuses) |
| 18 | `/settings/occasions`, "same date every year" kind only (`:456`) | Day | **Yes** (`:462`) | No change. | "says a day of 0 must be at least 1": unchanged | Clearly required |

**Counts.** 18 `Field`s are given `required`, and all 18 are clearly required. 14 of them gain the
attribute (rows 1–13 and 17). 11 of those 14 can actually refuse a blank; timezone, currency and month
can never be blank.

### Arguable boxes

**None.** Each box that gains `required` is one the server already refuses when blank, or one that
can never be blank. Two points the main session should see before phase 2, though neither is a reason
to hold a box back:

- **The coordinates while "Is this the right place?" is showing.** Today, pressing Add temple while
  that card is up and the boxes are still blank sends 0,0. T-166 pinned that as "unchanged from
  before Form" (`tenant-new.test.tsx:428–443`), not as intended behaviour. After phase 2 the same press
  shows "Latitude is required" and "Longitude is required" under the boxes, below the card still
  asking to confirm. That is the right outcome, since the only other one is a temple in the Atlantic.
  It is still a visible change on that screen.
- **The administrator's phone.** Required is right today because the server insists on it. If
  Rajeev ever wants that phone optional, both sides change together. This task changes neither.

### Tests whose meaning changes

**`tenant-new.test.tsx`** (the Field change makes the blank boxes below refuse, so `provisionSpy` is
never called and these go red unless phase 2 fills them in):

- "previews the derived web address, cleans the phone, and hands off on success" (line 100): coordinates, admin name and email blank
- "takes out the spaces and leaves a typo in…" (127): same
- "steers a duplicate-name web-address clash to the Name field" (145): everything but Name blank
- "fills the coordinates from the server's answer once the operator confirms it" (170): admin name, email and phone blank
- "declining what came back leaves the coordinates empty…" (212): name and the admin fields blank
- "a coordinate the operator would rather type wins…" (230): same
- "a place that cannot be resolved never becomes a confirmation…" (247): same
- "a deployment with no Places key gets the plain box…" (270): same
- "shows the coordinates exactly as the server sent them…" (310): admin fields blank
- T-166 "lets a phone typo through to the server, and shows its KMS-400003" (404): coordinates and admin name and email blank
- T-166 "names no coordinate box while the picker's answer is still waiting to be confirmed" (428): **its point reverses** (it asserts `not.toBeRequired()` and that the blank coordinates are sent)
- T-166 "names a malformed administrator email in words, and provisions nothing" (391): probably still green, but no longer for the reason it names. Other blank boxes are refused too, and focus goes to Latitude, not the email box. Phase 2 should fill the rest so the email is the only thing wrong.
- Not affected: "leaves a coordinate that is already short…" (342) and "never spends a lookup on two characters" (358). Neither submits.

**`signin.test.tsx`:** T-166 "refuses no blank box…" (96) reverses. The `not.toBeRequired()` on
the code box in "hands the typed code…" (118) flips. The other four tests are unaffected.

**`tenant-edit.test.tsx`:** T-166 "refuses no box itself…" (316) reverses. Every other test submits
with the name still filled from the record, so none is affected.

**Read and found not affected**, though DISPATCH's T-174 row names some of them. None submits a
`Field` box that gains `required`:
- `sign-out.test.tsx` renders the sign-in page but never submits it.
- `settings-payments.test.tsx` and `settings-warnings.test.tsx`: no `Field` on Settings is given `required`.
- `meal-kinds.test.tsx` and `occasions.test.tsx`: their required inputs already carry the attribute, and Month is never blank.
- `meal-composer.test.tsx`: MealComposer renders no `Field`.
- `form-wrapper.test.tsx`: its `Field` input already carries `required`.
- `field-kit.test.tsx` and `tenants.test.tsx`: their `Field`s are not given `required`.
- `blank-submit-slice-f.test.tsx`: renders profile and audit only.

## Part B — a blank or cleared coordinate on Add a temple and Edit this temple

### Client, Add a temple (`frontend/app/tenants/new/page.tsx`)

- The two boxes are controlled strings that start as `""` (lines 68–69). They are
  `type="number" step="any"` with no `min`, `max` or `required` (288–314), so neither the browser nor
  `Form` refuses anything in them.
- On submit the page sends `latitude: Number(form.get("latitude"))` and the same for longitude
  (103–104). `Number("")` is `0`. **A blank or cleared box is sent as 0.** A half-typed number such as
  `12.` also reads as `""` in a browser, so it is sent as 0 too.
- The address picker does not write the boxes directly. A pick stores the server's answer in `picked`
  (213–220) and shows "Is this the right place?". Only "Use these coordinates" writes the boxes,
  as `String(number)` (266–270). "No, I'll type them" (277) and typing in the address again (221–224)
  leave whatever the boxes held. So pressing Add temple while the card is up and the boxes are still
  blank sends 0,0. T-166's test at `tenant-new.test.tsx:428–443` pins exactly that.
- After phase 2, `required` on a blank number box sets `valueMissing`, and a half-typed one sets
  `badInput`. `Form` refuses both before `handleSubmit` runs, so neither can reach `Number()`.

### Client, Edit this temple (`frontend/app/tenants/[id]/edit/page.tsx`)

- The coordinates are not form controls. They are shown as text (`Fixed`, 254–255) and sent from the
  loaded record, as `latitude: temple.latitude` and `longitude: temple.longitude` (130–131). The
  comment at 120–127 says this was chosen so that a lost value could not reach the payload through
  `Number()` as 0. `TenantDetail` types both as required numbers (`lib/api.ts:191–192`).
- **So on this screen a coordinate cannot be blank or cleared on the client.**

### Server

**Provisioning** (`POST /api/v1/tenants`, `TenantController.java:59–68`, `@Valid`):
- `ProvisionTenantRequest.java:39–47`: `@NotNull` with `@DecimalMin`/`@DecimalMax` of -90..90 and
  -180..180. A JSON `null` or a missing field is refused with "Enter the temple's latitude." A JSON `0`
  becomes `BigDecimal` 0, which passes all three constraints.
- `TenantProvisioningService.provision` (56–60) checks only the timezone and a duplicate slug.
  `insertTenant` (141–158) writes `request.latitude()` and `request.longitude()` unchanged.
- `V1__tenancy_foundation.sql:28–29`: `latitude NUMERIC(9,6) NOT NULL` and the same for longitude.
  `:45–46` has `CHECK (latitude BETWEEN -90 AND 90)` and `CHECK (longitude BETWEEN -180 AND 180)`. 0 is
  inside both. No later migration adds a constraint on `tenants` coordinates. (V88 and V97 are about
  meal-plan delivery coordinates, not temples.)
- The service then queues the temple's calendar (`calendarScheduler.enqueueForTenant`), which works
  from those coordinates.

**Correction** (`PATCH /api/v1/tenants/{id}`, `TenantController.java:166–176`, `@Valid`):
- `UpdateTenantRequest.java:70–78` has the same `@NotNull` and ranges.
  `TenantUpdateService.rejectFrozenFieldChanges` (226–247) refuses any coordinate that differs from the
  stored one, comparing with `compareTo` (`numberChanged`, 263–265). A 0 is refused unless the temple is
  already stored at 0. A temple provisioned at 0,0 cannot be corrected on this path (D-17); it has to
  be deleted and added again.

**What the existing tests pin:** `TenantProvisioningIT.refusesInvalidCoordinates` (242–252) sends only
`latitude: 200`. `TenantUpdateIT` refuses a changed latitude (198–213) and several frozen fields at once
(239–253). **No test in either class sends a coordinate of 0, or leaves one out.**

### Finding, stated on its own

**The server accepts 0 for a missing coordinate today.** Add a temple turns a blank box into 0, and
`POST /api/v1/tenants` stores 0.000000, 0.000000 without complaint. Nothing refuses it: not the
request's validation, not the service, not the column. Only a JSON `null` is refused. Phase 2 closes the
blank-box route on this screen. It does not change what the server accepts from any other caller, or
from a client that loses the value some other way. Per the brief, this is not fixed in T-174. Whether
the server should refuse a 0 pair is a decision of its own, because 0 on one axis is a real place (the
equator, the Greenwich meridian), as V97's comment argues for delivery pins.

## Phase 2's paths

- `frontend/components/Field.tsx`: pass `required` into the child's props, and add it to the
  render-props type.
- `frontend/__tests__/field-kit.test.tsx`: the new `Field` test, showing the input carries `required`
  when `Field` does and does not when it does not.
- `frontend/__tests__/tenant-new.test.tsx`: the 12 tests listed above.
- `frontend/__tests__/signin.test.tsx`: 2 tests.
- `frontend/__tests__/tenant-edit.test.tsx`: 1 test.
- **No page file needs editing.** Every child spreads `props` onto its control. On Add a temple the
  address box's props go into `AddressPicker`'s `inputProps`, but that `Field` is not given `required`.
- To run, not edit: `sign-out`, `meal-kinds`, `occasions`, `settings-payments`, `settings-warnings`,
  `form-wrapper`, `tenants`, `blank-submit-slice-f`, and `design-system.test.ts` (a repo-wide guard that
  reads source files as text). They should stay green, and running them is the proof. `meal-composer`
  can be dropped from the list, since MealComposer renders no `Field`.
- `tenant-new.test.tsx`, `signin.test.tsx` and `tenant-edit.test.tsx` currently hold T-166's
  uncommitted work. Phase 2 should start from them only after the 3-2 + T-173 release has committed
  them.

## Not done / needs the main session

- No hand test in a real browser. This phase is reading only.
- The two ITs were not run, for the reason at the top. The server claim rests on reading the request
  records, the services, the controller and V1's constraints.
- The server accepting 0 is a separate finding and needs its own task.
- DISPATCH's T-174 row names `MealComposer.tsx` as a `Field` user and lists `meal-composer`,
  `settings-payments`, `settings-warnings` and `sign-out` as tests that may change. None of them
  changes. That row is not mine to edit.
