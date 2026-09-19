"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { Badge } from "@/components/ds/Badge";
import { FieldRow } from "@/components/ds/FieldRow";
import { Form } from "@/components/ds/Form";
import { InfoHint } from "@/components/ds/InfoHint";
import { AddressPicker } from "@/components/planner/AddressPicker";
import { Button } from "@/components/ds/Button";
import { ButtonLink } from "@/components/ds/ButtonLink";
import { Card } from "@/components/ds/Card";
import { EmptyState } from "@/components/ds/EmptyState";
import { InlineNotice } from "@/components/ds/InlineNotice";
import { BusyPot } from "@/components/Loading";
import { ErrorNotice } from "@/components/ErrorNotice";
import {
  api,
  toApiError,
  type ApiError,
  type CrewAtView,
  type EventNameSuggestion,
  type Handover,
  type MealCrewView,
  type MealKindView,
  type MealShiftDraft,
  type MealView,
  type MenuHistoryView,
  type RecipeSummary,
  type SaveMealInput,
  type ShiftView,
  type TravelEstimate,
  type UpdateMealInput,
} from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { ShiftLayer, timesChanged, timesChangedWarning } from "@/components/planner/ShiftLayer";
import { ConfirmLayer, useLeaveGuard } from "@/app/planner/confirm-layer";
import { convertQuantity, longDate, unitLabel } from "@/lib/format";
import { ekadashiLabel } from "@/lib/vaishnava-day";
import { FIELD_LABEL } from "@/components/Field";

/**
 * Planning a meal, in the order a kitchen decides one: what kind of meal it is, who is expected,
 * what is being cooked, who will run it, and anything the cooks should know.
 *
 * <p>The head count is the temple's own arithmetic — children eat about six tenths of a portion,
 * seniors about eight — and it sets each preparation's servings. But only until someone disagrees:
 * a planner knows the sweet always goes first and the buttermilk rarely does, so every preparation
 * carries its own figure, and one that has been set by hand is never overwritten by a later change
 * to the count. That judgement is the most valuable thing on this screen.
 *
 * <p>Since 2026-08-21 the same component also <em>edits</em> a meal, given one in `existing` (item
 * 16). A meal is planned as one thing and it is corrected as one thing: change a preparation's
 * servings, swap one, remove one, add one, move the ready-by, redo the head count. Two forms for
 * the one act would be two places to keep a rule.
 *
 * <p>Since E4-S15 an <em>event</em> is planned here too, and step 1 asks it a chain of questions
 * nothing else is asked: its name, whether it leaves the temple, and — only if it does — how it
 * gets there and who to ring. The three main meals see none of it and ask exactly what they always
 * asked. An event is also the one kind that may be saved with nobody counted (D2): it is planned by
 * how much to make, and thirty laddus and some chiwda is a real thing a temple cooks.
 */

const CHILD_PORTION = 0.6;
const SENIOR_PORTION = 0.8;
/**
 * The most of one dish a meal may plan, in the recipe's own yield unit (T-217). The same 50,000 as
 * `SaveMealRequest.DishDraft`, `RecipeService.MAX_TARGET_YIELD` and `DocumentService.MAX_TARGET_YIELD`
 * on the server; the four move together.
 */
const MAX_TARGET_YIELD = 50_000;

interface Draft {
  recipeId: string;
  /**
   * How much to make, in the recipe's own yield unit — 60 litres of rasam, 300 idlis, 10 kilos of
   * podi. Null where nobody has said yet: a recipe with no per-head portion cannot be filled in
   * from a head count, and an empty box that stays empty is the honest state until somebody
   * answers it. It is also what stops the save (item E2-S17).
   */
  target: number | null;
  /** Set once the planner types a number of their own; the head count stops driving it. */
  overridden: boolean;
  /**
   * The dish this draft already is, when a meal is being edited. Absent on a preparation added during
   * this edit — the server creates that one — and a planned dish left out of the list is cancelled
   * rather than deleted, so its history survives.
   */
  dishId?: string;
}

/**
 * Everything the meal itself is, as the save and the update both take it — everything, that is,
 * except the day and the kind (which an update cannot change), its dishes and its volunteer shift.
 *
 * <p><strong>Named rather than inferred, and that is the point (T-044).</strong> `mealFacts()` used
 * to have no return type, and its result is *spread* into the request: spread properties are exempt
 * from TypeScript's excess-property check, so a field the request needed could simply be absent and
 * nothing would say so. `deliveryLatitude` and `deliveryLongitude` were absent for exactly that
 * reason, while the composer sent a pin of `0, 0` alongside a real place id, and every edit of a
 * placed delivery event silently re-pinned it to the Gulf of Guinea. Every other payload builder in
 * this application is annotated — `readShiftForm`, `collect`, `build` — and this was the one that
 * was not. With the annotation the next omission is a compile error at the one place that can fix it,
 * which is what a comment saying "remember to send the pin" was never going to be.
 */
type MealFacts = Omit<
  SaveMealInput,
  "planDate" | "mealKindId" | "ekadashiAcknowledged" | "dishes" | "volunteerShift"
>;

/**
 * Where a picked delivery address actually is: the place id and the pin that came back with it.
 *
 * <p>The pin is nullable and zero is never allowed to stand in for it. Null means the coordinates
 * are not to hand — an older plan saved with a place id and nothing beside it — and the server
 * answers null by asking Places for the id, which is the question Places can answer. A zero would
 * instead be taken for a place somebody chose, and stored.
 */
interface PickedPlace {
  placeId: string;
  latitude: number | null;
  longitude: number | null;
}

/** What the screen around the composer needs to know to draw its own commit button. */
export interface ComposerStatus {
  busy: boolean;
  blocked: boolean;
  /** Why it is blocked, in one clause, or null when it is not. */
  hint: string | null;
}

export function MealComposer({
  date,
  recipes,
  mealKinds,
  isEkadashi,
  ekadashiName,
  existing,
  formId,
  onClose,
  onPlanned,
  onStatus,
}: {
  date: string;
  recipes: RecipeSummary[];
  mealKinds: MealKindView[];
  isEkadashi: boolean;
  /**
   * What the calendar calls this day, so the picker can say why its list is short in the same words
   * the calendar and the day header use. Optional: absent, the line falls back to "Ekadashi".
   */
  ekadashiName?: string | null;
  /** The meal being corrected. Absent when a new one is being planned. */
  existing?: MealView;
  /**
   * The id the screen's own commit button targets with `form=`.
   *
   * <p>Every caller is a focus screen now, and the screen carries the heading and the
   * `[Cancel] [Primary]` pair. The composer used to draw its own card and its own buttons for the
   * inline case, behind a `chrome` prop — which is precisely how planning a meal and correcting one
   * came to put their buttons in two different places. The branch is gone rather than reconciled.
   */
  formId?: string;
  onClose: () => void;
  onPlanned: () => void;
  /** Lets a focus screen keep its own button in step with the form it commits. */
  onStatus?: (status: ComposerStatus) => void;
}) {
  const { getToken } = useAuth();
  // Held in a ref rather than read in the effects below. `getToken` is a fresh closure on most
  // renders, so an effect that depends on it re-runs on every render — and an effect that also
  // sets state then never stops.
  const tokenRef = useRef(getToken);
  tokenRef.current = getToken;

  const editing = Boolean(existing);

  const [kindName, setKindName] = useState(existing?.mealKind ?? mealKinds[0]?.name ?? "");
  const kind = mealKinds.find((k) => k.name === kindName);

  const [readyBy, setReadyBy] = useState(
    existing ? existing.readyBy.slice(0, 5) : (kind?.defaultReadyTime?.slice(0, 5) ?? "")
  );
  /**
   * Who is expected, and nothing until somebody says so.
   *
   * <p>This used to open on 100 adults. Nobody chose that number and the application then costed,
   * scaled and rostered against it — a meal for a head count it had invented. A new meal now starts
   * at nothing, which is the only honest answer before the planner has said, and it is the count
   * that has to be filled in before anything can be saved against it. A meal being corrected opens
   * on its own figures, which somebody did choose.
   */
  const [adults, setAdults] = useState(existing?.adults ?? 0);
  const [children, setChildren] = useState(existing?.children ?? 0);
  const [seniors, setSeniors] = useState(existing?.seniors ?? 0);
  const [picked, setPicked] = useState<Draft[]>(() => openDrafts(existing));
  const [notes, setNotes] = useState(existing?.kitchenNotes ?? "");
  const [serverNotes, setServerNotes] = useState(existing?.serverNotes ?? "");

  /**
   * The event block (E4-S15 D6), asked for in a chain and only by a kind that is an event.
   *
   * <p>An event has a name. Going outside adds a contact, name and phone both. Delivering adds an
   * address and the time the guests sit down. An in-house event stops at its name — a Bhajan
   * Prasadam in the temple hall has no client, and a form that asked for one would be asking a
   * question with no answer, which gets either a made-up answer or a blocked save.
   *
   * <p>A meal being corrected opens on its own facts, which since D-27 live once on the meal rather
   * than on each of its preparation rows.
   */
  const [eventName, setEventName] = useState(existing?.eventName ?? "");
  const [isOutside, setIsOutside] = useState(Boolean(existing?.isOutside));
  const [handover, setHandover] = useState<Handover | "">(existing?.handover ?? "");
  const [contactName, setContactName] = useState(existing?.contactName ?? "");
  const [contactPhone, setContactPhone] = useState(existing?.contactPhone ?? "");
  const [deliveryAddress, setDeliveryAddress] = useState(existing?.deliveryAddress ?? "");
  /**
   * Where the picked address actually is. Null the moment somebody types over it, because a
   * coordinate left behind from a previous pick would route the van to the wrong gate — which is
   * worse than having no estimate at all.
   *
   * <p>A meal being corrected reopens on the pin it was saved with (T-044). It used to reopen on
   * `latitude: 0, longitude: 0`, because the view did not return the coordinates and a placeholder
   * was all this had; the server reads a pin as a place somebody chose and stores it without looking,
   * so saving an edit re-pinned the event to 0°N 0°E and the job card worked a driver's departure time
   * back from a drive into the Atlantic. The coordinates are on the view now.
   *
   * <p>Where they are null — an address typed rather than picked, or one of the older plans that kept
   * only a place id — null is what goes back across, and the server does the right thing with it: it
   * asks Places for the id, or keeps the coordinates it already holds, or looks the address up again
   * once they are past their thirty days. None of those are things a zero would have let it do.
   */
  const [placed, setPlaced] = useState<PickedPlace | null>(
    existing?.deliveryPlaceId
      ? {
          placeId: existing.deliveryPlaceId,
          latitude: existing.deliveryLatitude,
          longitude: existing.deliveryLongitude,
        }
      : null
  );
  const [subLocation, setSubLocation] = useState(existing?.deliverySubLocation ?? "");
  const [guestsEatAt, setGuestsEatAt] = useState(existing?.guestsEatAt?.slice(0, 5) ?? "");

  /**
   * How long to allow for the drive, and whether a person set it.
   *
   * <p>The box is prefilled from Google the moment there is a picked address and a serving time, and
   * anybody may type over it. Rajeev, 2026-09-05: *"the user who has local knowledge knows googles
   * estimates are inflated or deflated can adjust it manually."* Typing flips the source to MANUAL,
   * which is what stops the job card refreshing the figure out from under them on the sheet a driver
   * is about to act on.
   */
  const [travelMinutes, setTravelMinutes] = useState<string>(
    existing?.travelMinutes == null ? "" : String(existing.travelMinutes)
  );
  const [travelManual, setTravelManual] = useState(
    existing?.travelMinutesSource === "MANUAL"
  );
  /** What Google currently says, kept beside the box so the "i" can be honest in both states. */
  const [estimate, setEstimate] = useState<TravelEstimate | null>(null);

  /**
   * What the food is for, in the planner's own words (B6). No kind asks for it any more — an
   * event's name says what it is (E4-S15 D5) — so there is no box for it here. It is still carried
   * through a correction rather than dropped: it is printed on the job card, and a meal that loses
   * a line somebody typed because a later story removed the field is a meal we have edited on their
   * behalf.
   */
  const [purpose] = useState(existing?.purpose ?? "");
  const [occasionName, setOccasionName] = useState(existing?.occasionName ?? "");

  /**
   * How many people it takes to cook this meal (item 24). One counter, not two: at execution time
   * the number can be met by any mix of staff and volunteers, and splitting it would invent a
   * constraint the temple does not have.
   *
   * <p>Null, not zero, until somebody says. Null is the honest answer for a meal nobody has thought
   * about the hands for yet, and it is drawn as an empty box rather than a nought.
   */
  const [crewRequired, setCrewRequired] = useState<number | null>(existing?.crewRequired ?? null);
  // Once the planner has touched the counter it is theirs, and the suggestion stops arriving over
  // the top of it when they change the kind of meal.
  const crewTouched = useRef(editing);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  // `recipeIds` are the offending preparations, so "Leave it out" can untick exactly those. Empty when
  // the per-dish check could not say which one it was — then there is nothing safe to untick.
  const [confirmGrain, setConfirmGrain] = useState<
    { names: string[]; ingredients: string[]; recipeIds: string[] } | null
  >(null);

  /**
   * The volunteer shift drafted in the layer and not yet saved (D-27 answers 2 and 7).
   *
   * <p>Held here, in the form's own state, and nowhere else: *Done* in the layer hands it back and
   * saves nothing, and *Save this meal* or *Update this meal* sends it as `volunteerShift`, where the
   * server saves the meal and the shift in one transaction. Abandon the meal and the draft goes with
   * it — which is the whole of Rajeev's rule: *"we should not be left with an orphan shift."*
   *
   * <p>Null means nothing has been drafted in this visit. On an update that is also what is sent, and
   * the server reads null as "leave the meal's shift exactly as it is".
   */
  const [shiftDraft, setShiftDraft] = useState<MealShiftDraft | null>(null);
  const [shiftOpen, setShiftOpen] = useState(false);
  /** The times-changed warning is showing, waiting for *Update this meal* or *Go back*. */
  const [confirmTimes, setConfirmTimes] = useState(false);
  /**
   * Somebody has changed something on this form. Set by the form's own change events and by the few
   * controls that are buttons rather than boxes, never by what the form fills in for itself — a
   * suggested crew size or the calendar's occasion is not a change anybody made.
   */
  const [dirty, setDirty] = useState(false);
  const leaveGuard = useLeaveGuard(dirty && !busy);
  const closeShift = useCallback(() => setShiftOpen(false), []);
  const dismissTimes = useCallback(() => setConfirmTimes(false), []);

  /** The meal's saved shift, if it has one. A draft of changes to it is in `shiftDraft`. */
  const liveShift: ShiftView | null = existing?.volunteerShift ?? null;
  /**
   * The one thing a saved plan can come back saying (E4-S16, KMS-400078): the map service could not
   * place the delivery address. <strong>It is not a refusal.</strong> The meal is saved and whole,
   * so this is a notice on a finished plan rather than an error on an unfinished one — and the form
   * stays open behind it only so the sentence has somewhere to be read, not because anything is
   * still wanted from it.
   */

  const headCount = Math.round(adults + children * CHILD_PORTION + seniors * SENIOR_PORTION);

  const byId = useMemo(() => new Map(recipes.map((r) => [r.id, r])), [recipes]);

  // --- what the form asks the server for, rather than the planner ------------

  /**
   * Which crew row is this meal's. By its id once it has one (D-27) — never by the kind's name, which
   * two events on one day share. A meal not yet saved has no row of its own: one of the main meals is
   * matched by its kind, because saving it lands on that day's meal of that kind if there is one.
   * Anything else not yet saved — every new event, and a main meal with no meal of its kind that day —
   * is counted at its date and ready-by instead, below.
   */
  const existingId = existing?.mealId ?? null;
  const matchByKind = !existing && !kind?.isEvent;

  /** Who is actually rostered over this meal's ready-by, for the readout in step 4. */
  const [crew, setCrew] = useState<MealCrewView | null>(null);
  /** Whether the day's crew rows have answered (or failed to) — until then nobody knows there is no row. */
  const [crewLooked, setCrewLooked] = useState(false);
  useEffect(() => {
    let live = true;
    tokenRef
      .current()
      .then((t) => api.mealCrew(date, date, t))
      .then((rows) => {
        if (live) {
          setCrew(
            rows.find((r) => (existingId ? r.mealId === existingId : matchByKind && r.mealKind === kindName)) ?? null
          );
          setCrewLooked(true);
        }
      })
      .catch(() => {
        if (live) {
          setCrew(null);
          setCrewLooked(true);
        }
      });
    return () => {
      live = false;
    };
  }, [date, kindName, existingId, matchByKind]);

  /**
   * Who is rostered at this date and ready-by, for a meal with no crew row to read (T-215).
   *
   * <p>Rajeev ruled that *Volunteers requested* opens on People needed minus Rostered (D-27 answer 1),
   * and a new event had no Rostered at all: the browser test prefilled 5 where 2 staff were rostered,
   * and the same meal read "2 of 5" once saved. The server counts the moment exactly as it counts a
   * saved meal, so the figure here is the figure after Save. Asking is a GET and saves nothing — no
   * meal, no day, no shift — because nothing in the planner is saved until the meal is (answer 7).
   *
   * <p>Asked again whenever the ready-by or the date changes, since a cook on 06:00–14:00 is in for
   * a 12:00 event and not for an 18:00 one. Only an answer for the time and date on screen is used,
   * so a slow reply to the time before is never read out as the count for this one; while the new one
   * is on its way, and if it cannot be fetched at all, the readout says it has not counted, which is
   * true.
   */
  const needsCount = !existingId && crewLooked && crew === null;
  const countableReadyBy = /^\d{2}:\d{2}(:\d{2})?$/.test(readyBy) ? readyBy.slice(0, 5) : null;
  const [crewAt, setCrewAt] = useState<CrewAtView | null>(null);
  useEffect(() => {
    if (!needsCount || !countableReadyBy) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.mealCrewAt(date, countableReadyBy, t))
      .then((count) => {
        if (live) setCrewAt(count);
      })
      .catch(() => {
        if (live) setCrewAt(null);
      });
    return () => {
      live = false;
    };
  }, [needsCount, date, countableReadyBy]);

  /** What step 4 reads out and measures People needed against: the meal's row, else the count. */
  const roster: Roster | null =
    crew ??
    (needsCount &&
    crewAt &&
    countableReadyBy &&
    crewAt.planDate === date &&
    crewAt.readyBy.slice(0, 5) === countableReadyBy
      ? crewAt
      : null);

  /** The median of the last three ordinary meals of this kind (Q11), or null where there are none. */
  useEffect(() => {
    if (crewTouched.current || !kindName) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.suggestedCrew(kindName, t))
      .then((s) => {
        if (live && !crewTouched.current) setCrewRequired(s.crewRequired);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [kindName]);

  /**
   * The events this temple has run before (E4-S15 D9).
   *
   * <p>Asked for with whatever has been typed so far, and with nothing at all when nothing has:
   * with an empty prefix the server answers with the most recent few, which is exactly what a
   * planner about to enter the same Saturday reading wants to see before they start typing.
   *
   * <p>A failure is silence. The suggestions save keystrokes and nothing else depends on them, so a
   * temple whose server is having a bad minute types the name out — which is what they would have
   * done anyway.
   */
  const isEventKind = Boolean(kind?.isEvent);

  /**
   * Asks for the estimate as soon as there is a place and a time to work back from.
   *
   * <p>It fills the box only while nobody has touched it. Once somebody has, the figure is theirs
   * and this becomes a second opinion shown under the "i" rather than something that overwrites
   * them — the same rule the job card follows at print time, for the same reason.
   */
  useEffect(() => {
    const delivering = isEventKind && isOutside && handover === "DELIVERY";
    if (!delivering || !guestsEatAt || !placed) {
      setEstimate(null);
      return;
    }
    let live = true;
    (async () => {
      try {
        const got = await api.travelEstimateFor(
          { placeId: placed.placeId, latitude: placed.latitude, longitude: placed.longitude },
          date,
          guestsEatAt,
          await getToken()
        );
        if (!live) return;
        setEstimate(got);
        if (!travelManual && got.available && got.pessimisticMinutes != null) {
          setTravelMinutes(String(got.pessimisticMinutes));
        }
      } catch {
        // One quiet absence. A map service having a bad minute must never stand between a planner
        // and a meal plan, and the box is typeable either way.
        if (live) setEstimate(null);
      }
    })();
    return () => {
      live = false;
    };
    // Two deliberate absences. `travelManual`, because flipping it must not re-run the lookup —
    // only stop the next reply from overwriting what the person just typed. And `getToken`, which is
    // a new function on every render: naming it here makes the effect re-run on every render, and
    // since the effect sets state, that is an infinite loop rather than a slow screen.
  }, [isEventKind, isOutside, handover, guestsEatAt, placed, date]);
  const [eventSuggestions, setEventSuggestions] = useState<EventNameSuggestion[]>([]);
  const eventQuery = isEventKind ? eventName.trim() : "";
  useEffect(() => {
    if (!isEventKind) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.eventNameSuggestions(eventQuery, t))
      .then((rows) => {
        if (live) setEventSuggestions(rows);
      })
      .catch(() => {
        if (live) setEventSuggestions([]);
      });
    return () => {
      live = false;
    };
  }, [isEventKind, eventQuery]);

  /**
   * The temple's named occasions, for the picker a feast carries. A list to choose from and not a
   * closed one: a temple anniversary, or a local festival the calendar does not carry, is typed in.
   */
  const [occasions, setOccasions] = useState<string[]>([]);
  useEffect(() => {
    if (!kind?.needsOccasion) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.listOccasions(t))
      .then((list) => {
        if (live) setOccasions(list.map((o) => o.name));
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [kind?.needsOccasion]);

  /**
   * What the calendar says this date is, which two boxes open on: the occasion a feast is for, and
   * the adults a new meal on a festival day expects (Phase B item 9, T-208).
   *
   * <p>Both are only ever defaults. The planner may be cooking a feast the calendar has never heard
   * of, and a festival's usual crowd is the temple's estimate, not this year's count. So each has a
   * touched ref, and once somebody has typed in the box the answer that arrives afterwards is thrown
   * away rather than written over them — the request is slow enough on a phone that a planner can
   * type 350 before it lands, and a 500 replacing it would be the application overruling a person.
   *
   * <p><strong>The servings default reaches every kind, not only a feast.</strong> The build brief
   * records Rajeev's decision as applying to a new meal on a festival day, not to a feast alone, and
   * on a festival the temple serves its ordinary breakfast and lunch to the same crowd the feast is
   * cooked for. So the day is asked about whenever the adults are still untouched, whatever the kind;
   * the occasion name is still written only on a kind that asks for one, as before.
   *
   * <p><strong>Never on a meal being corrected.</strong> Its adults are a number somebody chose and
   * saved, so `adultsTouched` starts true for it and the default never applies — correcting Tuesday's
   * lunch must not quietly swap its 180 for the festival's usual 500.
   *
   * <p>Applied through `suggestAdults` rather than `setAdults`: the preparations already ticked have
   * to rescale to the new head count exactly as if it had been typed, and that arithmetic reads the
   * latest recipes and counters, which a closure captured when this effect started would not have.
   * It does not mark the form changed — a default is not a change anybody made, so leaving the screen
   * straight afterwards asks nothing.
   */
  const occasionTouched = useRef(Boolean(existing?.occasionName));
  const adultsTouched = useRef(editing);
  const suggestAdults = useRef(applyCount);
  suggestAdults.current = applyCount;
  useEffect(() => {
    const wantsOccasion = Boolean(kind?.needsOccasion) && !occasionTouched.current;
    if (!wantsOccasion && adultsTouched.current) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.mealDayContext(date, t))
      .then((ctx) => {
        if (!live) return;
        if (kind?.needsOccasion && !occasionTouched.current && ctx.occasionName) {
          setOccasionName(ctx.occasionName);
        }
        if (!adultsTouched.current && ctx.suggestedServings != null) {
          suggestAdults.current("adults", ctx.suggestedServings);
        }
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [date, kind?.needsOccasion]);

  /**
   * What was cooked for this occasion last time (item 26b). Offered, never applied — the whole
   * point is that one press saves an hour of reassembling a menu, not that the app decides one.
   */
  const [history, setHistory] = useState<MenuHistoryView | null>(null);
  const [menuUsed, setMenuUsed] = useState(false);
  const occasionQuery = kind?.needsOccasion ? occasionName.trim() : "";
  useEffect(() => {
    if (!occasionQuery) {
      setHistory(null);
      return;
    }
    let live = true;
    setMenuUsed(false);
    tokenRef
      .current()
      .then((t) => api.menuHistory(occasionQuery, date, t))
      .then((h) => {
        if (live) setHistory(h.lastCookedOn ? h : null);
      })
      .catch(() => {
        if (live) setHistory(null);
      });
    return () => {
      live = false;
    };
  }, [occasionQuery, date]);

  /**
   * The picker, on a day the calendar already knows is a fast (E4-S6, review item MP1).
   *
   * <p>It opens filtered. The reviewers asked for a checkbox, and a checkbox is the wrong shape
   * here: it asks the planner to remember the fast on the one day the app is certain of it, and
   * the cost of forgetting is a grain preparation cooked for a temple that is fasting. So the
   * short list is the default and the way out is a button beside it, in plain sight.
   *
   * <p>The judgement is the server's. `fastingCompatible` on a summary is the recipe category's
   * answer; whether any single line uses a grain or a bean is a question only the ingredient flags
   * can settle, and that is what the `ekadashiCompatible` filter asks.
   */
  const [showGrains, setShowGrains] = useState(false);
  const [fastingList, setFastingList] = useState<
    { status: "loading" } | { status: "ready"; recipes: RecipeSummary[] } | { status: "unavailable" }
  >({ status: "loading" });
  useEffect(() => {
    if (!isEkadashi) return;
    let live = true;
    tokenRef
      .current()
      .then((t) => api.listRecipes({ ekadashiCompatible: true }, t))
      .then((rows) => {
        if (live) setFastingList({ status: "ready", recipes: rows });
      })
      .catch(() => {
        // Nothing to fall back to but the whole list. Saying so is better than a picker that has
        // silently stopped filtering, and the check on save still stands behind it.
        if (live) setFastingList({ status: "unavailable" });
      });
    return () => {
      live = false;
    };
  }, [isEkadashi]);

  const filtering = isEkadashi && !showGrains && fastingList.status === "ready";

  const loadingFastingList = isEkadashi && fastingList.status === "loading";

  /**
   * The preparations the picker draws.
   *
   * <p>Anything already picked stays on the list whatever the filter says. A meal being corrected
   * on a fasting day may hold a grain preparation somebody deliberately confirmed, and hiding it
   * would leave its servings box — and the block that box can put on saving — out of reach.
   */
  const visible = useMemo(() => {
    // Nothing until the filtered list lands. Drawing the full one and shrinking it would flash
    // every grain preparation the temple cooks, which is the one thing this control prevents.
    if (loadingFastingList) return [];
    if (!filtering || fastingList.status !== "ready") return recipes;
    const shown = new Set(fastingList.recipes.map((r) => r.id));
    const kept = picked
      .filter((d) => !shown.has(d.recipeId))
      .map((d) => byId.get(d.recipeId))
      .filter((r): r is RecipeSummary => Boolean(r));
    if (kept.length === 0) return fastingList.recipes;
    return [...fastingList.recipes, ...kept].sort((a, b) => a.name.localeCompare(b.name));
  }, [loadingFastingList, filtering, fastingList, recipes, picked, byId]);

  // --- the form itself -------------------------------------------------------

  function chooseKind(name: string) {
    setDirty(true);
    setKindName(name);
    const next = mealKinds.find((k) => k.name === name);
    setReadyBy(next?.defaultReadyTime?.slice(0, 5) ?? "");
  }

  /**
   * Naming the event — and, when the name is one the temple has used before, bringing that event's
   * arrangements with it (E4-S15 D9).
   *
   * <p>This is the point of the suggestions rather than a nicety. We are introducing a practice, not
   * digitising one: the temple's own artifacts hold no event register anywhere. If entering a
   * Saturday reading costs three minutes it will stop being entered, and the data is then worse than
   * if events had never been split out at all.
   *
   * <p>Carried once per name, and never again over the top of the planner. Somebody who takes the
   * suggestion and then corrects the phone number has this event's number, and nothing reaches back
   * to rewrite the one it came from — these are separate plans that happen to share a name.
   */
  const carriedFrom = useRef(existing?.eventName?.trim().toLowerCase() ?? null);
  function chooseEventName(value: string) {
    setEventName(value);
    const key = value.trim().toLowerCase();
    if (!key || key === carriedFrom.current) return;
    const previous = eventSuggestions.find((s) => s.eventName.trim().toLowerCase() === key);
    if (!previous) return;
    carriedFrom.current = key;
    // Everything, including the emptiness of it: taking the in-house Bhajan Prasadam forward must
    // leave no contact behind from whatever was picked before it.
    setIsOutside(previous.isOutside);
    setHandover(previous.handover ?? "");
    setContactName(previous.contactName ?? "");
    setContactPhone(previous.contactPhone ?? "");
    setDeliveryAddress(previous.deliveryAddress ?? "");
    // The place behind it does not carry: the suggestion is a remembered address string, not a
    // resolved one, and inventing coordinates for it would be routing the van on a guess.
    setPlaced(null);
  }

  /**
   * Whether this plan leaves any room to get the food into the van, and what to say if it does not.
   *
   * <p>Cooked at 16:00, seventy minutes on the road, guests eating at 17:00: the arithmetic works
   * only if loading takes less than no time. Thirty minutes is the floor rather than a realistic
   * estimate — it is deliberately the smallest gap that could plausibly be enough, so the warning
   * fires on plans that are genuinely impossible rather than merely optimistic. A warning that
   * cries wolf is one people learn to scroll past.
   *
   * <p>Two tiers, because Rajeev settled both on 2026-09-05. Arriving after the guests have sat
   * down is <strong>refused</strong> — "People Sit to eat time MUST be = Ready by time + transit
   * time at a minumum" — and the endpoint refuses it as well, since a screen is not a guard. Above
   * that floor but inside half an hour is a <strong>warning</strong> only, because nobody here knows
   * how long this temple takes to carry fifty litres of curd rice across a courtyard, and he was
   * explicit that the application must not pretend to.
   *
   * <p>Null when there is nothing to judge: not a delivery, or one of the three figures missing.
   */
  const timing = useMemo(() => {
    const delivering = isEventKind && isOutside && handover === "DELIVERY";
    const allow = positiveOrNull(travelMinutes);
    if (!delivering || !allow || !/^\d{2}:\d{2}$/.test(readyBy) || !/^\d{2}:\d{2}$/.test(guestsEatAt)) {
      return null;
    }
    const mins = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
    // A serving time at or before the food is ready is somebody mid-edit, or a meal that runs past
    // midnight. Neither is this arithmetic's business.
    if (mins(guestsEatAt) <= mins(readyBy)) return null;

    const spare = mins(guestsEatAt) - mins(readyBy) - allow;
    if (spare < 0) {
      const late = -spare;
      return {
        blocking: `The food arrives ${late} ${
          late === 1 ? "minute" : "minutes"
        } late. Make it ready earlier, or change when guests eat.`,
        warning: null,
      };
    }
    if (spare < LOADING_MINUTES) {
      return {
        blocking: null,
        warning: `Only ${spare} ${spare === 1 ? "minute" : "minutes"} to load the van. Make it ready earlier if you need more.`,
      };
    }
    return null;
  }, [isEventKind, isOutside, handover, travelMinutes, readyBy, guestsEatAt]);

  /** Refused: the van is still on the road when the guests sit down. */
  const cannotArrive = timing?.blocking ?? null;
  /** Allowed, but with nothing left over to carry it out and load it. */
  const loadingSqueeze = timing?.warning ?? null;

  /**
   * A head count the planner typed. Marks the form changed, and marks Adults as the planner's own so
   * a festival's usual crowd arriving late from the day's context never writes over it.
   */
  function setCount(which: "adults" | "children" | "seniors", value: number) {
    setDirty(true);
    if (which === "adults") adultsTouched.current = true;
    applyCount(which, value);
  }

  /**
   * A head count everyone follows, except the preparations someone has deliberately set. Split from
   * `setCount` so the day's suggested servings can go through the same rescaling without claiming
   * the planner changed anything.
   */
  function applyCount(which: "adults" | "children" | "seniors", value: number) {
    const v = Math.max(0, value);
    const next = {
      adults: which === "adults" ? v : adults,
      children: which === "children" ? v : children,
      seniors: which === "seniors" ? v : seniors,
    };
    if (which === "adults") setAdults(v);
    if (which === "children") setChildren(v);
    if (which === "seniors") setSeniors(v);

    const total = Math.round(next.adults + next.children * CHILD_PORTION + next.seniors * SENIOR_PORTION);
    setPicked((list) => list.map((d) => (d.overridden ? d : { ...d, target: targetFor(d.recipeId, total) })));
  }

  /**
   * How much to make of one dish for a given number of people.
   *
   * <p>A head count is not a quantity. It becomes one by multiplying by what one person eats:
   * 300 people at 200 ml of rasam is 60 litres, and 100 people at 3 idlis is 300 idlis. Copying the
   * head count into the box — which is what this did before — is only ever right for a recipe
   * measured in servings, and gives 300 litres of rasam for 300 people otherwise.
   *
   * <p>Null where the recipe has no per-head portion. That is not a failure: nobody serves a
   * per-head portion of lime pickle, and the honest answer is to leave the box empty and let the
   * planner say how much. Saving is blocked until they do.
   */
  function targetFor(recipeId: string, people: number): number | null {
    const recipe = byId.get(recipeId);
    if (!recipe) return null;

    // Nobody has said who is coming, so nothing can be said about how much to cook. An empty box
    // rather than a nought: a nought is an answer, and this is the absence of one. It fills itself
    // in the moment the counter is typed, which is what the planner sees happen.
    if (people <= 0) {
      return null;
    }

    // No per-head portion, no target. There used to be one more route here: a recipe measured in
    // servings already said what one person ate — one serving — so the head count was the target.
    // Servings stopped being a unit in V80, because it counts the people fed rather than the food
    // made, and with it went the only case where a head count could be a quantity of food.
    //
    // So the box arrives empty and the planner types what they mean to cook. That is the honest
    // answer for a recipe nobody has said the per-head portion of, and it is what already happened
    // for every recipe measured in kilos or litres.
    if (!recipe.perHeadQty) {
      return null;
    }

    // The portion in the recipe's own unit before it is multiplied, because the box is in the
    // recipe's unit (T-217). Basmati Ghee Rice is measured in litres with a portion of 350 ml; this
    // used to multiply 600 people by 350 and save 210,000 *litres*, which the Today screen then
    // refused to scale for the whole kitchen. 350 ml is 0.35 L, and 600 × 0.35 is 210 L.
    //
    // A portion with no unit is read as the recipe's, which is what every recipe saved before the
    // unit was asked for meant. A portion in another family — millilitres of a dish measured in
    // kilos — has no answer without a density, so the box stays empty and the planner types it,
    // exactly as for a recipe with no portion at all.
    const portion = recipe.perHeadUnit
      ? convertQuantity(Number(recipe.perHeadQty), recipe.perHeadUnit, recipe.baseYieldUnit)
      : Number(recipe.perHeadQty);
    if (portion === null) {
      return null;
    }

    const raw = people * portion;
    // Pieces are whole things. Half an idli is not a plan.
    return recipe.baseYieldUnit === "PIECES" ? Math.ceil(raw) : Math.round(raw * 100) / 100;
  }

  function toggle(recipeId: string) {
    setPicked((list) =>
      list.some((d) => d.recipeId === recipeId)
        ? list.filter((d) => d.recipeId !== recipeId)
        : [...list, { recipeId, target: targetFor(recipeId, headCount), overridden: false }]
    );
  }

  /**
   * <p>An emptied box stays empty. It used to become 1, through a `Math.max(1, Number(value))` over
   * an empty string — so tabbing past the quantity of a masala left a plan reading "1 Kg of Idli
   * Milagai Podi" for a festival, which is a legal quantity no server validation could catch.
   */
  function setTarget(recipeId: string, raw: string) {
    const value = raw.trim() === "" ? null : Number(raw);
    setPicked((list) =>
      list.map((d) =>
        d.recipeId === recipeId
          ? { ...d, target: value === null || Number.isNaN(value) ? null : value, overridden: true }
          : d
      )
    );
  }

  /**
   * Last time's menu, put in with one press.
   *
   * <p>The preparation list carries and nothing else does. Servings follow this year's head count,
   * and last year's per-preparation overrides are deliberately dropped: an override was a judgement
   * about last year's crowd, and re-applying it against a different head count would be wrong in a
   * way nobody would notice. Everything stays editable afterwards.
   */
  function useLastMenu() {
    if (!history) return;
    setDirty(true);
    setPicked((list) => {
      const already = new Set(list.map((d) => d.recipeId));
      const added = history.preparations
        .filter((p) => byId.has(p.recipeId) && !already.has(p.recipeId))
        .map((p) => ({ recipeId: p.recipeId, target: targetFor(p.recipeId, headCount), overridden: false }));
      return [...list, ...added];
    });
    setMenuUsed(true);
  }

  const needsOccasion = Boolean(kind?.needsOccasion) && !occasionName.trim();
  const needsTime = !readyBy;
  // Every preparation carries a quantity, or the meal does not save. A masala has no per-head
  // portion, so its box arrives empty; saved that way the kitchen is handed a plan with a hole in
  // it and adjusts on the fly, which is the thing planning exists to prevent.
  const missingQuantity = picked.find((d) => d.target === null || !(d.target > 0));
  /**
   * A meal that is cooking something has to say who it is for.
   *
   * <p>Checked as the three counters and not as the weighted total below them: a hall of one child
   * weighs 0.6 of a portion, which is a head count somebody made, and rounding it away to nothing
   * would refuse a meal that has been counted. The endpoint refuses the same meal in its own words
   * (KMS-400080) and is the guard that matters; this is here so the planner is stopped before eight
   * preparations of work go, rather than after.
   *
   * <p>Only once something is being cooked. A meal with nothing in it is a placeholder somebody has
   * put on Thursday without yet saying what or for how many, and there is nothing wrong with that.
   */
  /**
   * <p>An event is exempt (E4-S15 D2). It is quantified by how much to make, and its head count is
   * context: thirty laddus and some chiwda is a real thing a temple cooks, and the temple's own
   * `FHC Sabjis` sheet — its crib for bulk distribution — is kept in gross kilograms per dish with
   * no head count anywhere on it. <strong>The exemption is exactly that and no wider</strong>: the
   * three main meals are refused as they always were, here and at the endpoint.
   */
  const needsHeadCount =
    !isEventKind && picked.length > 0 && adults === 0 && children === 0 && seniors === 0;

  /**
   * The one thing the form is waiting for, in the order somebody fills it in — or null when it is
   * waiting for nothing and can be saved.
   *
   * <p>The endpoint refuses each of these in its own words and its own code, and that is the guard
   * that matters. This is here so a planner is stopped before eight preparations of work go in,
   * rather than after.
   */
  const blockedHint = firstBlocker();
  const blocked = blockedHint !== null;

  function firstBlocker(): string | null {
    if (picked.length === 0) return "Pick at least one preparation";
    // A new meal is saved against its kind's id. Only reachable while the kinds are still loading.
    if (!editing && !kind) return "Pick what kind of meal this is";
    if (needsTime) return "Pick the time it must be ready";

    // The event chain, in the order it is asked (D6). Each answer is what reveals the next
    // question, so naming them out of order would point at a box that is not on the screen yet.
    if (isEventKind) {
      if (!eventName.trim()) return "Give the event a name";
      if (isOutside) {
        if (!handover) return "Say whether somebody collects it or we deliver it";
        // Both halves. A contact you cannot ring is not a contact.
        if (!contactName.trim() || !contactPhone.trim()) return "Say who to contact, and their number";
        if (handover === "DELIVERY" && (!deliveryAddress.trim() || !guestsEatAt)) {
          return "Say where it is going and when the guests eat";
        }
        // The floor, and the endpoint refuses it too (KMS-400079). Rajeev, 2026-09-05: "People Sit to
        // eat time MUST be = Ready by time + transit time at a minumum." Below that the van is still
        // on the road when the guests sit down, whatever anybody does about loading.
        //
        // Short here on purpose: the arithmetic that explains it sits under the Ready by field,
        // which is the field somebody has to move. Printing the whole sentence twice would put two
        // copies of a long line on one screen and neither of them where the fix is.
        if (cannotArrive) return "The delivery cannot arrive in time";
      }
    }

    if (needsOccasion) return "Name the occasion this feast is for";
    // Before the quantities, because it is what emptied them: at a head count of nothing every
    // preparation's box is blank, and naming eight of them in turn would send the planner round the
    // list to fix one number at the top.
    if (needsHeadCount) return "Say how many people are expected";
    // Named, because a festival lunch has eight preparations and "a quantity is missing" sends
    // somebody hunting through all of them.
    if (missingQuantity) {
      return `Say how much ${byId.get(missingQuantity.recipeId)?.name ?? "this dish"} to make`;
    }
    return null;
  }

  // The focus screen draws the commit button, so it has to know what the form knows. Every value
  // here is a primitive and `onStatus` is expected to be stable, so this settles rather than loops.
  useEffect(() => {
    onStatus?.({ busy, blocked, hint: blockedHint });
  }, [busy, blocked, blockedHint, onStatus]);

  /**
   * Everything about the meal that every one of its preparation rows carries.
   *
   * <p>The event fields go across as nulls for a kind that is not an event, rather than as whatever
   * was typed into the block before the planner changed their mind about the kind. The server drops
   * them for such a kind anyway; sending them empty is so that what we sent and what it stored say
   * the same thing.
   *
   * <p>An in-house event sends the same nulls beyond its name, for the same reason: it has no
   * contact and no handover, and carrying one forward from a kind the planner tried and abandoned
   * would put a phone number on a Bhajan Prasadam.
   */
  function mealFacts(): MealFacts {
    const outside = isEventKind && isOutside;
    const delivering = outside && handover === "DELIVERY";
    return {
      readyBy: readyBy || null,
      eventName: isEventKind ? eventName.trim() || null : null,
      isOutside: outside,
      handover: outside ? handover || null : null,
      contactName: outside ? contactName.trim() || null : null,
      contactPhone: outside ? contactPhone.trim() || null : null,
      deliveryAddress: delivering ? deliveryAddress.trim() || null : null,
      deliverySubLocation: delivering ? subLocation.trim() || null : null,
      deliveryPlaceId: delivering ? placed?.placeId ?? null : null,
      // The pin goes with it. Leaving it behind is what made the save discard a chosen place and
      // ask a geocoder to find the address text again, which then failed on the very address the
      // picker had just resolved (2026-09-05). Null where there is no pin — an address that was
      // typed, or one of the older plans that kept only a place id — and never a zero, which the
      // server would take for a place somebody chose (T-044).
      deliveryLatitude: delivering ? placed?.latitude ?? null : null,
      deliveryLongitude: delivering ? placed?.longitude ?? null : null,
      guestsEatAt: delivering ? guestsEatAt || null : null,
      travelMinutes: delivering ? positiveOrNull(travelMinutes) : null,
      travelMinutesManual: delivering && travelManual,
      purpose: purpose.trim() || null,
      occasionName: kind?.needsOccasion ? occasionName.trim() || null : null,
      adults,
      children,
      seniors,
      crewRequired,
      kitchenNotes: notes.trim() || null,
      serverNotes: serverNotes.trim() || null,
    };
  }

  /**
   * How many hands the meal is short: People needed against Rostered. What *Ask for volunteers* is
   * offered on (strictly more than none short — at equal the meal is covered) and what the layer's
   * *Volunteers requested* opens on (D-27 answer 1). Rostered is the meal's crew row, or for a meal
   * not saved yet the count at its date and ready-by (T-215). Where neither is known — the count could
   * not be fetched, or no ready-by is given yet — nobody is counted as rostered, and the layer opens on
   * People needed in full, as it did before the count existed.
   */
  const shortBy = crewRequired == null ? 0 : crewRequired - (roster?.rostered ?? 0);

  /**
   * Whether this save changes the times of a shift people have signed up for — the one save that stops
   * and says so first (D-27 answer 6). Their places are kept and the server tells them after it saves.
   */
  const warnTimes =
    liveShift !== null && shiftDraft !== null && liveShift.signedUpCount > 0 && timesChanged(liveShift, shiftDraft);

  /**
   * Saves the meal as one request (D-27): its facts, every dish, and the volunteer shift if one was
   * drafted, which the server commits together or not at all.
   *
   * <p>It used to be a loop of one request per preparation — cancel the dropped ones, update the kept
   * ones, create the new ones — which could stop half way and leave a meal holding some of its
   * preparations. One request cannot, and it is the only shape a shift that must not outlive an
   * abandoned meal can be saved in.
   *
   * @param acknowledge the planner has confirmed grain preparations on a fasting day
   * @param timesAcknowledged the planner has read that signed-up volunteers will be told new times
   */
  async function save(acknowledge = false, timesAcknowledged = false) {
    if (warnTimes && !timesAcknowledged) {
      setConfirmTimes(true);
      return;
    }
    setConfirmTimes(false);
    setBusy(true);
    setError(null);
    const token = await tokenRef.current();

    const body: UpdateMealInput = {
      ...mealFacts(),
      ekadashiAcknowledged: acknowledge,
      dishes: picked.map((d) => ({ id: d.dishId ?? null, recipeId: d.recipeId, targetYield: d.target ?? 0 })),
      volunteerShift: shiftDraft,
    };

    try {
      if (existing) {
        await api.updateMeal(existing.mealId, body, token);
      } else if (kind) {
        await api.saveMeal({ planDate: date, mealKindId: kind.id, ...body }, token);
      } else {
        return;
      }
      onPlanned();
      // Saved, so there is nothing left to lose by leaving.
      setDirty(false);
      // OLD BEHAVIOUR, removed 2026-09-05: a warning held the form open rather than closing over the
      // top of it. Rajeev: "under normal circumstances, IF it is saved, it gets auto closed." A form
      // that stays open is how this app says a save did not happen. A saved meal closes the form, and
      // the day it lands on already carries the travel line for anything the map could not place.
      onClose();
    } catch (e) {
      const err = toApiError(e, editing ? "We couldn’t save that meal." : "We couldn’t plan that meal.");
      // A grain preparation on a fasting day. The whole save is refused now rather than one dish of
      // it, so each picked preparation is asked about, and every one that offends is named — letting
      // the planner decide rather than refusing a whole meal over a preparation they meant to cook.
      if (err.code === "KMS-400048" && !acknowledge) {
        const checks = await Promise.all(
          picked.map((d) =>
            api
              .ekadashiCheck(date, d.recipeId, token)
              .then((check) => ({ recipeId: d.recipeId, check }))
              .catch(() => null)
          )
        );
        const offending = checks.filter(
          (c): c is NonNullable<typeof c> => c !== null && !c.check.compatible
        );
        setConfirmGrain({
          names:
            offending.length > 0
              ? offending.map((c) => byId.get(c.recipeId)?.name ?? "A preparation")
              : ["A preparation"],
          ingredients: Array.from(new Set(offending.flatMap((c) => c.check.offendingIngredients))),
          recipeIds: offending.map((c) => c.recipeId),
        });
        return;
      }
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  if (recipes.length === 0) {
    // Says where to go, and deliberately does not offer to take them (Rajeev, 2026-09-06). Choosing
    // the recipes a temple cooks is a setting-up step done once and revisited rarely; the planner is
    // opened most days. A button here would put a rarely-wanted door on a frequently-used screen,
    // and the people who meet this are the ones who skipped a step they were trained on — they need
    // telling where to go back to, not a shortcut that saves them one click of it.
    const empty = (
      <EmptyState title="No recipes yet">
        Add recipes before you plan a meal. Open <strong>Recipes</strong> in the menu to add them.
      </EmptyState>
    );
    return empty;
  }

  const body = (
    <div className="grid gap-6">
      {error && <ErrorNotice error={error} />}

      {/* Saved, and then something worth saying about it (E4-S16). A warning tone rather than the
          red of a refusal, and the first word is that the plan is there — the map service's opinion
          of a street name never cost anybody a meal plan. The code is printed because it is the one
          travel failure somebody can act on, and they may need to quote it. */}
      {confirmGrain && (
        <InlineNotice
          tone="warning"
          title={`${confirmGrain.names.join(", ")} ${
            confirmGrain.names.length === 1 ? "has" : "have"
          } grains or beans, and this is a fasting day`}
          action={
            <span className="flex gap-3">
              <Button type="button" size="sm" disabled={busy} onClick={() => { setConfirmGrain(null); save(true, true); }}>
                Plan it anyway
              </Button>
              {/* The words are a promise: "Leave it out" unticks the grain dish. It used to only close
                  this warning and leave the dish ticked, so a cook who pressed it believed they had
                  removed a dish that was still on the menu (T-223 content audit, T-224). */}
              <Button
                type="button"
                size="sm"
                variant="ghost"
                onClick={() => {
                  const leaveOut = new Set(confirmGrain.recipeIds);
                  setDirty(true);
                  setPicked((list) => list.filter((d) => !leaveOut.has(d.recipeId)));
                  setConfirmGrain(null);
                }}
              >
                {confirmGrain.recipeIds.length > 1 ? "Leave them out" : "Leave it out"}
              </Button>
            </span>
          }
        >
          {confirmGrain.ingredients.length > 0 && <>Contains {confirmGrain.ingredients.join(", ")}.</>}
        </InlineNotice>
      )}

      {/* 1 — what kind of meal */}
      <section className="card grid gap-3 p-5">
        <Step n={1} title="What kind of meal" />
        {editing ? (
          // A meal is its date and its kind, so the kind is what identifies the thing being edited.
          // Changing it here would not correct this meal — it would move its preparations into a
          // different one.
          <div className="flex flex-wrap items-center gap-3">
            <Badge tone="accent">{kindName}</Badge>
            <InfoHint
              text="You can’t change the meal type. Cancel this meal and plan a new one."
              label="Meal kind"
            />
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {mealKinds.map((k) => (
              <button
                key={k.id}
                type="button"
                onClick={() => chooseKind(k.name)}
                aria-pressed={k.name === kindName}
                className={[
                  "min-h-touch rounded-control border px-4 text-sm transition-colors duration-state",
                  k.name === kindName
                    ? "btn btn-primary"
                    : "border-hairline-strong text-ink hover:bg-raised",
                ].join(" ")}
              >
                {k.name}
              </button>
            ))}
          </div>
        )}

        {/* The three rows of step 1 share one fixed column template. Left to itself a
            `grid-flow-col` row sizes each column to its widest child, and the labels are not the
            same width — *Is this going outside?* against *Event name* — so column three of row one
            would sit some pixels right of column three of row two. Pinning the columns at the
            control width makes the boxes line up down the form.

            The margins are the vertical half of the same problem, and they are arithmetic rather
            than magic numbers. Measured the way the eye reads it — bottom of one row's box to the
            top of the next row's label — every gap here is 56px.

            Every gap is the section's `gap-3` (12) plus `mt-11` (44), and every row uses the same
            margin — including the first, measured from the bottom of the meal-kind chips.

            It took two corrections to get there, both worth recording. It was 20 + 12 + 24 while a
            hint line sat under each control; when the guidance went into the labels' "i" on
            2026-09-04 the line went but `FieldRow`'s third track did not, leaving 4px of `gap-y-1`
            above a track nothing was drawn in, and the margins were briefly `mt-10` to absorb it.
            `FieldRow` now declares two tracks, so the 4 is gone and the arithmetic is honest.

            Except on the edit screen (T-231, Rajeev 2026-09-18). There the chips are replaced by one
            small badge, and 56px under a 22px badge left "Ready by" floating in the middle of the
            card, a gap sized for 44px buttons that are not drawn. The badge belongs with the step's
            title, so it is spaced as a group of its own: `gap-3` (12) plus `mt-3` (12) is 24px, twice
            the 12px between the title and the badge, which is enough to read as a new group and no
            more. The rows below it keep their 56. */}
        <FieldRow className={`${editing ? "mt-3" : "mt-11"} [grid-template-columns:repeat(3,16rem)] ${NARROW_TWO_UP}`}>
          <RowField label="Ready by">
            {(id) => (
              <span className="grid gap-1">
                <input
                  id={id}
                  type="time"
                  value={readyBy}
                  onChange={(e) => setReadyBy(e.target.value)}
                  className={`min-h-touch w-full rounded-control border px-3 ${
                    cannotArrive ? "border-danger" : loadingSqueeze ? "border-warning" : "border-hairline"
                  }`}
                />
                {/* A nudge and never a refusal (Rajeev, 2026-09-05): "It just shows as a warning on
                    the Ready by time field but never stops the user." Nobody here knows how long
                    this temple takes to carry fifty litres of curd rice across a courtyard and load
                    it, so the application does not pretend to — it only says when the plan has left
                    no room for it at all. */}
                {cannotArrive && <span className="text-xs text-danger">{cannotArrive}</span>}
                {loadingSqueeze && (
                  <span className="text-xs text-warning">{loadingSqueeze}</span>
                )}
              </span>
            )}
          </RowField>

          {/* A feast names the festival it is for (item 26). The calendar fills it in, and it is
              still a box: a temple anniversary, or a local festival the calendar does not carry, is
              a feast the temple takes just as much pride in. */}
          {kind?.needsOccasion && (
            <RowField label="What is the occasion?" hint="Filled in from the calendar. You can change it.">
              {(id) => (
                <input
                  id={id}
                  list="meal-occasions"
                  value={occasionName}
                  onChange={(e) => {
                    occasionTouched.current = true;
                    setOccasionName(e.target.value);
                  }}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                />
              )}
            </RowField>
          )}

          {/* The event chain (E4-S15 D6). Each answer reveals the next question and no more:
              the name, then whether it leaves the temple, then how it gets there and who to ring,
              and only for a delivery the address and the hour the guests sit down. An in-house
              event stops at its name — asking a Bhajan Prasadam in the temple hall for a client
              would be asking a question with no answer. Breakfast, Lunch and Dinner never reach
              any of it. */}
          {isEventKind && (
            <RowField
              label="Event name"
              hint="Filled in from events you have planned before"
            >
              {(id) => (
                <input
                  id={id}
                  list="event-names"
                  value={eventName}
                  onChange={(e) => chooseEventName(e.target.value)}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                />
              )}
            </RowField>
          )}
          {isEventKind && (
            <RowField label="Is this going outside?">
              {(id) => (
                <select
                  id={id}
                  value={isOutside ? "yes" : "no"}
                  onChange={(e) => setIsOutside(e.target.value === "yes")}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                >
                  <option value="no">No — we eat it here</option>
                  <option value="yes">Yes — it leaves the temple</option>
                </select>
              )}
            </RowField>
          )}
        </FieldRow>

        {/* The chain is split across rows rather than run along one, because a row of six fields
            overflows a laptop and takes the page's horizontal scrollbar with it — the same density
            complaint the recipe list drew (OUTSTANDING_BUILD_LIST R2). `FieldRow` is `grid-flow-col`
            and deliberately never wraps: it exists so three stacked parts line up across a row, and
            a wrapping version would line them up against fields on a different line. So the caller
            keeps each row to three, and the breaks fall where the questions change subject: what and
            where it is, then who to hand it to, then where it goes and when. */}
        {isEventKind && isOutside && (
        <FieldRow className={`mt-11 [grid-template-columns:repeat(3,16rem)] ${NARROW_TWO_UP}`}>
          {isEventKind && isOutside && (
            <RowField label="Pickup or delivery?">
              {(id) => (
                <select
                  id={id}
                  value={handover}
                  onChange={(e) => setHandover(e.target.value as Handover | "")}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                >
                  <option value="">Which is it?</option>
                  <option value="PICKUP">Pickup — somebody collects it</option>
                  <option value="DELIVERY">Delivery — we take it there</option>
                </select>
              )}
            </RowField>
          )}
          {isEventKind && isOutside && (
            <RowField label="Contact name">
              {(id) => (
                <input
                  id={id}
                  value={contactName}
                  onChange={(e) => setContactName(e.target.value)}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                />
              )}
            </RowField>
          )}
          {isEventKind && isOutside && (
            <RowField label="Contact phone">
              {(id) => (
                <input
                  id={id}
                  type="tel"
                  value={contactPhone}
                  onChange={(e) => setContactPhone(e.target.value)}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                />
              )}
            </RowField>
          )}
        </FieldRow>
        )}

        {isEventKind && isOutside && handover === "DELIVERY" && (
        <FieldRow className={`mt-11 [grid-template-columns:repeat(3,16rem)] ${NARROW_TWO_UP}`}>
          {isEventKind && isOutside && handover === "DELIVERY" && (
            <RowField
              label="Where is it going?"
              hint="Pick an address from the list so we can work out the travel time."
            >
              {(id) => (
                <AddressPicker
                  id={id}
                  value={deliveryAddress}
                  onPick={(place) => {
                    setDeliveryAddress(place.address);
                    setPlaced({
                      placeId: place.placeId,
                      latitude: place.latitude,
                      longitude: place.longitude,
                    });
                  }}
                  onType={(typed) => {
                    setDeliveryAddress(typed);
                    setPlaced(null);
                  }}
                />
              )}
            </RowField>
          )}
          {isEventKind && isOutside && handover === "DELIVERY" && (
            <RowField
              label="Gate or building"
              // Kept off the address on purpose: the van is routed to the main entrance, and this is
              // what the driver asks about on arrival.
              hint="The block, gate or hall the driver should look for."
            >
              {(id) => (
                <input
                  id={id}
                  value={subLocation}
                  onChange={(e) => setSubLocation(e.target.value)}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                />
              )}
            </RowField>
          )}
          {/* Not the ready-by. The food is ready before it leaves, and this is the hour it has to
              be in front of the guests — which is what the travel estimate works backwards from to
              say when to leave the temple (E4-S16 D1). */}
          {isEventKind && isOutside && handover === "DELIVERY" && (
            <RowField label="When do the guests eat?" hint="We work back from this to say when to leave">
              {(id) => (
                <input
                  id={id}
                  type="time"
                  value={guestsEatAt}
                  onChange={(e) => setGuestsEatAt(e.target.value)}
                  className="min-h-touch w-full rounded-control border border-hairline px-3"
                />
              )}
            </RowField>
          )}
          {isEventKind && isOutside && handover === "DELIVERY" && (
            <RowField label="Estimated travel time" hint={travelHint(travelManual, estimate)}>
              {(id) => (
                <span className="flex items-center gap-2">
                  <input
                    id={id}
                    type="number"
                    min={1}
                    max={600}
                    value={travelMinutes}
                    onChange={(e) => {
                      setTravelMinutes(e.target.value);
                      // Touching it makes the figure theirs, and printing the card stops refreshing
                      // it. Nothing else on this form needs to know.
                      setTravelManual(true);
                    }}
                    className="min-h-touch w-24 rounded-control border border-hairline px-3 tabular-nums"
                  />
                  <span className="text-sm text-ink-secondary">minutes</span>
                </span>
              )}
            </RowField>
          )}
        </FieldRow>
        )}
        {isEventKind && isOutside && handover === "DELIVERY" && leaveByLine(travelMinutes, guestsEatAt) && (
          // The arithmetic, said out loud. A planner can act on "leave the temple by 11:15" and
          // nobody can act on "45", which is the whole reason the figure is worth collecting.
          <p className="mt-2 text-sm text-ink-secondary">
            {leaveByLine(travelMinutes, guestsEatAt)}
          </p>
        )}

        {/* Outside the row on purpose: a datalist is invisible, but a fourth child inside a
            three-track field would be a fourth cell for the row to reason about. */}
        {kind?.needsOccasion && (
          <datalist id="meal-occasions">
            {occasions.map((name) => (
              <option key={name} value={name} />
            ))}
          </datalist>
        )}

        {/* The events this temple has run before, for the same reason and in the same shape. */}
        {isEventKind && (
          <datalist id="event-names">
            {eventSuggestions.map((s) => (
              <option key={s.eventName} value={s.eventName} />
            ))}
          </datalist>
        )}
      </section>

      {/* 2 — who is expected */}
      <section className="card grid gap-3 p-5">
        {/* An event says so here rather than letting somebody find out by pressing Save (D2). The
            three main meals get no such line: they are refused without a head count exactly as they
            were, and a sentence saying the count is needed would be new text on a screen this story
            promised not to change. */}
        <Step
          n={2}
          title="Who is expected"
          hint={isEventKind ? "Optional for events. You set each dish’s amount below." : undefined}
        />
        <FieldRow className={NARROW_TWO_UP}>
          <Counter label="Adults" value={adults} onChange={(v) => setCount("adults", v ?? 0)} />
          <Counter label="Children" value={children} onChange={(v) => setCount("children", v ?? 0)} />
          <Counter label="Seniors" value={seniors} onChange={(v) => setCount("seniors", v ?? 0)} />
          {/* People, not servings. The dishes below each scale to their own unit now, so one
              number here cannot stand for all of them.

              The portion weights are here rather than on the three counters. Each of them carried
              its own — "A full portion", "0.6 of a portion", "0.8 of a portion" — and by the letter
              of the rule those are three derivations and would be three "i"s in a row, on three
              adjacent boxes, saying one thing between them. They are the arithmetic behind this
              readout, so they sit on the figure they produce, once. */}
          <Readout
            label="Cooking for"
            hint="An adult counts as a full portion, a child as 0.6 and a senior as 0.8."
            value={`${headCount.toLocaleString("en-IN")} people`}
          />
        </FieldRow>
      </section>

      {/* 3 — preparations */}
      <section className="card grid gap-3 p-5">
        <Step
          n={3}
          title="Preparations"
          hint="Increase any dish that usually runs out."
        />

        {/* Why the list is short, in the calendar's own words for the day, and the way out for
            somebody who means to cook a grain preparation anyway. Beside the list rather than
            behind a menu: an escape nobody can find is not an escape. */}
        {isEkadashi && (
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs text-ink-muted">
              {ekadashiLabel(ekadashiName)}.{" "}
              {fastingList.status === "loading"
                ? "Checking which preparations suit the fast."
                : filtering
                  ? "Grain and bean preparations are hidden."
                  : "Every preparation is listed."}
            </span>
            {fastingList.status === "ready" && (
              <Button
                type="button"
                size="sm"
                variant="secondary"
                aria-pressed={showGrains}
                onClick={() => setShowGrains((on) => !on)}
              >
                {showGrains ? "Hide grain preparations" : "Show grain preparations too"}
              </Button>
            )}
          </div>
        )}

        {history && (
          <InlineNotice
            tone={menuUsed ? "success" : "info"}
            autoDismiss={menuUsed}
            title={
              menuUsed
                ? `Last ${history.occasionName}’s menu has been added.`
                : `Last ${history.occasionName}, ${longDate(history.lastCookedOn ?? "")} — ${
                    history.preparationCount
                  } ${history.preparationCount === 1 ? "preparation" : "preparations"}.`
            }
            action={
              !menuUsed && history.preparations.length > 0 ? (
                <Button type="button" size="sm" variant="secondary" onClick={useLastMenu}>
                  Use this menu
                </Button>
              ) : undefined
            }
          >
            {history.missingCount > 0 && (
              <>
                {history.missingCount} of last year’s {history.preparationCount} preparations
                are no longer in your recipes.
              </>
            )}
          </InlineNotice>
        )}

        {loadingFastingList && (
          <span className="inline-flex items-center gap-2 text-sm text-ink-muted">
            <BusyPot />
            Loading preparations…
          </span>
        )}

        <div className="grid gap-x-6 gap-y-2 sm:grid-cols-2 xl:grid-cols-3">
          {visible.map((recipe) => {
            const draft = picked.find((d) => d.recipeId === recipe.id);
            return (
              // One row per dish, ticked or not: the name on the left and, once ticked, the amount
              // box and its unit on the same line to its right (Rajeev, 2026-09-18, T-237). The box
              // used to sit on a line of its own under the name, and because the grid gives every
              // cell in a row the height of the tallest, one ticked dish left its neighbours standing
              // over ~50px of nothing. Measured before and after in the proof (T-237).
              //
              // A grid rather than a flex row, so a refused amount's red sentence goes under the
              // box and not beside it (Rajeev, 2026-09-18). `Form` places its error slot straight
              // after the box, which in a flex row made it a third item on the line: it took the
              // room the unit had, and "L · set by hand" was squeezed into a column that broke
              // over three lines. Here the name, the box and the unit are pinned to the first row
              // and the slot, whatever its position in the markup, spans the row beneath from the
              // box's left edge — so the red sentence is the only thing that makes a ticked dish
              // taller. The slot is still `Form`'s own, so the sentence stays tied to the box by
              // aria-describedby exactly as before.
              //
              // The slot is `w-0 min-w-full`: it fills the two columns it spans but asks nothing of
              // their width. Without that, the sentence ("Amount of Arbi ki Sabzi (Haryana) can be
              // at most 50,000") sized the two `auto` columns to its own length and squeezed the
              // name's column to 0px, so the name broke a word per line (measured: a 138px cell).
              //
              // `content-start` because the outer grid stretches every cell in a row to the tallest:
              // without it an unticked neighbour's one row stretched too and `items-center` floated
              // its name down to the middle of the cell, out of line with the ticked dish's name.
              // The name itself is `self-start` for the same reason on a smaller scale: the box is
              // 44px and a name with its category 36px, so centring put a ticked dish's name 4px
              // below its unticked neighbours' (measured 677 against 673).
              <div
                key={recipe.id}
                className="grid grid-cols-[minmax(0,1fr)_auto_auto] content-start items-center gap-x-2 border-t border-hairline py-2 first:border-t-0 sm:border-t-0 [&>[data-form-error-slot]]:col-span-2 [&>[data-form-error-slot]]:col-start-2 [&>[data-form-error-slot]]:row-start-2 [&>[data-form-error-slot]]:w-0 [&>[data-form-error-slot]]:min-w-full"
              >
                <label className="col-start-1 row-start-1 flex cursor-pointer items-start gap-2 self-start">
                  <input
                    type="checkbox"
                    checked={Boolean(draft)}
                    onChange={() => toggle(recipe.id)}
                    className="mt-1 h-4 w-4 flex-none accent-accent"
                  />
                  <span className="grid min-w-0">
                    <span className="text-sm text-ink">{recipe.name}</span>
                    <span className="text-xs text-ink-muted">{recipe.categoryName}</span>
                  </span>
                </label>

                {draft && (
                  <>
                    {/* `max` is the server's ceiling on a dish's amount and the scaler's (T-217):
                        past 50,000 the Today screen cannot scale the dish and fails for everyone.
                        `Form` reads it off the element and puts the refusal under this box in red
                        on the press, so the button stays live for it — adding it to firstBlocker
                        would disable the button and the red sentence would never be reached. */}
                    <input
                      type="number"
                      min={0}
                      max={MAX_TARGET_YIELD}
                      step="any"
                      aria-label={`Amount of ${recipe.name}`}
                      value={draft.target ?? ""}
                      onChange={(e) => setTarget(recipe.id, e.target.value)}
                      className={[
                        "col-start-2 row-start-1 min-h-touch w-20 rounded-control border px-2 text-sm tabular-nums",
                        draft.target === null || !(draft.target > 0) || draft.target > MAX_TARGET_YIELD
                          ? "border-warning"
                          : "border-hairline",
                      ].join(" ")}
                    />
                    <span className="col-start-3 row-start-1 max-w-20 text-xs text-ink-muted">
                      {/* The unit is the recipe's, never chosen here — nobody can plan ten litres
                          of a dry podi. Written the way it is said rather than lower-cased: a
                          litre is "L", and toLowerCase() rendered it as the digit-like "l". */}
                      {unitLabel(recipe.baseYieldUnit)}
                      {draft.overridden && draft.target !== targetFor(recipe.id, headCount) && (
                        <> · set by hand</>
                      )}
                    </span>
                  </>
                )}
              </div>
            );
          })}
        </div>
      </section>

      {/* 4 — who will run it. After the preparations and not before (Q10): the crew a meal takes
          depends on what is being cooked as much as on how many are eating, and three preparations
          for 133 and eight for 133 are not the same morning’s work. */}
      <section className="card grid gap-3 p-5">
        <Step n={4} title="Who will run it" hint="Any mix of staff and volunteers" />
        {/* The two numbers and the button that acts on them, in one row of three equal columns that
            share the card's whole width, rather than the button dropping to a row of its own under a
            stretch of white space (Rajeev, 2026-09-17). The counter centres its controls in the
            wider box, and the Rostered sentence gets room to sit on one line. */}
        <FieldRow className={`[grid-template-columns:repeat(3,minmax(16rem,1fr))] ${NARROW_TWO_UP}`}>
          <Counter
            label="People needed"
            hint="Leave it empty until you know"
            value={crewRequired}
            onChange={(v) => {
              crewTouched.current = true;
              setDirty(true);
              setCrewRequired(v === null ? null : Math.max(0, v));
            }}
          />
          <Readout
            label="Rostered"
            value={rosterReadout(roster, crewRequired)}
            // Quiet, and only a warning. A meal is planned weeks before anybody is rostered, so
            // being short of hands today says nothing about the plan and never blocks saving it.
            tone={crewRequired != null && roster != null && roster.rostered < crewRequired ? "warning" : "neutral"}
          />
          {/* Asking for volunteers, beside the two numbers that say whether any are needed (D-27, the
              first of the screens that change). *Ask for volunteers* appears the moment People needed
              is more than Rostered, and not at equal: a covered meal is not short. Once a shift has
              been drafted here, or the meal already has one, *View volunteer shift* takes its place
              whatever the numbers say, so a shift can always be opened. Both open the same layer, and
              neither saves anything — the meal's own Save or Update does. The empty first cell sits in
              the shared label track, so the button lines up with the two boxes, not their labels. */}
          {(shiftDraft || liveShift || shortBy > 0) && (
            <span className="contents">
              <span aria-hidden="true" />
              <Button
                type="button"
                size="sm"
                variant="secondary"
                icon="hand-stop"
                aria-haspopup="dialog"
                onClick={() => setShiftOpen(true)}
                className="h-full w-full justify-center"
              >
                {shiftDraft || liveShift ? "View volunteer shift" : "Ask for volunteers"}
              </Button>
            </span>
          )}
        </FieldRow>
        {(shiftDraft || liveShift) && (
          <span className="text-sm text-ink-secondary">{shiftLine(liveShift, shiftDraft)}</span>
        )}
      </section>

      {/* 5 — notes. In a card of their own, numbered like the four above (Rajeev, Decisions Desk,
          2026-09-18): the two boxes used to sit loose under the step cards, so they read as an
          afterthought to step 4 rather than as the last thing a meal's plan carries. The same card,
          padding and gap as every step, so the column of cards stays one rhythm. */}
      <section className="card grid gap-3 p-5">
        <Step n={5} title="Notes" />
        <label className="grid gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes for the kitchen</span>
          <textarea
            rows={3}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Cook the kheer thin — the seniors prefer it that way."
            className="rounded-control border border-hairline px-3 py-2 text-ink"
          />
        </label>

        {/* The mirror of the kitchen's notes, for the people handing food out. It exists because the
            job card's serving sheet had nothing to say on it: the meal carried notes for the kitchen
            and no equivalent for the servers, so the sheet was boxes and signatures alone. */}
        <label className="grid gap-1 text-sm text-ink-secondary">
          <span className="pl-field-inset font-medium text-ink">Notes for the servers</span>
          <textarea
            rows={3}
            value={serverNotes}
            onChange={(e) => setServerNotes(e.target.value)}
            placeholder="Serve the children first, and keep a tray back for the kitchen."
            className="rounded-control border border-hairline px-3 py-2 text-ink"
          />
        </label>
      </section>

      {isEkadashi && (
        <div>
          {/* Information: it states the day. The grain confirm is the warning, and it stays amber. */}
          <Badge tone="info">Fasting day — grain preparations will ask you to confirm</Badge>
        </div>
      )}
    </div>
  );

  return (
    <>
      <Form
        id={formId}
        aria-label={editing ? `Edit ${kindName}` : "Plan a meal"}
        // Any box typed in, ticked or chosen is a change somebody made. React's change event bubbles to
        // the form from every control inside it, so one listener covers them all; the controls that are
        // buttons mark the form themselves.
        onChange={() => setDirty(true)}
        onSubmit={(e) => {
          e.preventDefault();
          if (!blocked && !busy) save(false);
        }}
      >
        {body}
      </Form>

      {/* Beside the form, never inside it. The layer holds the volunteers' own `<form>`, and a form
          inside a form is invalid HTML whose submit would also reach this one and save the meal. */}
      {shiftOpen && (
        <ShiftLayer
          date={date}
          mealKind={kindName}
          mealEventName={isEventKind ? eventName.trim() || null : null}
          readyBy={readyBy}
          suggestedCapacity={Math.max(1, shortBy)}
          values={shiftDraft ? { ...shiftDraft, shiftDate: date } : liveShift}
          saved={liveShift !== null}
          onClose={closeShift}
          onDone={(draft) => {
            setShiftDraft(draft);
            setDirty(true);
            setShiftOpen(false);
          }}
        />
      )}

      {/* D-27 answer 6: warn before the save, keep their places, tell them after it. The sentence is
          the ruled one, word for word, about times and never about a shift being shifted. */}
      {confirmTimes && liveShift && (
        <ConfirmLayer
          title="The volunteer shift has new times"
          confirmLabel="Update this meal"
          dismissLabel="Go back"
          busy={busy}
          onDismiss={dismissTimes}
          onConfirm={() => save(false, true)}
        >
          <p>{timesChangedWarning(liveShift.signedUpCount)}</p>
        </ConfirmLayer>
      )}

      {leaveGuard}
    </>
  );
}

/**
 * The preparations of a meal that are still open, as drafts.
 *
 * <p>A cooked or cancelled row is not offered for editing: what was cooked drew stock against a
 * figure, and rewriting the figure afterwards would leave the ledger describing a meal that never
 * happened. A servings figure that does not match the meal's own head count was set by hand, so it
 * is marked as such and a later change to the count leaves it alone.
 */
function openDrafts(meal: MealView | undefined): Draft[] {
  if (!meal) return [];
  const drafts: Draft[] = [];
  const seen = new Set<string>();
  for (const dish of meal.dishes) {
    if (dish.status !== "PLANNED" || seen.has(dish.recipeId)) continue;
    seen.add(dish.recipeId);
    drafts.push({
      recipeId: dish.recipeId,
      target: Number(dish.targetYield),
      // A saved quantity is treated as set by hand, whatever produced it. Recomputing it here to
      // decide would need the recipe list, which this function does not have — and would risk
      // silently rewriting a figure somebody chose. The head count stops driving it either way.
      overridden: true,
      dishId: dish.id,
    });
  }
  return drafts;
}

/**
 * The line beside *View volunteer shift*: how the saved shift stands, and whether anything drafted here
 * is still waiting for the meal's own save.
 *
 * <p>Said because *Done* saves nothing, and a planner who pressed it and then looked at a list of
 * shifts elsewhere would otherwise not find theirs there and not know why.
 */
function shiftLine(saved: ShiftView | null, draft: MealShiftDraft | null): string {
  if (!saved) return "Saved when you save this meal.";
  const signedUp = `${saved.signedUpCount} of ${draft?.capacity ?? saved.capacity} signed up`;
  return draft ? `${signedUp}. Your changes are saved with this meal.` : signedUp;
}

/**
 * The three figures step 4 reads, whether they came from the meal's crew row or, before its first
 * save, from the count at its date and ready-by (T-215). Both are counted the same way on the server.
 */
type Roster = Pick<MealCrewView, "staffIn" | "volunteers" | "rostered">;

/**
 * "3 staff · 2 volunteers · 5 of 8" — who is rostered over this meal, against what it takes.
 *
 * <p>Nothing counted is <em>not knowing</em> rather than nobody: the day may be fully staffed. Since
 * T-215 a meal not yet saved is counted at its date and ready-by, so this is left for the count that
 * could not be fetched and the meal with no ready-by yet. It used to say "0 of 8" there, which is a
 * count the screen had not made — the same mistake in the opposite direction to counting an uncrewed
 * meal as covered (E6-S15).
 */
function rosterReadout(crew: Roster | null, required: number | null): string {
  if (!crew) return "Not counted yet";
  const parts = [
    `${crew.staffIn} staff`,
    `${crew.volunteers} ${crew.volunteers === 1 ? "volunteer" : "volunteers"}`,
  ];
  if (required != null) parts.push(`${crew.rostered} of ${required}`);
  return parts.join(" · ");
}

/**
 * The numbered heading. The order is the order a kitchen decides a meal in, so it is a sequence.
 *
 * <p>A step's own guidance is in an "i" beside its title, for the same reason a field's is: it sits
 * over four or five controls, so there is no one box for it to be under, and as a line of muted text
 * it read as part of the heading rather than as something to consult.
 */
function Step({ n, title, hint }: { n: number; title: string; hint?: string }) {
  return (
    <span className="flex flex-wrap items-center gap-3">
      <span className="flex h-6 w-6 flex-none items-center justify-center rounded-full bg-ink text-xs font-semibold text-ink-inverse">
        {n}
      </span>
      <span className="flex items-center gap-1.5">
        <span className="text-base font-medium text-ink">{title}</span>
        {hint && <InfoHint text={hint} label={title} />}
      </span>
    </span>
  );
}

/** The label and its "i", in the row's first track. One shape for a field, a counter and a readout. */
const ROW_LABEL = `${FIELD_LABEL} flex items-center gap-1.5`;

/**
 * One field in a {@link FieldRow}: its label with its "i", and its control.
 *
 * <p>Two tracks of the row's three, since 2026-09-04. The guidance that used to sit under the box is
 * in the label's "i", so nothing is ever drawn in the hint track — and the row still reserves it,
 * because {@link FieldRow} is shared with Settings and cannot be narrowed from here. What that costs
 * is the 4px `gap-y-1` above an empty track, which the callers' own margins are set against.
 *
 * <p>The control is a child render function taking the id it must carry, and that is not ceremony —
 * it is the trap {@link HintedField} was given the same shape to avoid. A `<label>`'s control is its
 * <em>first labelable descendant</em> and a `<button>` is labelable, so an "i" inside the label
 * would quietly become the labelled thing and the input beside it would lose its own name. An
 * explicit `htmlFor` cannot make that mistake.
 *
 * <p>The wrapper stays a `contents` span rather than becoming a fragment: `display: contents` still
 * passes its inherited type down, and the boxes are set in `text-sm` from here.
 */
function RowField({
  label, hint, children,
}: {
  label: string;
  hint?: string;
  children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <span className="contents text-sm text-ink-secondary">
      <span className={ROW_LABEL}>
        <label htmlFor={id}>{label}</label>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      {children(id)}
    </span>
  );
}

/**
 * What the "i" beside the travel box says, which depends on whose figure is in it.
 *
 * <p>The two states are genuinely different facts and saying the same thing in both would be a lie
 * in one of them. Untouched, the number is Google's and printing the card will refresh it. Edited,
 * the number is a person's and printing leaves it alone — so the note says so, and offers Google's
 * current opinion beside it rather than acting on it.
 */
function travelHint(manual: boolean, estimate: TravelEstimate | null): string {
  if (manual) {
    const google =
      estimate?.available && estimate.pessimisticMinutes != null
        ? ` Google says ${estimate.optimisticMinutes}–${estimate.pessimisticMinutes} minutes.`
        : "";
    return `You set this.${google}`;
  }
  // Untouched, the figure is Google's and is worked out again when the job card prints; typed over,
  // the person's figure is the one that prints. The hint used to say all of that in three sentences
  // (T-223 content audit); a hint is one sentence, so the mechanics live here.
  if (estimate?.available && estimate.pessimisticMinutes != null) {
    return `From Google Maps: ${estimate.optimisticMinutes}–${estimate.pessimisticMinutes} minutes at that hour.`;
  }
  return "Minutes to drive there.";
}

/**
 * The smallest gap between "cooked" and "the van leaves" that this application will not warn about.
 *
 * <p>Fixed rather than configured, and Rajeev asked the question directly. It is a warning
 * threshold, not an input to any sum — nothing is computed from it and nothing is printed from it,
 * so it does not need to be right for a particular temple, only right enough to catch a plan that
 * cannot happen. Making it a setting would ask every temple a question most of them would answer
 * with whatever default appeared, and buy a column, a form control and a migration for it. If a
 * temple tells us thirty is wrong, that is the moment to make it theirs — with a direction and a
 * figure, rather than a guess with a text box round it.
 */
/**
 * A row of three 16rem fields is 50rem, and the card has that much room only from `xl` (1280px):
 * below it — a tablet, or a laptop narrower than 1184px beside the sidebar — the row ran past the
 * card and took the page sideways with it. Below `xl` the row goes two to a line instead, and
 * `FieldRow`'s own rule still stacks it to one below `sm`.
 */
const NARROW_TWO_UP =
  "max-xl:grid-flow-row max-xl:gap-y-4 max-xl:![grid-template-columns:repeat(2,minmax(0,1fr))]";

const LOADING_MINUTES = 30;

/** "45" out of a text box, or null. Anything that is not a positive whole number is not a figure. */
function positiveOrNull(raw: string): number | null {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? Math.round(n) : null;
}

/**
 * The arithmetic said out loud, under the boxes it comes from.
 *
 * <p>A planner can act on "leave the temple by 11:15" and nobody can act on "45" — the same reason
 * the job card prints a departure time rather than a duration.
 */
function leaveByLine(minutes: string, guestsEatAt: string): string | null {
  const allow = positiveOrNull(minutes);
  if (!allow || !/^\d{2}:\d{2}$/.test(guestsEatAt)) return null;
  const [h, m] = guestsEatAt.split(":").map(Number);
  const at = h * 60 + m - allow;
  if (at < 0) return "That is longer than the time left before the guests eat.";
  const hh = String(Math.floor(at / 60)).padStart(2, "0");
  const mm = String(at % 60).padStart(2, "0");
  return `Leave the temple by ${hh}:${mm} to be there before ${guestsEatAt}.`;
}

/**
 * A figure the form worked out rather than asked for.
 *
 * <p>Same three parts as every other field in the row — the label above the box, not inside it —
 * so it is a peer of the counters beside it and not a shape of its own. Its label being inside its
 * box is what made this pill impossible to align and what two previous fixes were aimed at.
 *
 * <p>A readout takes a warning tone when the figure is short of what the form was told it needs.
 * Quiet, and never a block: it is telling the planner something, not refusing them.
 */
function Readout({
  label,
  hint,
  value,
  tone = "neutral",
}: {
  label: string;
  /** The arithmetic behind the figure, on the figure rather than on the boxes that feed it. */
  hint?: string;
  value: string;
  tone?: "neutral" | "warning";
}) {
  return (
    <span className="contents">
      <span className={ROW_LABEL}>
        <span>{label}</span>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      <span
        className={[
          "flex items-center rounded-control px-5 py-2 text-lg font-semibold leading-snug tabular-nums",
          tone === "warning" ? "bg-warning-bg text-warning" : "bg-sunken text-ink",
        ].join(" ")}
      >
        {value}
      </span>
    </span>
  );
}

function Counter({
  label, hint, value, onChange,
}: {
  label: string;
  hint?: string;
  /** Null draws an empty box — an honest answer where nobody has said, and not a nought. */
  value: number | null;
  onChange: (value: number | null) => void;
}) {
  return (
    <span className="contents">
      {/* Not a `<label>`, and not now either: the counter is three controls in one box — a minus, a
          figure and a plus — each carrying its own name. The "i" sits beside the word the way it
          does on a field. */}
      <span className={ROW_LABEL}>
        <span>{label}</span>
        {hint && <InfoHint text={hint} label={label} />}
      </span>
      <span className="flex items-center justify-center gap-2 rounded-control bg-sunken px-3 py-1">
        <button
          type="button"
          aria-label={`One fewer ${label.toLowerCase()}`}
          onClick={() => onChange((value ?? 0) - 1)}
          className="min-h-touch w-9 rounded-control text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          −
        </button>
        <input
          type="number"
          min={0}
          aria-label={label}
          value={value ?? ""}
          onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
          className="w-16 bg-transparent text-center text-base tabular-nums text-ink outline-none"
        />
        <button
          type="button"
          aria-label={`One more ${label.toLowerCase()}`}
          onClick={() => onChange((value ?? 0) + 1)}
          className="min-h-touch w-9 rounded-control text-lg text-ink-secondary transition-colors duration-state hover:bg-hairline"
        >
          +
        </button>
      </span>
    </span>
  );
}
