/**
 * The single way this application talks to the backend.
 *
 * <p>Everything goes through here so that the error contract is honoured in exactly one place.
 * A failed request produces an ApiError carrying the reference code, the plain-language message,
 * and any per-field messages — never a raw status or a thrown string, because those are what end
 * up rendered to a temple administrator by accident.
 */


export interface FieldError {
  field: string;
  message: string;
}

export interface ErrorPayload {
  code: string;
  message: string;
  action: string;
  fieldErrors: FieldError[];
}

export class ApiError extends Error {
  readonly code: string;
  readonly action: string;
  readonly fieldErrors: FieldError[];
  /**
   * The HTTP status the server answered with, or 0 when the request never got an answer at all.
   *
   * <p>Added 2026-08-30 because the two were indistinguishable and one caller genuinely had to tell
   * them apart. `/whoami` answers 401 with an empty body for somebody with no account here, so the
   * synthesised envelope below gave it the same KMS-0000 as a dropped connection — and the session
   * layer, unable to see the difference, told a person whose server was merely restarting that they
   * belonged to no temple. Nothing else needs this, and nothing else should reach for it: a screen
   * that branches on a status number instead of on a KMS code is a screen drifting away from the
   * error contract.
   */
  readonly status: number;

  constructor(payload: ErrorPayload, status = 0) {
    super(payload.message);
    this.name = "ApiError";
    this.code = payload.code;
    this.action = payload.action;
    this.fieldErrors = payload.fieldErrors ?? [];
    this.status = status;
  }

  /** Field errors keyed by field name, for rendering beside the input that caused them. */
  byField(): Record<string, string> {
    return Object.fromEntries(this.fieldErrors.map((e) => [e.field, e.message]));
  }
}

/**
 * Coerces anything thrown into the {@link ApiError} the UI knows how to render.
 *
 * <p>A request that never reached the backend — a dropped connection, a DNS failure — has no
 * reference code of its own, so it borrows KMS-0000 and a caller-supplied sentence. Everything
 * the backend refused already arrives as an ApiError and passes straight through.
 */
export function toApiError(caught: unknown, message = "Something went wrong."): ApiError {
  return caught instanceof ApiError
    ? caught
    : new ApiError({
        code: "KMS-0000",
        message,
        action: "Check your connection and try again.",
        fieldErrors: [],
      });
}

/** True when the request never reached the server, or the server was too broken to answer it. */
export function isUnreachable(error: ApiError): boolean {
  return error.status === 0 || error.status >= 500;
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

/**
 * Which temple the next request speaks for. A person may serve at several; the server accepts this
 * only after matching it against their own memberships, so it selects rather than grants.
 */
const ACTIVE_TEMPLE_KEY = "kms.activeTemple";

export function activeTempleId(): string | null {
  return typeof window === "undefined" ? null : window.localStorage.getItem(ACTIVE_TEMPLE_KEY);
}

export function setActiveTempleId(tenantId: string | null) {
  if (typeof window === "undefined") return;
  if (tenantId) window.localStorage.setItem(ACTIVE_TEMPLE_KEY, tenantId);
  else window.localStorage.removeItem(ACTIVE_TEMPLE_KEY);
}

async function request<T>(
  path: string,
  init: RequestInit & { token?: string } = {}
): Promise<T> {
  const { token, ...rest } = init;

  const response = await fetch(`${BASE_URL}${path}`, {
    ...rest,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(activeTempleId() ? { "X-KMS-Temple": activeTempleId() as string } : {}),
      ...rest.headers,
    },
  });

  if (response.ok) {
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  }

  // The backend always returns the error contract. If it somehow didn't — a proxy timeout,
  // a network failure — synthesise the same shape so callers never have to handle two kinds
  // of failure, and the user still gets something quotable.
  let payload: ErrorPayload;
  try {
    payload = (await response.json()) as ErrorPayload;
    if (!payload?.code) throw new Error("unrecognised");
  } catch {
    payload = {
      code: "KMS-0000",
      message: "We couldn't reach the server.",
      action: "Check your connection and try again.",
      fieldErrors: [],
    };
  }

  throw new ApiError(payload, response.status);
}

export interface TenantSummary {
  id: string;
  slug: string;
  name: string;
  timezone: string;
  currency: string;
  is_80g_approved: boolean;
  created_at: string;
  user_count: number;
}

/** One temple's detail (the list row plus its address), for the view page. */
export interface TenantDetail extends TenantSummary {
  address: string | null;
  /** When this temple was last exported, or null if it never has been (E1-S15). */
  last_export_at: string | null;
}

export interface ProvisionTenantInput {
  name: string;
  slug: string;
  address: string;
  latitude: number;
  longitude: number;
  timezone: string;
  currency: string;
  is80gApproved: boolean;
  adminName: string;
  adminEmail: string;
  adminPhone: string;
}

export interface AuditEventView {
  id: string;
  action: string;
  entityType: string;
  entityId: string;
  actorUserId: string;
  actorLabel: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  reason: string | null;
  createdAt: string;
}

export interface AuditPage {
  events: AuditEventView[];
  /** Echo back to `cursor` for the next page; null when this is the last. */
  nextCursor: string | null;
}

export interface AuditFilters {
  from?: string;
  to?: string;
  action?: string;
  actor?: string;
  cursor?: string;
  limit?: number;
}

/** Serialises only the filters that are actually set, so absent ones don't narrow the query. */
function toQuery(filters: AuditFilters): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

export type NotificationChannel = "WHATSAPP" | "SMS" | "EMAIL";
/** Every role a signed-in principal can have. SUPER_ADMIN belongs to no tenant. */
export type PrincipalRole =
  | "SUPER_ADMIN"
  | "TEMPLE_ADMIN"
  | "KITCHEN_MANAGER"
  | "KITCHEN_STAFF"
  | "VOLUNTEER";
/** Roles a Temple Admin may assign — SUPER_ADMIN is deliberately excluded. */
export type UserRole = "TEMPLE_ADMIN" | "KITCHEN_STAFF" | "VOLUNTEER";

/** The application's own view of the signed-in user, resolved from the verified token. */
export interface WhoAmI {
  userId: string;
  tenantId: string | null;
  role: PrincipalRole;
  /** The person's own name, so the app can address them by it rather than as "you". */
  fullName: string;
  /** The temple's name for the menu. Null for a platform operator, who belongs to no temple. */
  tenantName: string | null;
  /** The temple's slug, which its public pages — giving, the wish list — live under. */
  tenantSlug: string | null;
  /** Every temple this person serves at, oldest first — the first of them is their home temple. */
  temples: TempleMembership[];
  /**
   * Which colour scheme this temple works in, for every person who serves there whatever their
   * role. Null when the temple has never chosen, and for a platform operator, who belongs to no
   * temple — both mean the default.
   *
   * <p>The identifier only: the palettes are in `lib/theme-packs.ts`, so the browser already holds
   * every colour and needs only to be told which set to use. Carried on the session rather than
   * fetched on its own, so switching temples repaints without anybody arranging for it to.
   */
  themeId: string | null;
}

export interface TempleMembership {
  id: string;
  name: string;
}

/** A temple as someone choosing one sees it: what it is called, where it is, and how far away. */
export interface TempleSummary {
  id: string;
  name: string;
  address: string | null;
  /** Present only when the search knew where to measure from. */
  distanceKm: number | null;
}

export interface JoinTempleInput {
  firstName: string;
  lastName: string;
  phone: string;
  email?: string | null;
}
export type UserStatus = "ACTIVE" | "DISABLED";

export interface UserSummary {
  id: string;
  fullName: string;
  email: string;
  phone: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
}

export interface Profile {
  fullName: string;
  email: string;
  phone: string;
  preferredChannel: NotificationChannel;
  /** When and to which wording the user last consented; null until they do. */
  consentAt: string | null;
  consentVersion: string | null;
  /** True when consent is missing or was given against an older wording. */
  consentNeeded: boolean;
  currentConsentVersion: string;
  consentText: string;
  role: string;
}

export interface HealthStatus {
  /** "UP" when healthy, "DOWN" otherwise. */
  status: string;
  /** "UP" / "DOWN". */
  db: string;
  /** RUNNING, STANDBY, ABSENT (not on this instance), or ERROR. */
  scheduler: string;
  /**
   * Whether a background worker is alive anywhere — RUNNING, STALE, ABSENT or UNKNOWN. Read from
   * the clustered job store, not from this instance, because jobs run in their own service.
   */
  worker: string;
  timestamp: string;
}

/** Platform-wide notification-send figures for the Super-Admin Operations page. */
export interface NotificationMetrics {
  sentToday: number;
  failedToday: number;
  /**
   * Seven days, oldest first; the last entry is today. Each day's `sent`/`failed` is twelve
   * two-hour buckets (index 0 = 00:00–02:00 … index 11 = 22:00–24:00), in the platform timezone.
   */
  days: { date: string; sent: number[]; failed: number[] }[];
}

// --- Recipes (Epic 2) -------------------------------------------------
export interface RecipeCategory {
  id: string;
  name: string;
  fastingCompatible: boolean;
}

export interface RecipeSummary {
  id: string;
  name: string;
  categoryName: string;
  fastingCompatible: boolean;
  baseYieldQty: number;
  baseYieldUnit: string;
  status: string;
  sattvicOverridden: boolean;
  /** What the source said the yield was — "300 idlis (3 per devotee)". */
  yieldNote: string | null;
  /**
   * What one person eats, in the recipe's own yield unit. The planner multiplies a head count by
   * this; where it is null nobody serves the dish by the head and the planner asks instead.
   */
  perHeadQty: number | null;
  perHeadUnit: string | null;
  fromLibrary: boolean;
}

/** One row of the Recipes page's single box, from the temple's own list or the shared library. */
export interface RecipeSearchResult {
  origin: "MINE" | "LIBRARY";
  id: string;
  name: string;
  subtitle: string | null;
  categoryName: string;
  /** The state a library recipe came from; null for the temple's own. */
  state: string | null;
  /** Whether to print the state beside the name — false where the name already carries it. */
  showState: boolean;
  badge: string | null;
  /** Library rows only: already in this temple's list, so no plus. */
  alreadyAdded: boolean;
  status: string | null;
  sattvicOverridden: boolean;
}

export interface MasterRecipeIngredient {
  name: string;
  qty: string;
  qtyValue: number;
  qtyUnit: string;
  scaled: Record<string, string> | null;
}

/** A library recipe in full. Read-only to a temple; an operator may edit one. */
export interface MasterRecipeDetail {
  id: string;
  name: string;
  displayName: string;
  subtitle: string | null;
  categoryKey: string;
  categoryName: string;
  state: string;
  region: string | null;
  badge: string;
  yieldText: string;
  yieldQty: number;
  yieldUnit: string;
  perHeadText: string | null;
  perHeadQty: number | null;
  perHeadUnit: string | null;
  indicativeCost: number | null;
  why: string;
  cateringNote: string | null;
  noteStart: string | null;
  noteVessel: string | null;
  noteSeason: string | null;
  tags: string[];
  serveWith: string[];
  ingredients: MasterRecipeIngredient[];
  method: string[];
  sourceRef: string;
  alreadyAdded: boolean;
}

export interface MasterRecipeSummary {
  id: string;
  displayName: string;
  subtitle: string | null;
  categoryName: string;
  state: string;
  badge: string;
  showState: boolean;
  alreadyAdded: boolean;
}

export interface RecipeIngredientView {
  ingredientId: string;
  ingredientName: string;
  quantity: number;
  unit: string;
  sattvicProhibited: boolean;
}

export interface RecipeDetail {
  id: string;
  name: string;
  categoryId: string;
  categoryName: string;
  fastingCompatible: boolean;
  baseYieldQty: number;
  baseYieldUnit: string;
  method: string | null;
  notes: string | null;
  regionTag: string | null;
  yieldNote: string | null;
  perHeadQty: number | null;
  perHeadUnit: string | null;
  subtitle: string | null;
  badge: string | null;
  indicativeCost: number | null;
  why: string | null;
  cateringNote: string | null;
  subRegion: string | null;
  noteStart: string | null;
  noteVessel: string | null;
  noteSeason: string | null;
  tags: string[];
  serveWith: string[];
  /** Where this copy came from, or null where it was written here. */
  masterRecipeId: string | null;
  status: string;
  sattvicOverrideReason: string | null;
  version: number;
  ingredients: RecipeIngredientView[];
  createdAt: string;
}

export interface ScaledLine {
  ingredientId: string;
  ingredientName: string;
  rawQuantity: number;
  rawUnit: string;
  displayQuantity: number;
  displayUnit: string;
  sattvicProhibited: boolean;
}

export interface ScaledRecipe {
  id: string;
  name: string;
  baseYieldQty: number;
  baseYieldUnit: string;
  targetYield: number;
  ratio: number;
  ingredients: ScaledLine[];
}

export interface DocumentView {
  id: string;
  kind: string;
  recipeId: string | null;
  purchaseOrderId: string | null;
  version: number;
  language: string;
  targetYield: number | null;
  status: string;
  error: string | null;
  createdAt: string;
  readyAt: string | null;
}

export interface TranslatedLine {
  name: string;
  quantity: number;
  unit: string;
}

export interface TranslatedRecipe {
  recipeId: string;
  language: string;
  provider: string;
  name: string;
  categoryName: string;
  ingredients: TranslatedLine[];
  method: string[];
}

export interface RecipeFilters {
  categoryId?: string;
  ingredientId?: string;
  q?: string;
  includeArchived?: boolean;
}

export interface IngredientView {
  id: string;
  name: string;
  category: string;
  unit: string;
  sattvicProhibited: boolean;
  aliases: string[];
  createdAt: string;
}

export interface CreateIngredientInput {
  name: string;
  category: string;
  unit: string;
  sattvicProhibited: boolean;
  aliases: string[];
}

export interface UpdateIngredientInput {
  name: string;
  category: string;
  unit: string;
  aliases: string[];
}

export interface RecipeLineInput {
  ingredientId: string;
  quantity: number;
  unit: string;
}

export interface RecipeInput {
  name: string;
  categoryId: string;
  baseYieldQty: number;
  baseYieldUnit: string;
  method?: string;
  notes?: string;
  regionTag?: string;
  yieldNote?: string;
  perHeadQty?: number;
  perHeadUnit?: string;
  subtitle?: string;
  badge?: string;
  indicativeCost?: number;
  why?: string;
  cateringNote?: string;
  subRegion?: string;
  noteStart?: string;
  noteVessel?: string;
  noteSeason?: string;
  tags?: string[];
  serveWith?: string[];
  ingredients: RecipeLineInput[];
  sattvicOverrideReason?: string;
}

export interface GlossaryEntry {
  id: string;
  language: string;
  sourceTerm: string;
  targetTerm: string;
}

// --- Inventory (Epic 3) ---------------------------------------------------

export interface StockItemView {
  itemId: string;
  ingredientId: string;
  ingredientName: string;
  category: string;
  storageLocation: string | null;
  unit: string;
  onHand: number;
  reorderThreshold: number | null;
  belowThreshold: boolean;
  expiringSoon: boolean;
  soonestExpiry: string | null;
  notes: string | null;
}

export interface BatchStock {
  batchId: string;
  quantity: number;
  unit: string;
  expiryDate: string | null;
  receivedDate: string | null;
  expiringSoon: boolean;
}

export interface StockDetail {
  item: StockItemView;
  batches: BatchStock[];
}

export interface StockMovement {
  id: string;
  ingredientId: string;
  ingredientName: string;
  storageLocation: string | null;
  batchId: string;
  quantity: number;
  unit: string;
  type: string;
  expiryDate: string | null;
  receivedDate: string | null;
  reason: string | null;
  referenceType: string | null;
  referenceId: string | null;
  note: string | null;
  actorUserId: string;
  actorName: string | null;
  createdAt: string;
}

export interface CreateInventoryItemInput {
  ingredientId: string;
  storageLocation?: string | null;
  reorderThreshold?: number | null;
  notes?: string | null;
}

export interface AdjustStockInput {
  /** The batch being corrected, or null to open one with what is on the shelf today. */
  batchId: string | null;
  quantity: number;
  unit: string;
  reason: string;
  note?: string | null;
}

export interface InventoryFilters {
  location?: string;
  category?: string;
  expiringWithinDays?: number;
}

/**
 * A gift handed to the temple in person. Either cash or goods, never both in one record — the two
 * go different places once recorded, so the server refuses to merge them.
 */
export interface RecordDonationInput {
  anonymous: boolean;
  donorName?: string | null;
  donorPhone?: string | null;
  donorEmail?: string | null;
  cashAmountInr?: number | null;
  estimatedValueInr?: number | null;
  donatedOn: string;
  notes?: string | null;
  /** Cash given towards a wish-list item — money towards its cost, never units. Cash only. */
  wishlistItemId?: string | null;
  ingredients: { ingredientId: string; quantity: number; unit: string; expiryDate?: string | null }[];
  equipment: { name: string; category: string; notes?: string | null }[];
}

// --- Meal planning & calendar (Epic 4) ------------------------------------

export interface CalendarFestival {
  text: string;
  priority: number;
}

export interface CalendarDayView {
  date: string;
  tithi: number;
  paksa: number;
  masa: number;
  gaurabdaYear: number | null;
  naksatra: number | null;
  isEkadashi: boolean;
  ekadashiName: string | null;
  mahadvadashi: string | null;
  fastType: string | null;
  sunrise: string | null;
  sunset: string | null;
  festivals: CalendarFestival[];
  overridden: boolean;
  overrideReason: string | null;
}

export interface SetCalendarOverrideInput {
  isEkadashi: boolean;
  ekadashiName?: string | null;
  tithi?: number | null;
  festivalNote?: string | null;
  reason: string;
}

export interface OccasionView {
  id: string;
  name: string;
  type: "COMPUTED" | "MANUAL";
  matchText: string | null;
  fixedMonth: number | null;
  fixedDay: number | null;
  defaultServings: number | null;
  notes: string | null;
  seeded: boolean;
}

export interface ResolvedOccasion {
  occasionId: string;
  name: string;
  date: string;
  defaultServings: number | null;
  type: "COMPUTED" | "MANUAL";
}

/**
 * A kind of meal the temple cooks (E4-S7).
 *
 * <p>`defaultReadyTime` is null on purpose for the occasional kinds — a deity offering or a catering
 * order has no usual hour, so the planner is always asked rather than given a guess.
 */
export interface MealKindView {
  id: string;
  name: string;
  sortOrder: number;
  /** "HH:mm:ss", or null when this kind must always be given a time. */
  defaultReadyTime: string | null;
  /** Food someone outside the temple asked for and is paying for: the plan must name them. */
  needsClient: boolean;
  /** Food that leaves the temple: the plan must say where it is going. */
  needsVenue: boolean;
  /**
   * The plan must say what the food is for — a reading, book distribution, a school event (B6).
   * Free text and never a picklist: the reasons a temple cooks for an outside event are open-ended,
   * and a list of five would be wrong by the sixth.
   */
  needsPurpose: boolean;
  /**
   * The plan must name which festival it is for (item 26) — the flag that makes a kind a feast.
   * A feast is a kind of meal and not a kind of day, because on Janmashtami the temple serves an
   * ordinary breakfast and then a feast: one day, two meals, only one of them the big one.
   */
  needsOccasion: boolean;
}

export type DayType = "REGULAR" | "WEEKEND" | "FESTIVAL" | "CATERING";
export type MealStatus = "PLANNED" | "COOKED" | "CANCELLED";

export interface DayContext {
  suggestedDayType: DayType;
  occasionName: string | null;
  suggestedServings: number | null;
  isEkadashi: boolean;
}

export interface MealPlanView {
  id: string;
  planDate: string;
  mealKind: string;
  /** "HH:mm:ss" — the local time the food must be ready. Every meal has one. */
  readyBy: string;
  recipeId: string;
  recipeName: string;
  targetYield: number;
  dayType: DayType;
  occasionName: string | null;
  status: MealStatus;
  clientName: string | null;
  clientContact: string | null;
  venue: string | null;
  /** What an outside event's food is for (B6). A label for the kitchen; nothing computes on it. */
  purpose: string | null;
  adults: number | null;
  children: number | null;
  seniors: number | null;
  /**
   * How many people it takes to execute this meal (item 24), any mix of staff and volunteers. A
   * whole-meal fact carried on each dish row, like the head count. Null where nobody has said, and
   * null is the honest answer — a made-up number would not be.
   */
  crewRequired: number | null;
  kitchenNotes: string | null;
  /**
   * What this dish actually went out at, from the returned job card (B5). Null until the meal is
   * recorded, and never a replacement for targetYield — the gap between the two is what tells a
   * temple its head counts are wrong, and in which direction.
   */
  actualServings: number | null;
  /** How much of what was cooked actually went out; null where the returned card didn't say. */
  consumedQuantity: number | null;
  /** The dish never went into a pot: its row reads CANCELLED, and it drew nothing from stock. */
  notMade: boolean;
  cookedAt: string | null;
  ekadashiAcknowledged: boolean;
  createdAt: string;
}

/**
 * One meal — a date and a kind — assembled from the dish rows that share them (B5).
 *
 * <p>There is no meal-line table: one meal plan row is one dish. This is the grouping the whole
 * product means whenever it says "the meal": one job card per meal kind, recording per meal rather
 * than per dish, plates per meal kind.
 */
export interface MealServiceView {
  /** The meal's own row, or null until a card has been printed or the meal recorded. */
  serviceId: string | null;
  planDate: string;
  mealKind: string;
  readyBy: string;

  adults: number | null;
  children: number | null;
  seniors: number | null;
  /** What the meal scales to. Never the sum of its dishes — three dishes at 250 is 250 plates. */
  plates: number;
  /** How many people it takes to execute this meal (item 24). Null where nobody has said. */
  crewRequired: number | null;

  dayType: DayType;
  occasionName: string | null;
  clientName: string | null;
  clientContact: string | null;
  venue: string | null;
  purpose: string | null;
  kitchenNotes: string | null;

  cardNumber: string | null;
  cardIssuedAt: string | null;

  recorded: boolean;
  recordedAt: string | null;
  recordedByName: string | null;
  recordingNote: string | null;

  dishes: MealPlanView[];
}

/** What actually went out at one meal, typed in from the card that came back. */
export interface RecordMealInput {
  planDate: string;
  mealKind: string;
  note?: string | null;
  /**
   * Every dish the meal has. A dish left out is refused rather than guessed at.
   *
   * <p>`actualServings` is how much was COOKED — the figure stock is drawn against —
   * and `consumedQuantity` how much of it went out. Both in the preparation's own yield unit.
   */
  dishes: {
    mealPlanId: string;
    actualServings?: number | null;
    consumedQuantity?: number | null;
    notMade: boolean;
  }[];
}

/**
 * The temple's morning screen (E4-S8), in one payload.
 *
 * <p>Nullable fields say "not yours" rather than "none": kitchen staff do not hold
 * MANAGE_VENDOR_PAYMENTS, so the money side of `deliveries` is absent for them. A zero would read as
 * a statement about the world, which is a different and wrong thing to say.
 */
export interface TodayView {
  date: string;
  calendar: TodayCalendarNote | null;
  meals: TodayMeal[];
  platesToday: number;
  itemsBelowThreshold: number;
  /** How many consumables the temple tracks at all — 0 below par means nothing when this is 0 too. */
  itemsTracked: number;
  workforce: TodayWorkforce;
  materialsCost: TodayMaterialsCost;
  /** Meals from the past week nobody has typed the job card back in for. A nudge, not an alarm. */
  unrecordedMeals: number;
  approvals: TodayApprovals;
  deliveries: TodayDelivery[];
}

/**
 * Whether there is enough of a kitchen to cook with today (B1). Counted apart and never summed — a
 * full-time cook and a two-hour evening volunteer are not interchangeable.
 */
export interface TodayWorkforce {
  staffIn: number;
  volunteers: number;
  /**
   * One readout per meal the kitchen is cooking today (item 24) — `Breakfast 4 of 4 · Lunch 5 of 8 ·
   * Dinner 6 of 6`, with the short one standing out. It replaces `Working today · 7`, which could
   * not answer the question: the seven are not all there at midday, and lunch may take eight.
   */
  meals: MealCrewView[];
}

/** What today's food is costing, estimated from vendors' last-known prices (B2). */
export interface TodayMaterialsCost {
  estimatedTotal: number;
  /** How many ingredients in today's basket have no known price. Named rather than swallowed. */
  withoutPrice: number;
}

/** What today and tomorrow ask of the kitchen. Null on a temple with no calendar computed yet. */
export interface TodayCalendarNote {
  fastingToday: boolean;
  fastingTomorrow: boolean;
  todayName: string | null;
  tomorrowName: string | null;
  /** "HH:mm:ss", or null. */
  sunrise: string | null;
  /** Today's place in the lunar month — named on the client, as everywhere else in the calendar. */
  tithi: number;
  paksa: number;
  masa: number;
  naksatra: number | null;
  /** The next day after tomorrow the kitchen has to cook differently for, within the month. */
  ahead: TodayAhead | null;
}

export interface TodayAhead {
  date: string;
  name: string;
  kind: "FAST" | "FESTIVAL";
  daysAway: number;
}

/**
 * A meal of today — a kind and a time, with its dishes beneath (A3).
 *
 * Today used to list one row per preparation, so a lunch of three dishes read as three lunches.
 */
export interface TodayMeal {
  mealKind: string;
  /** "HH:mm:ss" — the order the kitchen works in. */
  readyBy: string;
  /** What this meal scales to. Never the sum of its dishes (A4). */
  plates: number;
  recorded: boolean;
  /** Still has a dish to cook, and nobody has typed the card back in. */
  awaitingRecord: boolean;
  occasionName: string | null;
  dishes: TodayDish[];
}

/**
 * What is waiting for this person to answer. Counted by the server and scoped to what they may
 * actually act on, so somebody who cannot approve sees zeroes and no nudge renders.
 */
export interface TodayApprovals {
  ingredientRequests: number;
  /** Of those, needed today or tomorrow. */
  ingredientRequestsSoon: number;
  leaveRequests: number;
  /** Of those, starting today or tomorrow — or already under way with no answer. */
  leaveRequestsSoon: number;
}

export interface TodayDish {
  id: string;
  recipeName: string;
  targetYield: number;
  /**
   * What `targetYield` is measured in. Today has no recipe list to look it up in, so the server
   * carries it — without which this screen printed a bare number and left the reader to guess
   * whether 40 meant servings, kilos or litres (E11-S4).
   */
  targetYieldUnit: string;
  /** What actually went out, once the card came back. Null until then. */
  actualServings: number | null;
  notMade: boolean;
  status: MealStatus;
}

/** Something expected from a vendor: an order due, or an invoice past its date. */
export interface TodayDelivery {
  purchaseOrderId: string | null;
  poNumber: string | null;
  vendorName: string;
  neededBy: string | null;
  state: "AWAITED" | "INVOICE_OVERDUE";
}

/**
 * Plan a meal (E4-S7). No day type: whether a day is a weekend, a festival or an ordinary Tuesday
 * follows from the date and the calendar, so the server derives it and nobody is asked.
 */
export interface CreateMealPlanInput {
  planDate: string;
  mealKind: string;
  recipeId: string;
  targetYield: number;
  /** "HH:mm". Optional only for a kind that carries a default time. */
  readyBy?: string | null;
  clientName?: string | null;
  clientContact?: string | null;
  venue?: string | null;
  /** What the food is for, where the kind asks for it (B6). */
  purpose?: string | null;
  /**
   * Which festival this meal is for, where the kind asks (item 26). Honoured only by a kind carrying
   * `needsOccasion` — every other kind takes its occasion from the date and the calendar. Left out,
   * a feast falls back to whatever the calendar says for that date.
   */
  occasionName?: string | null;
  /** The hall as the planner expects it; the servings figure is derived from these three. */
  adults?: number | null;
  children?: number | null;
  seniors?: number | null;
  /**
   * How many people it takes to execute this meal (item 24). One counter, any mix of staff and
   * volunteers — the mix does not matter, and splitting it would invent a constraint the temple does
   * not have. Optional: a meal is planned weeks before anybody is rostered.
   */
  crewRequired?: number | null;
  kitchenNotes?: string | null;
  ekadashiAcknowledged?: boolean;
}

/**
 * Swap or edit a dish in place (B4) — instead of cancelling it and adding another, which loses the
 * row and its history. Allowed until the meal is recorded, refused the moment it is.
 */
export type UpdateMealPlanInput = CreateMealPlanInput;

export interface MealKindInput {
  name: string;
  sortOrder: number;
  defaultReadyTime: string | null;
  needsClient: boolean;
  needsVenue: boolean;
  needsPurpose: boolean;
  /** Meals of this kind must name the festival they are for (item 26) — a feast. */
  needsOccasion: boolean;
}

/**
 * Whether there are enough hands for one meal (item 24) — the readout that reads
 * `Rostered · 3 staff · 2 volunteers · 5 of 8`.
 *
 * <p>Staff and volunteers are reported apart and also added. Apart because "we are three short" and
 * "we are three short of staff" are different sentences. Added because the meal itself does not care
 * which: it is satisfied when staff + volunteers reaches the planned number.
 *
 * <p>A person counts towards a meal if their working window covers the time its food must be ready,
 * and a volunteer counts the same way against their shift window — so a shift posted 11:00–14:00
 * falls to lunch without anybody linking it to one.
 */
export interface MealCrewView {
  planDate: string;
  mealKind: string;
  /** "HH:mm:ss" — the moment the roster is asked about. */
  readyBy: string;
  /**
   * How many the planner said it takes, or null where nobody has said. Null is not zero and must not
   * be drawn as a shortfall — a meal is planned weeks before anybody is rostered.
   */
  crewRequired: number | null;
  staffIn: number;
  volunteers: number;
  /** staffIn + volunteers: the figure `crewRequired` is measured against. */
  rostered: number;
  /**
   * A number was set and the roster does not reach it. A quiet warning tone and nothing more: it
   * never blocks saving, and it never blocks leave.
   */
  shortOfCrew: boolean;
}

/**
 * What was cooked for this festival last time (item 26b) — "Last Janmashtami, 26 August 2025 — 18
 * preparations."
 *
 * <p>The preparation list carries; that is the part that takes an hour to reassemble. Servings do
 * not — they follow this year's head count. Last year's per-dish overrides do not either: an
 * override was a judgement about last year's crowd. Nothing is applied automatically; the menu is
 * offered, one press puts it in, and everything stays editable.
 */
export interface MenuHistoryView {
  /** The occasion as it was spelled on the meal actually cooked, not as it was asked for. */
  occasionName: string;
  /**
   * When it was last cooked for, or null where it never has been. The first ever Janmashtami has
   * nothing to offer and the control is absent.
   */
  lastCookedOn: string | null;
  mealKind: string | null;
  /** How many preparations that meal had in all — the 18 in "2 of last year's 18". */
  preparationCount: number;
  /** How many are no longer in the temple's recipes. Said out loud, never silently dropped. */
  missingCount: number;
  /** The ones that can still be planned. */
  preparations: MenuHistoryPreparation[];
}

export interface MenuHistoryPreparation {
  recipeId: string;
  recipeName: string;
}

export interface EkadashiCheck {
  isEkadashi: boolean;
  compatible: boolean;
  offendingIngredients: string[];
}

export interface IngredientShortfall {
  ingredientId: string;
  ingredientName: string;
  required: number;
  available: number;
  shortBy: number;
  unit: string;
}

export interface MealSufficiency {
  mealPlanId: string;
  planDate: string;
  mealKind: string;
  readyBy: string;
  recipeName: string;
  status: "SUFFICIENT" | "SHORT" | "PLANNING";
  shortfalls: IngredientShortfall[];
}

/** What cooking a recipe draws from stock, previewed or committed (E3-S6). */
export interface ConsumptionPlan {
  recipeName: string;
  targetYield: number;
  sufficient: boolean;
  lines: { ingredientName: string; required: number; unit: string }[];
  shortfalls: { ingredientName: string; required: number; available: number; unit: string }[];
}

/** An ingredient the day's cooking needs that no vendor has a price for (B2). */
export interface UnpricedIngredient {
  ingredientId: string;
  name: string;
  /** Null only when the recipe measures it in a family its catalogue unit cannot express. */
  quantity: number | null;
  unit: string | null;
}

/**
 * What a day's planned food costs, estimated from vendors' last-known prices (B2).
 *
 * <p>Never show {@code estimatedTotal} on its own. It covers the priced ingredients and no others,
 * so a screen that omits {@code ingredientsWithoutPrice} is quietly claiming a completeness the
 * figure does not have — "₹18,400 estimated · 6 ingredients have no known price" is the whole
 * sentence.
 */
export interface MaterialsCost {
  date: string;
  estimatedTotal: number;
  ingredientsPriced: number;
  ingredientsWithoutPrice: number;
  unpriced: UnpricedIngredient[];
}

// ---- Epic 5: Ordering & Vendors ------------------------------------------

export interface VendorView {
  id: string;
  name: string;
  contactPerson: string | null;
  phone: string;
  email: string | null;
  address: string | null;
  gstin: string | null;
  preferredLanguage: string;
  notes: string | null;
  active: boolean;
  whatsappReachable: boolean;
  createdAt: string;
}

export interface VendorSupplyView {
  ingredientId: string;
  ingredientName: string;
  lastPrice: number | null;
  preferred: boolean;
}

export interface VendorDetailView {
  vendor: VendorView;
  supplies: VendorSupplyView[];
}

export interface VendorInput {
  name: string;
  contactPerson?: string | null;
  phone: string;
  email?: string | null;
  address?: string | null;
  gstin?: string | null;
  preferredLanguage?: string | null;
  notes?: string | null;
}

export interface OrderListLineView {
  ingredientId: string;
  ingredientName: string;
  currentStock: number;
  unit: string;
  suggestedQty: number;
  neededBy: string | null;
  suggestedVendorId: string | null;
  suggestedVendorName: string | null;
  shortfall: number;
  thresholdTopUp: number;
  poOutstanding: number;
  shortPurchaseOrders: string[];
  included: boolean;
  edited: boolean;
}

export type PoStatus =
  | "DRAFT"
  | "SENT"
  | "PARTIALLY_RECEIVED"
  | "RECEIVED"
  | "CANCELLED";

export interface PurchaseOrderView {
  id: string;
  poNumber: string;
  vendorId: string;
  vendorName: string;
  status: PoStatus;
  orderDate: string;
  neededBy: string | null;
  deliveryLocation: string | null;
  notes: string | null;
  cancelReason: string | null;
  sentAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
}

export interface PurchaseOrderLineView {
  id: string;
  ingredientId: string;
  ingredientName: string;
  quantity: number;
  unit: string;
  expectedPrice: number | null;
}

export interface PoEventView {
  eventType: string;
  detail: string | null;
  actorName: string | null;
  createdAt: string;
}

export interface PurchaseOrderDetailView {
  order: PurchaseOrderView;
  lines: PurchaseOrderLineView[];
  events: PoEventView[];
}

export interface PoLineInput {
  ingredientId: string;
  quantity: number;
  unit: string;
  expectedPrice?: number | null;
}

export interface CreatePurchaseOrderInput {
  vendorId: string;
  neededBy?: string | null;
  deliveryLocation?: string | null;
  notes?: string | null;
  lines: PoLineInput[];
}

export interface GoodsReceiptLineView {
  id: string;
  poLineId: string;
  ingredientId: string;
  ingredientName: string;
  receivedQty: number;
  rejectedQty: number;
  rejectReason: string | null;
  unit: string;
  batchId: string | null;
  expiryDate: string | null;
  receivedDate: string | null;
}

export interface GoodsReceiptView {
  id: string;
  purchaseOrderId: string;
  deliveryNoteRef: string | null;
  note: string | null;
  receivedByName: string | null;
  receivedAt: string;
  lines: GoodsReceiptLineView[];
}

export type RejectReason = "DAMAGED" | "SPOILED" | "WRONG_ITEM" | "OTHER";

export interface ReceiptLineInput {
  poLineId: string;
  receivedQty: number;
  rejectedQty: number;
  rejectReason?: RejectReason | null;
  expiryDate?: string | null;
  receivedDate?: string | null;
}

export interface ReceiveDeliveryInput {
  idempotencyKey: string;
  deliveryNoteRef?: string | null;
  note?: string | null;
  lines: ReceiptLineInput[];
}

export type InvoiceStatus = "PENDING" | "PAID";

export interface VendorInvoiceView {
  id: string;
  vendorId: string;
  vendorName: string;
  purchaseOrderId: string | null;
  poNumber: string | null;
  direct: boolean;
  description: string | null;
  invoiceNumber: string;
  invoiceDate: string;
  amount: number;
  dueDate: string | null;
  scanRef: string | null;
  status: InvoiceStatus;
  expectedValue: number | null;
  variance: number | null;
  overdue: boolean;
  createdAt: string;
}

/** One recorded payment against a vendor invoice (E7-S8), behind MANAGE_VENDOR_PAYMENTS. */
export interface InvoicePaymentView {
  id: string;
  paidOn: string;
  amount: number;
  method: string;
  reference: string | null;
  note: string | null;
  recordedByName: string | null;
  createdAt: string;
}

export interface RecordInvoiceInput {
  vendorId: string;
  purchaseOrderId?: string | null;
  description?: string | null;
  invoiceNumber: string;
  invoiceDate: string;
  amount: number;
  dueDate?: string | null;
  scanRef?: string | null;
}

export interface RecordInvoiceResponse {
  invoice: VendorInvoiceView;
  duplicateWarning: boolean;
}

// ---- Epic 6: Workforce Management ----------------------------------------

/** What a member of staff is called. A label; it grants nothing (E6-S8). */
export type JobTitle =
  | "TEMPLE_ADMINISTRATOR"
  | "KITCHEN_MANAGER"
  | "HEAD_COOK"
  | "COOK"
  | "ASSISTANT_COOK"
  | "SWEET_MAKER"
  | "PRASADAM_SERVER"
  | "STORE_MANAGER"
  | "STOREKEEPER"
  | "HOUSEKEEPING"
  | "DISHWASHER"
  | "DRIVER"
  | "SECURITY"
  | "OFFICE_ASSISTANT"
  | "ACCOUNTANT"
  | "OTHER"
  | "UNRECORDED";

export type JobTitleGroup = "ADMINISTRATION" | "KITCHEN" | "STORE" | "SUPPORT" | "OTHER";

export type EmploymentType = "FULL_TIME" | "PART_TIME" | "CONTRACT";

export type EmploymentStatus = "ACTIVE" | "RESIGNED" | "TERMINATED" | "CONTRACT_ENDED";

/** What a member of staff may do. Null means no app account at all. */
/**
 * What a member of staff may do in the app. Mirrors the backend `SystemAccess` enum, which has
 * carried all three since E6-S12 — this type had only two of them, so a Kitchen Manager could be
 * described in the design and never actually appointed. E10 is what made that matter: approving and
 * issuing ingredients belong to that role, and a role nobody can be granted holds no permissions.
 */
export type SystemAccess = "TEMPLE_ADMIN" | "KITCHEN_MANAGER" | "KITCHEN_STAFF";

/** One entry of the hire form's picklist, served by the API so the vocabulary lives in one place. */
export interface JobTitleOption {
  value: JobTitle;
  label: string;
  group: JobTitleGroup;
  suggestedAccess: SystemAccess | null;
}

export interface StaffProfileView {
  id: string;
  /** Null for staff the temple gave no login. */
  userId: string | null;
  fullName: string;
  phone: string | null;
  email: string | null;

  jobTitle: JobTitle;
  jobTitleOther: string | null;
  /** What to print: the temple's own words if it gave any, otherwise the vocabulary's label. */
  jobTitleLabel: string;

  employmentType: EmploymentType;
  dateOfJoining: string;
  dateOfBirth: string | null;
  address: string | null;

  emergencyContactName: string | null;
  emergencyContactRelationship: string | null;
  emergencyContactPhone: string | null;

  /** The last four characters of a stored PAN. The whole thing is a separate, audited request. */
  panLast4: string | null;

  systemAccess: SystemAccess | null;

  employmentStatus: EmploymentStatus;
  lastWorkingDay: string | null;
  endReason: string | null;
  notes: string | null;

  createdAt: string;
}

/**
 * Somebody who used to work here, and whether this temple raised a record about them (B9).
 *
 * <p>A wrapper rather than a field on the profile, because that shape is shared with the roster and
 * with a person's own schedule — both behind a permission that is meant to be given to a kitchen
 * manager without handing them everyone's dismissal history. The flag is served on the register
 * alone. A retracted record does not count: it has stopped being shown at hires.
 */
export interface FormerStaffView {
  profile: StaffProfileView;
  banned: boolean;
}

/** Who works here now, and who used to — split by the API, because they answer different questions. */
export interface StaffRegisterView {
  current: StaffProfileView[];
  former: FormerStaffView[];
}

export interface HireStaffInput {
  /** An existing devotee to promote, or omitted to hire someone the temple has no record of. */
  existingUserId?: string | null;
  fullName: string;
  phone?: string | null;
  email?: string | null;
  jobTitle: JobTitle;
  jobTitleOther?: string | null;
  employmentType: EmploymentType;
  dateOfJoining: string;
  dateOfBirth?: string | null;
  address?: string | null;
  emergencyContactName?: string | null;
  emergencyContactRelationship?: string | null;
  emergencyContactPhone?: string | null;
  pan?: string | null;
  systemAccess?: SystemAccess | null;
  /**
   * A monthly figure in the temple's currency, or null when no pay has been agreed (B8). Null is
   * ordinary — a part-timer paid daily in cash may have nothing recorded at all — and is never
   * sent as 0, which would read as a wage of nothing.
   */
  monthlySalary?: number | null;
  /**
   * The id of the check whose findings this admin has already read and chosen to hire past (B9).
   *
   * <p>Omitted on a first attempt, which is every ordinary hire. When the check finds something the
   * hire does not complete — the findings come back instead — and sending the same input again with
   * this set is the admin's decision to go ahead. It is not an override of a block; there is no
   * block. It is an answer, and it is recorded as one.
   */
  acknowledgedBanCheckId?: string | null;
  notes?: string | null;
}

/**
 * Editing a record. `pan` omitted leaves the stored value alone and `""` clears it — the form never
 * shows the stored PAN, so sending an empty string by default would erase it on every other edit.
 */
export type UpdateStaffInput = Omit<HireStaffInput, "existingUserId">;

export interface EndEmploymentInput {
  status: Exclude<EmploymentStatus, "ACTIVE">;
  lastWorkingDay: string;
  reason?: string | null;
  /** True disables the account; false returns them to being an ordinary devotee. */
  revokeSignIn: boolean;
  /**
   * A record to raise against this person, visible to every temple on the platform (B9). Omitted in
   * the ordinary case — most dismissals raise none — and the panel is built so that omitting it is
   * what happens unless the admin deliberately chooses otherwise.
   */
  ban?: RaiseBanInput | null;
}

// ---- The record on termination, and the check at hire (B9) -----------------
//
// The one part of this product that crosses the line between temples. Two things about the shape
// below are worth reading before touching it.
//
// There is no type here for "someone else's ban record", because there is no endpoint that returns
// one. A BanFinding is the only form in which another temple's record ever reaches this browser,
// and it arrives only as the result of an actual hire. Adding a search would defeat the design.
//
// And nothing here is ever shown to the person the record is about. They are not given the reason
// in the app — the argument for that is in the backend service and is deliberate — which is why
// retraction, the ten-year fade and the raising temple's name on every finding have to carry the
// whole of the error correction between them.

export type BanCategory =
  | "THEFT"
  | "FINANCIAL_IRREGULARITY"
  | "VIOLENCE_OR_THREATS"
  | "HARASSMENT"
  | "CHILD_SAFETY"
  | "INTOXICATION_ON_DUTY"
  | "FALSIFIED_IDENTITY"
  | "SERIOUS_NEGLIGENCE";

/** Served by the API so the vocabulary and its wording live in one place. */
export interface BanCategoryOption {
  value: BanCategory;
  label: string;
}

/** Both halves are required: a category to compare, and an account in the temple's own words. */
export interface RaiseBanInput {
  category: BanCategory;
  account: string;
}

/**
 * One record that might be about the person being hired.
 *
 * <p>The raising temple is named and what they wrote is quoted in full, on purpose: the point is to
 * produce a telephone call between two administrators, not a verdict delivered by a screen.
 */
export interface BanFinding {
  banId: string;
  raisingTempleName: string;
  category: BanCategory;
  categoryLabel: string;
  /** The name they employed the person under, which may not be the one on the form. */
  bannedName: string;
  account: string;
  raisedOn: string;
  signals: string[];
  /** Which details matched, ready to read out — "PAN", "Name", "Address". */
  signalLabels: string[];
  /** True when at least one signal was a value compared against the same value. */
  exact: boolean;
}

/**
 * What a hire came back with.
 *
 * <p>Exactly one of the two is present. `id` means the person was taken on. `checkId` with
 * `findings` means the hire has <em>not</em> happened and there is something the admin should see
 * first — never that it was refused, because a match never blocks one.
 */
export interface HireOutcome {
  id?: string;
  checkId?: string;
  findings?: BanFinding[];
}

/** A record this temple raised, on its own list. There is no equivalent for anybody else's. */
export interface EmploymentBanView {
  id: string;
  staffProfileId: string;
  personName: string;
  category: BanCategory;
  categoryLabel: string;
  account: string;
  raisedAt: string;
  raisedBy: string | null;
  /** When it stops appearing at hires. Ten years, confirmed 2026-08-20. */
  fadesOn: string;
  retracted: boolean;
  retractedAt: string | null;
  retractionReason: string | null;
}

// ---- Staff pay (B8) --------------------------------------------------------
//
// Deliberately its own view rather than fields on StaffProfileView: that shape is shared with the
// roster and with a person's own schedule, and a salary added there would follow it into both.
// Everything below is served behind MANAGE_STAFF, which only the temple administrator holds.

export type StaffPaymentMode = "CHEQUE" | "CASH" | "PAYROLL";

/** Salary, or the figure agreed when somebody leaves. */
export type StaffPaymentPurpose = "SALARY" | "SETTLEMENT";

/** One advance repaid out of a payment. */
export interface StaffPaymentDeduction {
  advanceId: string;
  advancePaidOn: string;
  amount: number;
}

export interface StaffPaymentView {
  id: string;
  paidOn: string;
  /** Before anything was recovered from it. */
  gross: number;
  /** The advances this payment repaid, added up. */
  deducted: number;
  /** What the person actually received. */
  net: number;
  mode: StaffPaymentMode;
  modeLabel: string;
  reference: string | null;
  purpose: StaffPaymentPurpose;
  purposeLabel: string;
  note: string | null;
  recordedByName: string | null;
  /** Set when the entry was struck as a mistake. Nothing is ever deleted. */
  voidedAt: string | null;
  deductions: StaffPaymentDeduction[];
}

export interface StaffAdvanceView {
  id: string;
  paidOn: string;
  amount: number;
  recovered: number;
  /** What the temple is still owed on this one. */
  outstanding: number;
  mode: StaffPaymentMode;
  modeLabel: string;
  reference: string | null;
  note: string | null;
  recordedByName: string | null;
  voidedAt: string | null;
}

export interface StaffPayView {
  staffId: string;
  fullName: string;
  /** ISO-4217, the temple's own. Screens format with this rather than a hard-coded symbol. */
  currency: string;
  /** Null when no pay has been agreed — say "no salary recorded", never "₹0". */
  monthlySalary: number | null;
  /** Advances given minus deductions recovered. Arithmetic, so it can be stated flatly. */
  advanceBalance: number;
  /** The last salary payment that still stands; a settlement is not one. */
  lastSalaryPayment: StaffPaymentView | null;
  payments: StaffPaymentView[];
  advances: StaffAdvanceView[];
}

export interface RecordStaffPaymentInput {
  paidOn: string;
  /** The gross. What they received is this minus the deductions below. */
  amount: number;
  mode: StaffPaymentMode;
  /** The cheque number or payroll reference; required unless the payment was cash. */
  reference?: string | null;
  purpose: StaffPaymentPurpose;
  note?: string | null;
  deductions?: { advanceId: string; amount: number }[];
}

export interface RecordStaffAdvanceInput {
  paidOn: string;
  amount: number;
  /** Cheque or cash: an advance is handed over, never run through payroll. */
  mode: Exclude<StaffPaymentMode, "PAYROLL">;
  reference?: string | null;
  note?: string | null;
}

export interface ScheduleDay {
  dayOfWeek: number; // 1=Mon … 7=Sun
  working: boolean;
  startTime: string | null;
  endTime: string | null;
}

export interface ScheduleExceptionView {
  id: string;
  exceptionDate: string;
  working: boolean;
  startTime: string | null;
  endTime: string | null;
  note: string | null;
}

export interface StaffProfileDetailView {
  profile: StaffProfileView;
  template: ScheduleDay[];
  exceptions: ScheduleExceptionView[];
}

export interface ResolvedDay {
  date: string;
  dayOfWeek: number;
  working: boolean;
  startTime: string | null;
  endTime: string | null;
  /** A per-date override decided this day, so the grid shows it as adjusted. */
  fromException: boolean;
  /** The override's id, for undoing it. Null when the template decided the day. */
  exceptionId: string | null;
  /** Shared by the two halves of a swap: undoing either removes both. */
  swapLinkId: string | null;
  /** Approved leave covering this date. Read-only on the grid; revoke it to schedule over it. */
  leaveId: string | null;
  leaveType: LeaveType | null;
  leaveLabel: string | null;
  halfDayLeave: boolean;
}

export interface StaffWeek {
  staffProfileId: string;
  userId: string | null;
  fullName: string;
  jobTitleLabel: string;
  days: ResolvedDay[];
}

export interface WeekScheduleView {
  weekStart: string;
  staff: StaffWeek[];
  /** One per date, Monday first — the same figures Today and the planner pebbles read. */
  counts: WorkforceCount[];
}

// ---- Leave and the head count (B7) ---------------------------------------

/** Time off, sick, unpaid. No accrual and no balances — a request-and-approve log. */
export type LeaveType = "TIME_OFF" | "SICK" | "UNPAID";

/** Only PENDING and APPROVED keep somebody off the roster. */
export type LeaveStatus = "PENDING" | "APPROVED" | "DECLINED" | "REVOKED";

export interface LeaveView {
  id: string;
  staffProfileId: string;
  staffName: string;
  jobTitleLabel: string;
  leaveType: LeaveType;
  leaveTypeLabel: string;
  fromDate: string;
  toDate: string;
  halfDay: boolean;
  reason: string | null;
  status: LeaveStatus;
  /** Null where the temple recorded this for somebody who holds no login. */
  requestedByName: string | null;
  requestedAt: string;
  decidedByName: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
}

export interface RequestLeaveInput {
  leaveType: LeaveType;
  fromDate: string;
  toDate: string;
  halfDay: boolean;
  reason?: string | null;
}

/** Recording leave for somebody the temple employs; lands already approved. */
export interface RecordLeaveInput extends RequestLeaveInput {
  staffProfileId: string;
  decisionNote?: string | null;
}

/**
 * Who is in on one date. Staff and volunteers are never summed — a full-time cook and a two-hour
 * evening volunteer are not interchangeable.
 */
export interface WorkforceCount {
  date: string;
  staffIn: number;
  volunteers: number;
}


// ---- Epic 8: Devotee Communications --------------------------------------

/** What kind of message this is. Exactly one of them cannot be declined (E8-S1). */
export type CommunicationCategory =
  | "NEWSLETTER"
  | "FESTIVAL_ANNOUNCEMENT"
  | "SEVA_OPPORTUNITY"
  | "APPEAL"
  | "TEMPLE_NOTICE"
  | "OPERATIONAL";

export type CommunicationChannel = "EMAIL" | "WHATSAPP";

export type CommunicationStatus = "DRAFT" | "SENT";

export interface CommunicationCategoryOption {
  value: CommunicationCategory;
  label: string;
  description: string;
}

export interface CommunicationView {
  id: string;
  category: CommunicationCategory;
  channel: CommunicationChannel;
  subject: string;
  /** Already sanitised by the server — cleaned on the way in, not on the way out. */
  bodyHtml: string | null;
  bodyText: string;
  whatsappSummary: string | null;
  status: CommunicationStatus;
  audienceCount: number | null;
  publicToken: string;
  author: string | null;
  createdAt: string;
  sentAt: string | null;
}

export interface SaveCommunicationInput {
  category: CommunicationCategory;
  channel: CommunicationChannel;
  subject: string;
  bodyHtml: string;
  whatsappSummary?: string | null;
}

/** The email exactly as framed, and the WhatsApp line exactly as Meta would carry it. */
export interface CommunicationPreview {
  subject: string;
  emailHtml: string;
  whatsappText: string;
  plainText: string;
}

export interface CommunicationDelivery {
  recipientName: string;
  status: string;
  channel: string | null;
  suppressedReason: string | null;
}

export interface PublicCommunication {
  templeName: string;
  subject: string;
  bodyHtml: string | null;
  sentAt: string | null;
}

/** One category on a devotee's own preferences screen, and whether they get it. */
export interface CategoryChoice {
  value: CommunicationCategory;
  label: string;
  description: string;
  /** False for exactly one category, and that is the design. */
  optional: boolean;
  subscribed: boolean;
}

export interface CommunicationPreferencesView {
  optedOutOfAll: boolean;
  categories: CategoryChoice[];
}

export interface ShiftView {
  id: string;
  title: string;
  description: string | null;
  shiftDate: string;
  startTime: string;
  endTime: string;
  location: string | null;
  capacity: number;
  reminderOffsetsMinutes: number[];
  status: "OPEN" | "CANCELLED";
  cancelReason: string | null;
  signedUpCount: number;
  waitlistCount: number;
  createdAt: string;
}

export interface ShiftInput {
  title: string;
  description?: string | null;
  shiftDate: string;
  startTime: string;
  endTime: string;
  location?: string | null;
  capacity: number;
  reminderOffsetsMinutes?: number[];
}

export interface AvailableShiftView {
  id: string;
  title: string;
  description: string | null;
  shiftDate: string;
  startTime: string;
  endTime: string;
  location: string | null;
  capacity: number;
  signedUpCount: number;
  waitlistCount: number;
  callerState: "AVAILABLE" | "FULL" | "SIGNED_UP" | "WAITLISTED";
}

export interface MyShiftView {
  signupId: string;
  shiftId: string;
  title: string;
  shiftDate: string;
  startTime: string;
  endTime: string;
  location: string | null;
  source: string;
  signedUpAt: string;
}

export interface MyWaitlistView {
  shiftId: string;
  title: string;
  shiftDate: string;
  startTime: string;
  endTime: string;
  location: string | null;
  position: number;
  joinedAt: string;
}

export interface RosterReminder {
  offsetMinutes: number;
  channel: string | null;
  status: string | null;
}

export interface RosterSignup {
  userId: string;
  fullName: string;
  source: string;
  signedUpAt: string;
  releasedAt: string | null;
  reminders: RosterReminder[];
}

export interface RosterWaitlister {
  userId: string;
  fullName: string;
  position: number;
  joinedAt: string;
}

export interface RosterRecipient {
  fullName: string;
  channel: string | null;
  status: string | null;
}

export interface RosterBroadcast {
  message: string;
  sentByName: string | null;
  createdAt: string;
  recipients: RosterRecipient[];
}

export interface RosterView {
  shift: ShiftView;
  signups: RosterSignup[];
  waitlist: RosterWaitlister[];
  broadcasts: RosterBroadcast[];
}

// ---- Epic 7: Payments & Donations ----------------------------------------

export interface DonationPageInfo {
  templeName: string;
  is80gApproved: boolean;
  presets: number[];
  /** Plates on today's plan. Null when nothing is planned — the line is left out rather than zeroed. */
  platesToday: number | null;
  /** Last month's kitchen spend over the plates it produced. Null until there is enough of both. */
  costPerPlateInr: number | null;
  /** Where last month's money went, by the temple's own ingredient categories. */
  spendShares: { label: string; percent: number }[];
}

/**
 * A temple's payment gateway as its administrator may see it (E7).
 *
 * <p>Note what is absent: the key secret. It is never returned by any endpoint, so the screen shows
 * dots and a Replace button rather than a value it could not have.
 */
/** What duplicating a week actually did — reported, because the interesting part is what it declined. */
export interface DuplicateWeekResult {
  copied: number;
  daysAlreadyPlanned: number;
  /** Meals not copied because the day they would land on is a fast their recipe does not suit. */
  mealsRefusedOnFast: number;
  sourceWasEmpty: boolean;
}

export interface PaymentSettingsView {
  configured: boolean;
  provider: string | null;
  /** The provider's public key id — handed to the browser to open checkout, so safe to show. */
  keyId: string | null;
  keySecretSavedAt: string | null;
  /** Where this temple's provider must be told to send payment notifications. */
  webhookUrl: string | null;
  /** When the credentials last reached the provider. Says nothing about webhooks. */
  verifiedAt: string | null;
  /** When a correctly signed webhook last arrived — the only proof the return path works. */
  webhookSeenAt: string | null;
  /**
   * When we registered the webhook with the provider ourselves. Null means it is the administrator's
   * to do by hand — the ordinary case for Razorpay, whose webhook API only partners may call.
   */
  webhookRegisteredAt: string | null;
}

/**
 * Webhook events grouped by what subscribing to them gets a temple.
 *
 * <p>Grouped rather than listed flat because a provider offers some of them only once the matching
 * product is switched on — Razorpay shows no `subscription.*` event until Subscriptions is
 * activated — and telling an administrator to tick a box that is not on their screen reads as a
 * fault in ours.
 */
/**
 * A temple's WhatsApp connection as its administrator sees it.
 *
 * <p>Two Meta ids that address a send, the callback address Meta must be told about, and the dates
 * that say whether each half works. The access token and app secret are never returned.
 */
export interface WhatsAppSettingsView {
  connected: boolean;
  phoneNumberId: string | null;
  wabaId: string | null;
  /** The number as Meta describes it — proof the right one was connected. */
  displayNumber: string | null;
  webhookUrl: string | null;
  /** When the credentials last reached Meta. Says nothing about whether callbacks arrive. */
  verifiedAt: string | null;
  /** When a correctly signed callback last arrived — the only proof the return path works. */
  webhookSeenAt: string | null;
  /** When the message templates were last submitted. Approval is Meta's, and is not instant. */
  templatesSubmittedAt: string | null;
}

export interface SaveWhatsAppSettingsInput {
  phoneNumberId: string;
  wabaId: string;
  /** Omitted to keep the stored one — it is never sent back to the screen. */
  accessToken?: string;
  appSecret?: string;
}

export interface WebhookSubscriptionGroup {
  /** What these events are for, in a temple administrator's words. */
  purpose: string;
  /** Whether skipping this group leaves donations taken but never recorded. */
  essential: boolean;
  events: string[];
}

export interface PaymentProviderOption {
  value: string;
  label: string;
}

export interface SavePaymentSettingsInput {
  provider: string;
  keyId: string;
  /** Omitted when an admin is correcting the key id without retyping a secret they cannot see. */
  keySecret?: string;
}

/**
 * The only thing a signed-in devotee still has to type: an 80G certificate needs an address and a
 * PAN, and the temple holds neither. Everything else about them comes from their account.
 */
export interface EightyGInput {
  wants80g: boolean;
  address?: string;
  pan?: string;
}

export interface DonationCheckout {
  donationId: string;
  orderId: string;
  publicKey: string;
  amountInr: number;
  currency: string;
  provider: string;
}

export interface WishlistItemView {
  id: string;
  title: string;
  description: string | null;
  imageRef: string | null;
  priceInr: number;
  category: string;
  quantityWanted: number;
  /** Money given towards this item so far — progress is rupees, because the temple buys it whole. */
  paidInr: number;
  sortOrder: number;
  status: string;
  note: string | null;
  createdAt: string;
}

export interface WishlistItemInput {
  title: string;
  description?: string | null;
  imageRef?: string | null;
  priceInr: number;
  category: string;
  quantityWanted: number;
  note?: string | null;
}

export interface LedgerRow {
  id: string;
  donatedOn: string;
  category: string;
  donorDisplay: string;
  amountInr: number | null;
  currency: string | null;
  paymentMode: string | null;
  providerRef: string | null;
  status: string;
  linkedTo: string | null;
}


/** The four windows the donations ledger can be read over. A financial year is April to March. */
export type LedgerPeriodKind = "WEEK" | "MONTH" | "FINANCIAL_YEAR" | "YEAR";

/**
 * The window the server resolved, and the window a year earlier it compared against.
 *
 * <p>These dates come back from the server rather than being worked out here on purpose: the same
 * pair then drives the rows beneath the tiles and the CSV export, so a screen that calculated its
 * own would eventually disagree with the server about where the financial year starts and hand the
 * accountant a file covering a different span from the figures above it.
 */
export interface LedgerPeriodWindow {
  period: LedgerPeriodKind;
  financialYear: number | null;
  from: string;
  to: string;
  previousFrom: string;
  previousTo: string;
}

/** {@code changePercent} is null where no percentage can be justified — see the tile's note. */
export interface CategoryComparison {
  total: number;
  previousTotal: number;
  changePercent: number | null;
}

export interface PeriodSummary {
  window: LedgerPeriodWindow;
  /** False when the temple's records do not reach back as far as the window being compared against. */
  hasPriorYear: boolean;
  byCategory: Record<string, CategoryComparison>;
  financialYearsWithGifts: number[];
}

export interface PayableView {
  invoiceId: string;
  invoiceNumber: string;
  vendorName: string;
  amount: number;
  paidToDate: number;
  outstanding: number;
  dueDate: string | null;
  agingBucket: string;
}

export interface RecurringPlanView {
  id: string;
  frequency: string;
  amountInr: number;
  status: string;
  subscriptionId: string;
  shortUrl: string | null;
  createdAt: string;
}

/**
 * The filename the server chose, read back off the download header — with the same name derived
 * locally if it cannot be read.
 *
 * <p>The fallback is not decoration. A browser hides response headers from a cross-origin page
 * unless the server exposes them, and when `Content-Disposition` was not exposed every export
 * downloaded under a generic name (UAT003-1). The header is exposed now, but a download that arrives
 * without a name is still worth naming correctly, so the fallback follows the same convention.
 */
function exportFilename(response: Response, slug: string, fallback?: string): string {
  const header = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (encoded) return decodeURIComponent(encoded[1]);
  const plain = /filename="([^"]+)"/i.exec(header);
  return plain ? plain[1] : (fallback ?? `${slug}-ikms-data-export.xlsx`);
}

/**
 * Reads the server's own error off a failed binary response.
 *
 * <p>Downloads are plain `fetch` rather than {@link request}, because the body is bytes and not
 * JSON. That is no reason to throw the server's answer away: reporting KMS-0000 for every failure
 * sent a tester chasing a network fault when the real answer was KMS-4402, the file was never
 * where the API looked for it, and the code named exactly that.
 */
async function errorFromBinaryResponse(
  response: Response,
  message: string,
  action: string
): Promise<ApiError> {
  try {
    const body = (await response.json()) as ErrorPayload;
    if (body?.code) return new ApiError(body);
  } catch {
    // Not a JSON envelope — a proxy error page, or nothing at all. Fall through.
  }
  return new ApiError({ code: "KMS-0000", message, action, fieldErrors: [] });
}

export const api = {

  // --- Kitchens (E10-S2) ---

  listKitchens: (includeArchived: boolean, token?: string) =>
    request<Kitchen[]>(`/api/v1/kitchens${includeArchived ? "?includeArchived=true" : ""}`, {
      method: "GET",
      token,
    }),

  getKitchen: (id: string, token?: string) =>
    request<Kitchen>(`/api/v1/kitchens/${id}`, { method: "GET", token }),

  createKitchen: (input: KitchenInput, token?: string) =>
    request<{ id: string }>("/api/v1/kitchens", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateKitchen: (id: string, input: KitchenInput, token?: string) =>
    request<void>(`/api/v1/kitchens/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  archiveKitchen: (id: string, token?: string) =>
    request<void>(`/api/v1/kitchens/${id}/archive`, { method: "POST", token }),

  restoreKitchen: (id: string, token?: string) =>
    request<void>(`/api/v1/kitchens/${id}/restore`, { method: "POST", token }),

  deleteKitchen: (id: string, token?: string) =>
    request<void>(`/api/v1/kitchens/${id}`, { method: "DELETE", token }),

  // --- The work order (E10-S11) ---

  /**
   * Queues the sheet for an approved request. The batches on it are worked out when it renders,
   * not when the request was approved — an afternoon's cooking can empty the lot a sheet printed
   * this morning would have named.
   */
  requestWorkOrder: (requestId: string, language: string | null, token?: string) =>
    request<{ documentId: string; status: string }>(
      `/api/v1/work-orders?requestId=${requestId}` +
        (language ? `&language=${encodeURIComponent(language)}` : ""),
      { method: "POST", token }
    ),

  /** Every language the sheet can be printed in, and the one the picker opens on. */
  workOrderLanguages: (token?: string) =>
    request<{ languages: string[]; defaultLanguage: string }>("/api/v1/work-orders/languages", {
      method: "GET",
      token,
    }),

  getWorkOrderDocument: (documentId: string, token?: string) =>
    request<DocumentView>(`/api/v1/work-orders/documents/${documentId}`, { method: "GET", token }),

  downloadWorkOrderDocument: async (documentId: string, token?: string): Promise<Blob> => {
    const response = await fetch(`${BASE_URL}/api/v1/work-orders/documents/${documentId}/download`, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      throw await errorFromBinaryResponse(
        response,
        "We couldn't download that work order.",
        "Try again in a moment."
      );
    }
    return response.blob();
  },

  /**
   * The same sheet as HTML, for the browser's own print dialog.
   *
   * <p>Needs an Authorization header, so it cannot be a plain link — fetch it and write it into a
   * new window, as the purchase-order page does.
   */
  workOrderPrintUrl: (requestId: string, language?: string): string =>
    `${BASE_URL}/api/v1/work-orders/print?requestId=${requestId}` +
    (language ? `&language=${encodeURIComponent(language)}` : ""),

  /** What turning the meal planner on would delete and deny. Asked before saving, never after. */
  mealPlannerImpact: (id: string, token?: string) =>
    request<MealPlannerImpact>(`/api/v1/kitchens/${id}/meal-planner-impact`, {
      method: "GET",
      token,
    }),

  // --- Ingredient requests (E10-S5 to S7) ---

  listIngredientRequests: (status: IngredientRequestStatus | null, token?: string) =>
    request<IngredientRequestSummary[]>(
      `/api/v1/ingredient-requests${status ? `?status=${status}` : ""}`,
      { method: "GET", token }
    ),

  getIngredientRequest: (id: string, token?: string) =>
    request<IngredientRequestDetail>(`/api/v1/ingredient-requests/${id}`, {
      method: "GET",
      token,
    }),

  createIngredientRequest: (input: IngredientRequestInput, token?: string) =>
    request<{ id: string }>("/api/v1/ingredient-requests", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateIngredientRequest: (id: string, input: IngredientRequestInput, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  deleteIngredientRequest: (id: string, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}`, { method: "DELETE", token }),

  submitIngredientRequest: (id: string, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}/submit`, { method: "POST", token }),

  approveIngredientRequest: (id: string, note: string | null, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}/approve`, {
      method: "POST",
      body: JSON.stringify({ note }),
      token,
    }),

  denyIngredientRequest: (id: string, note: string | null, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}/deny`, {
      method: "POST",
      body: JSON.stringify({ note }),
      token,
    }),

  withdrawIngredientRequest: (id: string, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}/withdraw`, { method: "POST", token }),

  /** Records what the store actually handed over. This is the act that moves stock. */
  recordIngredientIssue: (id: string, input: RecordIssueInput, token?: string) =>
    request<void>(`/api/v1/ingredient-requests/${id}/issue`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),
  // Who the backend understands the caller to be — role and tenant come from our own user record,
  // not the token. A 401 here means a valid Firebase identity with no account at a temple yet.
  /** Temples to choose from: near a point, near a named place, or by name. */
  temples: (options: { near?: string; q?: string; withinKm?: number } = {}, token?: string) => {
    const params = new URLSearchParams();
    if (options.near) params.set("near", options.near);
    if (options.q) params.set("q", options.q);
    if (options.withinKm) params.set("withinKm", String(options.withinKm));
    const query = params.toString();
    return request<TempleSummary[]>(`/api/v1/temples${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  joinTemple: (templeId: string, input: JoinTempleInput, token?: string) =>
    request<{ userId: string; tenantId: string }>(`/api/v1/temples/${templeId}/join`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  whoami: (token?: string) =>
    request<WhoAmI>("/api/v1/whoami", { method: "GET", token }),

  listTenants: (token?: string) =>
    request<TenantSummary[]>("/api/v1/tenants", { method: "GET", token }),

  getTenant: (id: string, token?: string) =>
    request<TenantDetail>(`/api/v1/tenants/${id}`, { method: "GET", token }),

  // Permanently deletes a temple and all its data (DELETE_TENANT). Returns 204. Refused with
  // KMS-4941 unless the temple was exported in the last 24 hours — the export is the only copy.
  deleteTenant: (id: string, token?: string) =>
    request<void>(`/api/v1/tenants/${id}`, { method: "DELETE", token }),

  /**
   * The temple's whole data set as an Excel workbook (DELETE_TENANT). Returns the file and the name
   * the server chose for it — named after the temple, so it still says whose data it is later.
   */
  exportTenant: async (
    id: string,
    slug: string,
    token?: string
  ): Promise<{ blob: Blob; filename: string }> => {
    const response = await fetch(`${BASE_URL}/api/v1/tenants/${id}/export`, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      throw new ApiError({
        code: "KMS-0000",
        message: "We couldn't export this temple's data.",
        action: "Try again in a moment. Don't delete the temple until you have the export.",
        fieldErrors: [],
      });
    }
    return { blob: await response.blob(), filename: exportFilename(response, slug) };
  },

  provisionTenant: (input: ProvisionTenantInput, token?: string) =>
    request<{ id: string; slug: string }>("/api/v1/tenants", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // The caller's own temple (Temple Admin). RLS scopes it server-side.
  listAuditEvents: (filters: AuditFilters = {}, token?: string) =>
    request<AuditPage>(`/api/v1/audit-events${toQuery(filters)}`, { method: "GET", token }),

  // A super-admin drilling into one temple's log. The access is itself recorded server-side.
  drillIntoTenantAudit: (tenantId: string, filters: AuditFilters = {}, token?: string) =>
    request<AuditPage>(`/api/v1/tenants/${tenantId}/audit-events${toQuery(filters)}`, {
      method: "GET",
      token,
    }),

  // The caller's own account (E1-S8). All self-scoped server-side to the authenticated user.
  getProfile: (token?: string) =>
    request<Profile>("/api/v1/profile", { method: "GET", token }),

  updatePreferredChannel: (channel: NotificationChannel, token?: string) =>
    request<Profile>("/api/v1/profile", {
      method: "PATCH",
      body: JSON.stringify({ preferredChannel: channel }),
      token,
    }),

  giveConsent: (token?: string) =>
    request<Profile>("/api/v1/profile/consent", { method: "POST", token }),

  // Liveness for the in-app operations view. Public and on its own shape, not the KMS error
  // contract: a 503 body still carries the status we want to display, so this reads the body on
  // any response rather than throwing. Only a dropped connection rejects.
  health: async (): Promise<HealthStatus> => {
    const response = await fetch(`${BASE_URL}/health`, { method: "GET" });
    return (await response.json()) as HealthStatus;
  },

  // Super-Admin ops (VIEW_PLATFORM_OPERATIONS). Platform-wide send totals for the Operations page;
  // deeper trends and alerting still live in Cloud Monitoring.
  opsNotifications: (token?: string) =>
    request<NotificationMetrics>("/api/v1/ops/notifications", { method: "GET", token }),

  // Temple user management (E1-S12). All behind MANAGE_USERS server-side, RLS-scoped to the tenant.
  // `role` narrows the list: the devotee register asks for VOLUNTEER, so a temple's staff never
  // travel to the browser only to be filtered out of sight there.
  listUsers: (token?: string, role?: UserRole) =>
    request<UserSummary[]>(`/api/v1/users${role ? `?role=${role}` : ""}`, { method: "GET", token }),

  changeUserRole: (id: string, role: UserRole, token?: string) =>
    request<void>(`/api/v1/users/${id}/role`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
      token,
    }),

  setUserStatus: (id: string, status: UserStatus, token?: string) =>
    request<void>(`/api/v1/users/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
      token,
    }),

  // Recipes (Epic 2). All behind MANAGE_RECIPES server-side, RLS-scoped to the tenant.
  listRecipeCategories: (token?: string) =>
    request<RecipeCategory[]>("/api/v1/recipe-categories", { method: "GET", token }),

  listRecipes: (filters: RecipeFilters = {}, token?: string) => {
    const params = new URLSearchParams();
    if (filters.categoryId) params.set("categoryId", filters.categoryId);
    if (filters.ingredientId) params.set("ingredientId", filters.ingredientId);
    if (filters.q) params.set("q", filters.q);
    if (filters.includeArchived) params.set("includeArchived", "true");
    const query = params.toString();
    return request<RecipeSummary[]>(`/api/v1/recipes${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  getRecipe: (id: string, token?: string) =>
    request<RecipeDetail>(`/api/v1/recipes/${id}`, { method: "GET", token }),

  /** The Recipes page's one box: the temple's own recipes and the shared library, together. */
  searchRecipes: (query: string, token?: string) =>
    request<RecipeSearchResult[]>(
      `/api/v1/recipes/search${query.trim() ? `?q=${encodeURIComponent(query.trim())}` : ""}`,
      { method: "GET", token }
    ),

  /** Takes this temple's own copy of a library recipe. The id in, the temple's new id out. */
  importRecipe: (masterRecipeId: string, token?: string) =>
    request<{ id: string; name: string; ingredientsCreated: number; categoryCreated: boolean }>(
      `/api/v1/recipes/import/${masterRecipeId}`,
      { method: "POST", token }
    ),

  getLibraryRecipe: (id: string, token?: string) =>
    request<MasterRecipeDetail>(`/api/v1/library/recipes/${id}`, { method: "GET", token }),

  listLibraryRecipes: (
    params: { q?: string; state?: string; category?: string; limit?: number } = {},
    token?: string
  ) => {
    const query = new URLSearchParams();
    if (params.q) query.set("q", params.q);
    if (params.state) query.set("state", params.state);
    if (params.category) query.set("category", params.category);
    if (params.limit) query.set("limit", String(params.limit));
    return request<MasterRecipeSummary[]>(
      `/api/v1/library/recipes${query.toString() ? `?${query}` : ""}`,
      { method: "GET", token }
    );
  },

  listLibraryStates: (token?: string) =>
    request<{ slug: string; name: string; recipes: number }[]>(`/api/v1/library/recipes/states`, {
      method: "GET",
      token,
    }),

  deleteLibraryRecipe: (id: string, token?: string) =>
    request<void>(`/api/v1/library/recipes/${id}`, { method: "DELETE", token }),

  loadRecipeLibrary: (token?: string) =>
    request<{ books: number; recipes: number; bare: number; withState: number; withStateAndCategory: number }>(
      `/api/v1/library/recipes/load`,
      { method: "POST", token }
    ),

  scaleRecipe: (id: string, targetYield: number, token?: string) =>
    request<ScaledRecipe>(`/api/v1/recipes/${id}/scaled?targetYield=${targetYield}`, {
      method: "GET",
      token,
    }),

  translateRecipe: (id: string, language: string, token?: string) =>
    request<TranslatedRecipe>(`/api/v1/recipes/${id}/translations/${language}`, {
      method: "GET",
      token,
    }),

  // Documents (E2-S5): request a PDF, poll its status, then download the bytes through the
  // authorized backend proxy (the token can't ride in a plain link, so this fetches a Blob).
  requestRecipePdf: (
    id: string,
    options: { targetYield?: number; language?: string } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (options.targetYield != null) params.set("targetYield", String(options.targetYield));
    if (options.language) params.set("language", options.language);
    const query = params.toString();
    return request<{ documentId: string; status: string }>(
      `/api/v1/recipes/${id}/pdf${query ? `?${query}` : ""}`,
      { method: "POST", token }
    );
  },

  createRecipe: (input: RecipeInput, token?: string) =>
    request<{ id: string }>("/api/v1/recipes", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateRecipe: (id: string, input: RecipeInput, token?: string) =>
    request<void>(`/api/v1/recipes/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  /** Takes it out of the planner and the default list; the recipe and its history stay. */
  archiveRecipe: (id: string, token?: string) =>
    request<void>(`/api/v1/recipes/${id}/archive`, { method: "POST", token }),

  restoreRecipe: (id: string, token?: string) =>
    request<void>(`/api/v1/recipes/${id}/restore`, { method: "POST", token }),

  /**
   * Removes it outright. Refused with KMS-4967 for a recipe any meal plan has ever named — that
   * one is archived instead, so the record of what was cooked keeps its dish.
   */
  deleteRecipe: (id: string, token?: string) =>
    request<void>(`/api/v1/recipes/${id}`, { method: "DELETE", token }),

  // Ingredient catalogue (E2-S1).
  listIngredients: (token?: string) =>
    request<IngredientView[]>("/api/v1/ingredients", { method: "GET", token }),

  createIngredient: (input: CreateIngredientInput, token?: string) =>
    request<{ id: string }>("/api/v1/ingredients", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateIngredient: (id: string, input: UpdateIngredientInput, token?: string) =>
    request<void>(`/api/v1/ingredients/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  setIngredientSattvicFlag: (id: string, sattvicProhibited: boolean, token?: string) =>
    request<void>(`/api/v1/ingredients/${id}/sattvic-flag`, {
      method: "PATCH",
      body: JSON.stringify({ sattvicProhibited }),
      token,
    }),

  deleteIngredient: (id: string, token?: string) =>
    request<void>(`/api/v1/ingredients/${id}`, { method: "DELETE", token }),

  // Translation glossary (E2-S6).
  listGlossary: (language?: string, token?: string) =>
    request<GlossaryEntry[]>(
      `/api/v1/translation-glossary${language ? `?language=${encodeURIComponent(language)}` : ""}`,
      { method: "GET", token }
    ),

  addGlossaryEntry: (
    input: { language: string; sourceTerm: string; targetTerm: string },
    token?: string
  ) =>
    request<{ id: string }>("/api/v1/translation-glossary", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  deleteGlossaryEntry: (id: string, token?: string) =>
    request<void>(`/api/v1/translation-glossary/${id}`, { method: "DELETE", token }),

  getDocument: (id: string, token?: string) =>
    request<DocumentView>(`/api/v1/documents/${id}`, { method: "GET", token }),

  downloadDocument: async (id: string, token?: string): Promise<Blob> => {
    const response = await fetch(`${BASE_URL}/api/v1/documents/${id}/download`, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      throw await errorFromBinaryResponse(
        response,
        "We couldn't download that file.",
        "Try again in a moment."
      );
    }
    return response.blob();
  },

  // Inventory: consumable stock (E3), all behind MANAGE_INVENTORY server-side, RLS-scoped.
  listInventory: (filters: InventoryFilters = {}, token?: string) => {
    const params = new URLSearchParams();
    if (filters.location) params.set("location", filters.location);
    if (filters.category) params.set("category", filters.category);
    if (filters.expiringWithinDays != null)
      params.set("expiringWithinDays", String(filters.expiringWithinDays));
    const query = params.toString();
    return request<StockItemView[]>(`/api/v1/inventory/items${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  lowStockItems: (token?: string) =>
    request<StockItemView[]>("/api/v1/inventory/items/low-stock", { method: "GET", token }),

  getInventoryItem: (id: string, token?: string) =>
    request<StockDetail>(`/api/v1/inventory/items/${id}`, { method: "GET", token }),

  createInventoryItem: (input: CreateInventoryItemInput, token?: string) =>
    request<{ id: string }>("/api/v1/inventory/items", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateInventoryItem: (id: string, input: Omit<CreateInventoryItemInput, "ingredientId">, token?: string) =>
    request<void>(`/api/v1/inventory/items/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  deleteInventoryItem: (id: string, token?: string) =>
    request<void>(`/api/v1/inventory/items/${id}`, { method: "DELETE", token }),

  adjustStock: (id: string, input: AdjustStockInput, token?: string) =>
    request<{ id: string }>(`/api/v1/inventory/items/${id}/adjustments`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  listMovements: (
    filters: { ingredientId?: string; type?: string; limit?: number } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (filters.ingredientId) params.set("ingredientId", filters.ingredientId);
    if (filters.type) params.set("type", filters.type);
    if (filters.limit != null) params.set("limit", String(filters.limit));
    const query = params.toString();
    return request<StockMovement[]>(`/api/v1/inventory/movements${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  compensateMovement: (id: string, note: string, token?: string) =>
    request<{ id: string }>(`/api/v1/inventory/movements/${id}/compensate`, {
      method: "POST",
      body: JSON.stringify({ note }),
      token,
    }),

  // Donations (E3-S5). Recording is MANAGE_INVENTORY; reading is the ledger, behind VIEW_DONATIONS.
  recordDonation: (input: RecordDonationInput, token?: string) =>
    request<{ id: string }>("/api/v1/donations", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // Vaishnava calendar (E4-S1/S3). Read behind MANAGE_MEAL_PLANS; override behind OVERRIDE_CALENDAR_DATE.
  calendarRange: (from: string, to: string, token?: string) =>
    request<CalendarDayView[]>(`/api/v1/calendar?from=${from}&to=${to}`, { method: "GET", token }),

  setCalendarOverride: (date: string, input: SetCalendarOverrideInput, token?: string) =>
    request<void>(`/api/v1/calendar/${date}/override`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  revertCalendarOverride: (date: string, token?: string) =>
    request<void>(`/api/v1/calendar/${date}/override`, { method: "DELETE", token }),

  // Festival occasions (E4-S2).
  listOccasions: (token?: string) =>
    request<OccasionView[]>("/api/v1/occasions", { method: "GET", token }),

  resolvedOccasions: (from: string, to: string, token?: string) =>
    request<ResolvedOccasion[]>(`/api/v1/occasions/resolved?from=${from}&to=${to}`, { method: "GET", token }),

  // Meal slots + plans (E4-S4/S5/S6).
  listMealKinds: (token?: string) =>
    request<MealKindView[]>("/api/v1/meal-kinds", { method: "GET", token }),

  // Curating the kinds and their times is a temple-settings decision (MANAGE_TEMPLE_SETTINGS).
  updateMealKind: (id: string, input: MealKindInput, token?: string) =>
    request<void>(`/api/v1/meal-kinds/${id}`, { method: "PUT", body: JSON.stringify(input), token }),

  createMealKind: (input: MealKindInput, token?: string) =>
    request<{ id: string }>("/api/v1/meal-kinds", { method: "POST", body: JSON.stringify(input), token }),

  listMealPlans: (
    filters: { from?: string; to?: string; status?: MealStatus; dayType?: DayType } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.status) params.set("status", filters.status);
    if (filters.dayType) params.set("dayType", filters.dayType);
    const query = params.toString();
    return request<MealPlanView[]>(`/api/v1/meal-plans${query ? `?${query}` : ""}`, { method: "GET", token });
  },

  // The whole morning screen in one request: it is the first thing loaded each day, often on a
  // phone on a temple's connection.
  today: (token?: string) => request<TodayView>("/api/v1/today", { method: "GET", token }),

  /** A temple's own settings. `locale` is a BCP-47 tag — "en-IN", "kn-IN". */
  templeSettings: (token?: string) =>
    request<{
      volunteerBroadcastDailyLimit: number;
      locale: string;
      /** Null until somebody chooses, which is not the same as choosing the default. */
      themeId: string | null;
    }>("/api/v1/settings", {
      method: "GET",
      token,
    }),

  /**
   * Records which colour scheme the temple works in. Everybody who serves there sees it on their
   * next load.
   *
   * <p>There is no matching endpoint to read the catalogue, and there never will be: the themes
   * live in `lib/theme-packs.ts`, in this bundle. All that crosses the wire is which one.
   */
  setTempleTheme: (themeId: string, token?: string) =>
    request<void>("/api/v1/settings/theme", {
      method: "PUT",
      body: JSON.stringify({ themeId }),
      token,
    }),

  /** The language the temple works in, as an ISO 639-1 code. The region is added server-side. */
  setTempleLanguage: (language: string, token?: string) =>
    request<void>("/api/v1/settings/language", {
      method: "PUT",
      body: JSON.stringify({ language }),
      token,
    }),

  mealDayContext: (date: string, token?: string) =>
    request<DayContext>(`/api/v1/meal-plans/day-context?date=${date}`, { method: "GET", token }),

  ekadashiCheck: (date: string, recipeId: string, token?: string) =>
    request<EkadashiCheck>(`/api/v1/meal-plans/ekadashi-check?date=${date}&recipeId=${recipeId}`, {
      method: "GET",
      token,
    }),

  createMealPlan: (input: CreateMealPlanInput, token?: string) =>
    request<{ id: string }>("/api/v1/meal-plans", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Swap the recipe or re-scale a dish in place (B4). The whole meal is sent, as when it was
   * planned, because a partial update would leave the server guessing which silence meant "unchanged"
   * and which meant "clear it".
   */
  updateMealPlan: (id: string, input: UpdateMealPlanInput, token?: string) =>
    request<void>(`/api/v1/meal-plans/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  cancelMealPlan: (id: string, token?: string) =>
    request<void>(`/api/v1/meal-plans/${id}/cancel`, { method: "POST", token }),

  /**
   * Copies the previous week into the week beginning weekStart. Only ever adds — a day with
   * anything already planned on it is left alone — so pressing it twice is harmless.
   */
  duplicateWeek: (weekStart: string, token?: string) =>
    request<DuplicateWeekResult>(`/api/v1/meal-plans/duplicate-week?weekStart=${weekStart}`, {
      method: "POST",
      token,
    }),

  // ---- Meals as whole things, and the job card (B5) -------------------------
  //
  // There is no per-dish "mark cooked" call any more. A meal is recorded once, as a whole, from the
  // card that came back to the office — which is also the only moment its ingredients leave stock.

  mealServices: (from: string, to: string, token?: string) =>
    request<MealServiceView[]>(`/api/v1/meal-services?from=${from}&to=${to}`, {
      method: "GET",
      token,
    }),

  /**
   * How many people each meal in the range takes, and how many it has (item 24). One readout per
   * meal: the crew pebble on the planner block and the workforce line on Today both read this, so
   * neither can quietly disagree with the other about the same lunch.
   */
  mealCrew: (from: string, to: string, token?: string) =>
    request<MealCrewView[]>(`/api/v1/meal-crew?from=${from}&to=${to}`, { method: "GET", token }),

  /**
   * What to open the crew counter at for a new meal of this kind: the median of the last three
   * ordinary meals of it. Null where the temple has never recorded one — the field opens empty,
   * which is honest, where a made-up number would not be.
   */
  suggestedCrew: (mealKind: string, token?: string) =>
    request<{ crewRequired: number | null }>(
      `/api/v1/meal-crew/suggested?mealKind=${encodeURIComponent(mealKind)}`,
      { method: "GET", token }
    ),

  /**
   * What was cooked for this festival last time (item 26b). `before` is the date being planned: the
   * meal being composed carries the same occasion name from its first saved preparation, so without
   * it the composer would be offered back what it has just put in.
   */
  menuHistory: (occasionName: string, before: string, token?: string) =>
    request<MenuHistoryView>(
      `/api/v1/meal-plans/menu-history?occasionName=${encodeURIComponent(occasionName)}&before=${before}`,
      { method: "GET", token }
    ),

  /** How many meals went unrecorded in the range, and the plates each kind came to on `from`. */
  mealServiceSummary: (from: string, to: string, token?: string) =>
    request<{ unrecorded: number; platesByMealKind: Record<string, number> }>(
      `/api/v1/meal-services/summary?from=${from}&to=${to}`,
      { method: "GET", token }
    ),

  recordMeal: (input: RecordMealInput, token?: string) =>
    request<MealServiceView>("/api/v1/meal-services/record", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Queues a job card, issuing its number if this is the first print of that meal.
   *
   * <p>`language` is the recipes appendix's, not the sheet's — the worksheet is always English.
   * Pass `"none"` for the worksheet on its own.
   */
  requestJobCard: (date: string, mealKind: string, language?: string, token?: string) =>
    request<{ documentId: string; cardNumber: string; status: string }>(
      `/api/v1/job-cards?date=${date}&mealKind=${encodeURIComponent(mealKind)}` +
        (language ? `&language=${encodeURIComponent(language)}` : ""),
      { method: "POST", token }
    ),

  /**
   * What languages this meal's recipes can be printed in, and the one the picker opens on.
   *
   * <p>Never the full list of 23. English is always there because it is the source text; the rest
   * are only the languages a translation actually exists in for the preparations on this card.
   * Offering one with nothing behind it would print an English appendix under a Kannada heading.
   */
  jobCardLanguages: (date: string, mealKind: string, token?: string) =>
    request<{ languages: string[]; defaultLanguage: string }>(
      `/api/v1/job-cards/languages?date=${date}&mealKind=${encodeURIComponent(mealKind)}`,
      { method: "GET", token }
    ),

  getJobCardDocument: (documentId: string, token?: string) =>
    request<DocumentView>(`/api/v1/job-cards/documents/${documentId}`, { method: "GET", token }),

  downloadJobCardDocument: async (documentId: string, token?: string): Promise<Blob> => {
    const response = await fetch(`${BASE_URL}/api/v1/job-cards/documents/${documentId}/download`, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      throw await errorFromBinaryResponse(
        response,
        "We couldn't download that job card.",
        "Try again in a moment."
      );
    }
    return response.blob();
  },

  /** The browser print view of the same card. `language` means what it does above. */
  jobCardPrintUrl: (date: string, mealKind: string, language?: string): string =>
    `${BASE_URL}/api/v1/job-cards/print?date=${date}&mealKind=${encodeURIComponent(mealKind)}` +
    (language ? `&language=${encodeURIComponent(language)}` : ""),

  mealSufficiency: (from: string, to: string, token?: string) =>
    request<MealSufficiency[]>(`/api/v1/meal-plans/sufficiency?from=${from}&to=${to}`, {
      method: "GET",
      token,
    }),

  /**
   * The estimated cost of a day's materials (B2), behind MANAGE_MEAL_PLANS. Omit the date and the
   * server answers for today at the temple, which is not always today in the reader's browser.
   */
  materialsCost: (date?: string, token?: string) =>
    request<MaterialsCost>(`/api/v1/materials-cost${date ? `?date=${date}` : ""}`, {
      method: "GET",
      token,
    }),

  // ---- Vendors (E5-S1), behind MANAGE_VENDORS server-side. -----------------
  listVendors: (activeOnly = false, token?: string) =>
    request<VendorView[]>(`/api/v1/vendors${activeOnly ? "?activeOnly=true" : ""}`, {
      method: "GET",
      token,
    }),

  getVendor: (id: string, token?: string) =>
    request<VendorDetailView>(`/api/v1/vendors/${id}`, { method: "GET", token }),

  createVendor: (input: VendorInput, token?: string) =>
    request<{ id: string }>("/api/v1/vendors", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateVendor: (id: string, input: VendorInput, token?: string) =>
    request<void>(`/api/v1/vendors/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  deactivateVendor: (id: string, token?: string) =>
    request<void>(`/api/v1/vendors/${id}/deactivate`, { method: "POST", token }),

  reactivateVendor: (id: string, token?: string) =>
    request<void>(`/api/v1/vendors/${id}/reactivate`, { method: "POST", token }),

  setVendorSupply: (
    id: string,
    input: { ingredientId: string; lastPrice?: number | null; preferred: boolean },
    token?: string
  ) =>
    request<void>(`/api/v1/vendors/${id}/supplies`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  removeVendorSupply: (id: string, ingredientId: string, token?: string) =>
    request<void>(`/api/v1/vendors/${id}/supplies/${ingredientId}`, { method: "DELETE", token }),

  // ---- Order list (E5-S2), behind MANAGE_PURCHASE_ORDERS. ------------------
  listOrderList: (token?: string) =>
    request<OrderListLineView[]>("/api/v1/order-list", { method: "GET", token }),

  regenerateOrderList: (token?: string) =>
    request<{ lines: number }>("/api/v1/order-list/regenerate", { method: "POST", token }),

  updateOrderLine: (
    ingredientId: string,
    input: { suggestedQty?: number | null; suggestedVendorId?: string | null; included: boolean },
    token?: string
  ) =>
    request<void>(`/api/v1/order-list/${ingredientId}`, {
      method: "PATCH",
      body: JSON.stringify(input),
      token,
    }),

  // ---- Purchase orders (E5-S3), behind MANAGE_PURCHASE_ORDERS. -------------
  listPurchaseOrders: (status?: PoStatus, token?: string) =>
    request<PurchaseOrderView[]>(
      `/api/v1/purchase-orders${status ? `?status=${status}` : ""}`,
      { method: "GET", token }
    ),

  getPurchaseOrder: (id: string, token?: string) =>
    request<PurchaseOrderDetailView>(`/api/v1/purchase-orders/${id}`, { method: "GET", token }),

  createPurchaseOrder: (input: CreatePurchaseOrderInput, token?: string) =>
    request<{ id: string }>("/api/v1/purchase-orders", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  generatePurchaseOrders: (ingredientIds: string[] | null, token?: string) =>
    request<{ purchaseOrderIds: string[] }>("/api/v1/purchase-orders/generate", {
      method: "POST",
      body: JSON.stringify({ ingredientIds }),
      token,
    }),

  updatePurchaseOrder: (
    id: string,
    input: {
      neededBy?: string | null;
      deliveryLocation?: string | null;
      notes?: string | null;
      lines: PoLineInput[];
    },
    token?: string
  ) =>
    request<void>(`/api/v1/purchase-orders/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  sendPurchaseOrder: (id: string, token?: string) =>
    request<void>(`/api/v1/purchase-orders/${id}/send`, { method: "POST", token }),

  cancelPurchaseOrder: (id: string, reason: string, token?: string) =>
    request<void>(`/api/v1/purchase-orders/${id}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  sendPurchaseOrderWhatsApp: (id: string, token?: string) =>
    request<{ notificationId: string }>(`/api/v1/purchase-orders/${id}/whatsapp`, {
      method: "POST",
      token,
    }),

  // ---- Receiving (E5-S6). --------------------------------------------------
  listReceipts: (poId: string, token?: string) =>
    request<GoodsReceiptView[]>(`/api/v1/purchase-orders/${poId}/receipts`, {
      method: "GET",
      token,
    }),

  receiveDelivery: (poId: string, input: ReceiveDeliveryInput, token?: string) =>
    request<GoodsReceiptView>(`/api/v1/purchase-orders/${poId}/receipts`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // ---- Vendor invoices (E5-S8). --------------------------------------------
  listInvoices: (
    filters: { status?: InvoiceStatus; overdue?: boolean } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (filters.status) params.set("status", filters.status);
    if (filters.overdue) params.set("overdue", "true");
    const query = params.toString();
    return request<VendorInvoiceView[]>(`/api/v1/vendor-invoices${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  getInvoice: (id: string, token?: string) =>
    request<VendorInvoiceView>(`/api/v1/vendor-invoices/${id}`, { method: "GET", token }),

  recordInvoice: (input: RecordInvoiceInput, token?: string) =>
    request<RecordInvoiceResponse>("/api/v1/vendor-invoices", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // ---- PO documents (E5-S4 / E5-S5). ---------------------------------------
  requestPurchaseOrderPdf: (poId: string, language?: string, token?: string) =>
    request<{ documentId: string; status: string }>(
      `/api/v1/purchase-orders/${poId}/pdf${language ? `?language=${encodeURIComponent(language)}` : ""}`,
      { method: "POST", token }
    ),

  listPurchaseOrderDocuments: (poId: string, token?: string) =>
    request<DocumentView[]>(`/api/v1/purchase-orders/${poId}/documents`, {
      method: "GET",
      token,
    }),

  getPurchaseOrderDocument: (poId: string, documentId: string, token?: string) =>
    request<DocumentView>(`/api/v1/purchase-orders/${poId}/documents/${documentId}`, {
      method: "GET",
      token,
    }),

  downloadPurchaseOrderDocument: async (
    poId: string,
    documentId: string,
    token?: string
  ): Promise<Blob> => {
    const response = await fetch(
      `${BASE_URL}/api/v1/purchase-orders/${poId}/documents/${documentId}/download`,
      { method: "GET", headers: token ? { Authorization: `Bearer ${token}` } : {} }
    );
    if (!response.ok) {
      throw await errorFromBinaryResponse(
        response,
        "We couldn't download that document.",
        "Try again in a moment."
      );
    }
    return response.blob();
  },

  purchaseOrderPrintUrl: (poId: string, language?: string): string =>
    `${BASE_URL}/api/v1/purchase-orders/${poId}/print${language ? `?language=${encodeURIComponent(language)}` : ""}`,

  // ---- Communications (E8-S2, E8-S3), behind MANAGE_COMMUNICATIONS. --------
  listCommunications: (token?: string) =>
    request<CommunicationView[]>("/api/v1/communications", { method: "GET", token }),

  communicationCategories: (token?: string) =>
    request<CommunicationCategoryOption[]>("/api/v1/communications/categories", {
      method: "GET",
      token,
    }),

  createCommunication: (input: SaveCommunicationInput, token?: string) =>
    request<{ id: string }>("/api/v1/communications", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateCommunication: (id: string, input: SaveCommunicationInput, token?: string) =>
    request<void>(`/api/v1/communications/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  previewCommunication: (id: string, token?: string) =>
    request<CommunicationPreview>(`/api/v1/communications/${id}/preview`, { method: "GET", token }),

  /** How many devotees it would reach right now — the number the confirmation shows. */
  communicationAudience: (id: string, token?: string) =>
    request<{ count: number }>(`/api/v1/communications/${id}/audience`, { method: "GET", token }),

  testCommunication: (id: string, token?: string) =>
    request<void>(`/api/v1/communications/${id}/test`, { method: "POST", token }),

  sendCommunication: (id: string, token?: string) =>
    request<{ audience: number; queued: number }>(`/api/v1/communications/${id}/send`, {
      method: "POST",
      token,
    }),

  communicationDeliveries: (id: string, token?: string) =>
    request<CommunicationDelivery[]>(`/api/v1/communications/${id}/deliveries`, {
      method: "GET",
      token,
    }),

  // ---- A devotee's own preferences (E8-S1). Own row only, so no permission. -
  communicationPreferences: (token?: string) =>
    request<CommunicationPreferencesView>("/api/v1/profile/communications", {
      method: "GET",
      token,
    }),

  setCommunicationPreference: (
    input: { allOptional?: boolean; category?: CommunicationCategory; wanted?: boolean },
    token?: string
  ) =>
    request<CommunicationPreferencesView>("/api/v1/profile/communications", {
      method: "PUT",
      body: JSON.stringify({ wanted: false, ...input }),
      token,
    }),

  // ---- Public: no session, because neither of these can require one. --------
  /** The web copy a WhatsApp link points at, and what "read in your browser" opens. */
  publicCommunication: (publicToken: string) =>
    request<PublicCommunication>(`/api/v1/public/communications/${publicToken}`, { method: "GET" }),

  /** What this unsubscribe link would stop. Describing is not doing. */
  describeUnsubscribe: (token: string) =>
    request<{ valid: boolean; allOptional?: boolean; category?: string; label?: string }>(
      `/api/v1/public/unsubscribe?token=${encodeURIComponent(token)}`,
      { method: "GET" }
    ),

  unsubscribe: (token: string) =>
    request<{ done: boolean; label?: string }>(
      `/api/v1/public/unsubscribe?token=${encodeURIComponent(token)}`,
      { method: "POST" }
    ),

  // ---- The staff register (E6-S8), behind MANAGE_STAFF. --------------------
  staffRegister: (token?: string) =>
    request<StaffRegisterView>("/api/v1/staff/register", { method: "GET", token }),

  jobTitles: (token?: string) =>
    request<JobTitleOption[]>("/api/v1/staff/job-titles", { method: "GET", token }),

  /**
   * Hiring, and the cross-temple check that runs as part of it (B9). Either the person was taken on
   * — `id` — or there are findings the admin should read first. Never a refusal: a match flags and
   * never blocks, so re-sending with `acknowledgedBanCheckId` completes the hire.
   */
  hireStaff: (input: HireStaffInput, token?: string) =>
    request<HireOutcome>("/api/v1/staff/members", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateStaffMember: (id: string, input: UpdateStaffInput, token?: string) =>
    request<void>(`/api/v1/staff/members/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  endEmployment: (id: string, input: EndEmploymentInput, token?: string) =>
    request<void>(`/api/v1/staff/members/${id}/end-employment`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** The whole PAN. Its own request because reading it lands on the audit trail. */
  revealStaffPan: (id: string, token?: string) =>
    request<{ pan?: string }>(`/api/v1/staff/members/${id}/pan`, { method: "GET", token }),

  // ---- Bans (B9), behind MANAGE_STAFF. -------------------------------------
  // Note what is missing and keep it missing: nothing here searches, lists or reads a record this
  // temple did not raise. The only way another temple's record reaches a screen is as a finding
  // from a hire, and that is the control the whole feature rests on.

  banCategories: (token?: string) =>
    request<BanCategoryOption[]>("/api/v1/staff/ban-categories", { method: "GET", token }),

  /** The records this temple raised — its own, and only ever its own. */
  templeBans: (token?: string) =>
    request<EmploymentBanView[]>("/api/v1/staff/bans", { method: "GET", token }),

  /** Correcting a record. Only the temple that raised it may (KMS-4307). */
  amendBan: (id: string, input: RaiseBanInput, token?: string) =>
    request<void>(`/api/v1/staff/bans/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  /** Taking a record back. It stays on file and stops appearing at hires. */
  retractBan: (id: string, reason: string | null, token?: string) =>
    request<void>(`/api/v1/staff/bans/${id}/retraction`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  /**
   * The admin read the findings and decided not to hire. Recorded because nobody was taken on, so
   * there is no staff record for the decision to live on — and walking away is the more responsible
   * of the two answers, so it should not be the one that leaves no trace.
   */
  abandonHireCheck: (checkId: string, token?: string) =>
    request<void>(`/api/v1/staff/hire-checks/${checkId}/abandoned`, { method: "POST", token }),

  // ---- Staff pay (B8), behind MANAGE_STAFF. -------------------------------
  staffPay: (id: string, token?: string) =>
    request<StaffPayView>(`/api/v1/staff/members/${id}/pay`, { method: "GET", token }),

  /** The payment and the advances it repays are one request: they are one act at the desk. */
  recordStaffPayment: (id: string, input: RecordStaffPaymentInput, token?: string) =>
    request<{ id: string }>(`/api/v1/staff/members/${id}/payments`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  recordStaffAdvance: (id: string, input: RecordStaffAdvanceInput, token?: string) =>
    request<{ id: string }>(`/api/v1/staff/members/${id}/advances`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** Strikes an entry made in error. Nothing is deleted, so this is a POST and not a DELETE. */
  voidStaffPayment: (id: string, paymentId: string, token?: string) =>
    request<void>(`/api/v1/staff/members/${id}/payments/${paymentId}/void`, { method: "POST", token }),

  voidStaffAdvance: (id: string, advanceId: string, token?: string) =>
    request<void>(`/api/v1/staff/members/${id}/advances/${advanceId}/void`, { method: "POST", token }),

  // ---- Staff schedule (E6-S1), behind MANAGE_STAFF_SCHEDULE. ---------------
  getStaffProfile: (id: string, token?: string) =>
    request<StaffProfileDetailView>(`/api/v1/staff/profiles/${id}`, { method: "GET", token }),

  setStaffTemplate: (id: string, days: ScheduleDay[], token?: string) =>
    request<void>(`/api/v1/staff/profiles/${id}/template`, {
      method: "PUT",
      body: JSON.stringify({ days }),
      token,
    }),

  setStaffException: (
    id: string,
    input: {
      exceptionDate: string;
      working: boolean;
      startTime?: string | null;
      endTime?: string | null;
      note?: string | null;
    },
    token?: string
  ) =>
    request<void>(`/api/v1/staff/profiles/${id}/exceptions`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  deleteStaffException: (id: string, exceptionId: string, token?: string) =>
    request<void>(`/api/v1/staff/profiles/${id}/exceptions/${exceptionId}`, { method: "DELETE", token }),

  staffWeek: (weekStart: string, token?: string) =>
    request<WeekScheduleView>(`/api/v1/staff/schedule/week?weekStart=${weekStart}`, {
      method: "GET",
      token,
    }),

  /**
   * Moves a working day to another date. One call, because both halves are written together — a
   * swap sent as two requests is a swap that ends up half-done the first time the second one fails.
   */
  swapStaffShift: (
    id: string,
    input: { fromDate: string; toDate: string; note?: string | null },
    token?: string
  ) =>
    request<void>(`/api/v1/staff/profiles/${id}/exceptions/swap`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  myStaffSchedule: (token?: string) =>
    request<StaffProfileDetailView>("/api/v1/staff/schedule/me", { method: "GET", token }),

  // ---- Leave (B7). The person's own, behind REQUEST_OWN_LEAVE. -------------
  myLeave: (token?: string) => request<LeaveView[]>("/api/v1/leave/mine", { method: "GET", token }),

  requestLeave: (input: RequestLeaveInput, token?: string) =>
    request<{ id: string }>("/api/v1/leave/mine", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  withdrawLeave: (id: string, token?: string) =>
    request<void>(`/api/v1/leave/mine/${id}`, { method: "DELETE", token }),

  // ---- Leave, the approver's side, behind APPROVE_LEAVE. -------------------
  leaveQueue: (token?: string) => request<LeaveView[]>("/api/v1/leave", { method: "GET", token }),

  /** Recording leave for a staff member — the janitor with no app, and the grid's "mark them off". */
  recordLeave: (input: RecordLeaveInput, token?: string) =>
    request<{ id: string }>("/api/v1/leave", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * What approving this leave would cost the kitchen, meal by meal — "Approving this leaves Lunch on
   * 24 Aug at 4 of 8." Told, never enforced: nothing here can refuse a day off. Empty where the
   * person was not standing in for any meal on those days.
   */
  leaveImpact: (id: string, token?: string) =>
    request<MealCrewView[]>(`/api/v1/leave/${id}/impact`, { method: "GET", token }),

  decideLeave: (id: string, decision: "approve" | "decline" | "revoke", note?: string | null, token?: string) =>
    request<void>(`/api/v1/leave/${id}/${decision}`, {
      method: "POST",
      body: JSON.stringify({ note: note ?? null }),
      token,
    }),

  /**
   * How many hands there are, per date (B1/B3). Behind MANAGE_MEAL_PLANS, because the pebbles this
   * feeds sit on the planner that kitchen staff read every morning; what it carries is a head count
   * and no name.
   */
  workforce: (from: string, to: string, token?: string) =>
    request<WorkforceCount[]>(`/api/v1/workforce?from=${from}&to=${to}`, { method: "GET", token }),

  // ---- Shifts, poster side (E6-S2/S4/S6/S7), behind MANAGE_VOLUNTEER_SHIFTS.
  listShifts: (
    filters: { from?: string; to?: string; includeCancelled?: boolean } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.includeCancelled) params.set("includeCancelled", "true");
    const query = params.toString();
    return request<ShiftView[]>(`/api/v1/shifts${query ? `?${query}` : ""}`, { method: "GET", token });
  },

  getShift: (id: string, token?: string) =>
    request<ShiftView>(`/api/v1/shifts/${id}`, { method: "GET", token }),

  shiftRoster: (id: string, token?: string) =>
    request<RosterView>(`/api/v1/shifts/${id}/roster`, { method: "GET", token }),

  createShift: (input: ShiftInput, token?: string) =>
    request<{ id: string }>("/api/v1/shifts", { method: "POST", body: JSON.stringify(input), token }),

  updateShift: (id: string, input: ShiftInput, token?: string) =>
    request<void>(`/api/v1/shifts/${id}`, { method: "PUT", body: JSON.stringify(input), token }),

  cancelShift: (id: string, reason: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  broadcastShift: (
    id: string,
    input: { message: string; includeWaitlist: boolean },
    token?: string
  ) =>
    request<{ broadcastId: string; recipients: number; queued: number }>(
      `/api/v1/shifts/${id}/broadcast`,
      { method: "POST", body: JSON.stringify(input), token }
    ),

  // ---- Shifts, volunteer side (E6-S3/S4/S5). -------------------------------
  availableShifts: (filters: { from?: string; to?: string } = {}, token?: string) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    const query = params.toString();
    return request<AvailableShiftView[]>(`/api/v1/available-shifts${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  signUpShift: (id: string, token?: string) =>
    request<{ signupId: string; overlapWarning: boolean }>(`/api/v1/shifts/${id}/signup`, {
      method: "POST",
      token,
    }),

  releaseShift: (id: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/release`, { method: "POST", token }),

  joinWaitlist: (id: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/waitlist`, { method: "POST", token }),

  leaveWaitlist: (id: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/waitlist`, { method: "DELETE", token }),

  myShifts: (token?: string) =>
    request<MyShiftView[]>("/api/v1/my-shifts", { method: "GET", token }),

  myWaitlist: (token?: string) =>
    request<MyWaitlistView[]>("/api/v1/my-waitlist", { method: "GET", token }),

  // ---- Tenant settings (E6-S7), behind MANAGE_TEMPLE_SETTINGS. -------------
  getSettings: (token?: string) =>
    request<{ volunteerBroadcastDailyLimit: number }>("/api/v1/settings", { method: "GET", token }),

  setBroadcastLimit: (limit: number, token?: string) =>
    request<void>("/api/v1/settings/volunteer-broadcast-limit", {
      method: "PUT",
      body: JSON.stringify({ limit }),
      token,
    }),

  // ---- What the giving screen needs before anybody gives (E7-S1/S6). ----
  //
  // These two were unauthenticated and took the temple from a slug in the address, because there
  // was a public donation page. There is not, as of 2026-08-29 — giving requires an account — so
  // the temple comes from the token like everything else and the slug is gone from both.

  /** The temple's name, its 80G flag, and the plates-and-cost figures the page is built around. */
  givingPage: (token?: string) =>
    request<DonationPageInfo>("/api/v1/donations/page", { method: "GET", token }),

  /** The equipment a temple is hoping for, as somebody about to give towards it sees it. */
  givingWishlist: (token?: string) =>
    request<WishlistItemView[]>("/api/v1/donations/wishlist", { method: "GET", token }),

  // ---- Donations ledger (E7-S7), behind VIEW_DONATIONS. ----
  donationLedger: (
    filters: { from?: string; to?: string; type?: string; status?: string } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.type) params.set("type", filters.type);
    if (filters.status) params.set("status", filters.status);
    const query = params.toString();
    return request<LedgerRow[]>(`/api/v1/donations/ledger${query ? `?${query}` : ""}`, { method: "GET", token });
  },


  /**
   * The tiles for one period, each with what it came to by the same point a year earlier, plus the
   * resolved window the ledger list and the CSV export then follow.
   */
  donationPeriodSummary: (
    period: LedgerPeriodKind,
    financialYear: number | null | undefined,
    token?: string
  ) => {
    const params = new URLSearchParams({ period });
    if (financialYear != null) params.set("financialYear", String(financialYear));
    return request<PeriodSummary>(`/api/v1/donations/ledger/period-summary?${params.toString()}`, {
      method: "GET",
      token,
    });
  },

  /**
   * The ledger as a CSV, fetched rather than linked to.
   *
   * <p>It used to be a plain anchor at the export URL, which cannot work: a link is a navigation and
   * a navigation carries no Authorization header, so clicking Export CSV put an HTTP 401 error page
   * in front of the accountant. The file has to be fetched with the token and handed to the browser
   * as a blob, exactly as the temple export already does.
   */
  exportLedger: async (
    filters: { from?: string; to?: string; type?: string; status?: string } = {},
    token?: string
  ): Promise<{ blob: Blob; filename: string }> => {
    const params = new URLSearchParams();
    if (filters.from) params.set("from", filters.from);
    if (filters.to) params.set("to", filters.to);
    if (filters.type) params.set("type", filters.type);
    if (filters.status) params.set("status", filters.status);
    const query = params.toString();
    const response = await fetch(
      `${BASE_URL}/api/v1/donations/ledger/export${query ? `?${query}` : ""}`,
      { method: "GET", headers: token ? { Authorization: `Bearer ${token}` } : {} }
    );
    if (!response.ok) {
      throw await errorFromBinaryResponse(
        response,
        "We couldn't export the donations.",
        "Try again in a moment."
      );
    }
    return {
      blob: await response.blob(),
      filename: exportFilename(response, "donations", "donations.csv"),
    };
  },

  // ---- Wish-list management (E7-S5), behind MANAGE_WISHLIST. ----
  listWishlist: (includeArchived = false, token?: string) =>
    request<WishlistItemView[]>(`/api/v1/wishlist${includeArchived ? "?includeArchived=true" : ""}`,
      { method: "GET", token }),

  createWishlistItem: (input: WishlistItemInput, token?: string) =>
    request<{ id: string }>("/api/v1/wishlist", { method: "POST", body: JSON.stringify(input), token }),

  updateWishlistItem: (id: string, input: WishlistItemInput, token?: string) =>
    request<void>(`/api/v1/wishlist/${id}`, { method: "PUT", body: JSON.stringify(input), token }),

  archiveWishlistItem: (id: string, token?: string) =>
    request<void>(`/api/v1/wishlist/${id}`, { method: "DELETE", token }),

  reorderWishlist: (itemIds: string[], token?: string) =>
    request<void>("/api/v1/wishlist/reorder", { method: "POST", body: JSON.stringify({ itemIds }), token }),

  // ---- Payables & invoice payments (E7-S8), behind MANAGE_VENDOR_PAYMENTS. ----
  payables: (token?: string) =>
    request<PayableView[]>("/api/v1/payables", { method: "GET", token }),

  /**
   * What has been paid against one invoice. Behind the stricter MANAGE_VENDOR_PAYMENTS, not the
   * MANAGE_PURCHASE_ORDERS that opens the invoice itself — so a screen that shows both must be
   * prepared for this one alone to be refused.
   */
  listInvoicePayments: (invoiceId: string, token?: string) =>
    request<InvoicePaymentView[]>(`/api/v1/vendor-invoices/${invoiceId}/payments`, {
      method: "GET",
      token,
    }),

  recordInvoicePayment: (
    invoiceId: string,
    input: { paidOn: string; amount: number; method: string; reference?: string; note?: string },
    token?: string
  ) =>
    request<{ id: string }>(`/api/v1/vendor-invoices/${invoiceId}/payments`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // ---- A temple's own settings (E7): how it collects donations. ----
  paymentSettings: (token?: string) =>
    request<PaymentSettingsView>("/api/v1/settings/payments", { method: "GET", token }),

  paymentProviders: (token?: string) =>
    request<PaymentProviderOption[]>("/api/v1/settings/payments/providers", { method: "GET", token }),

  /**
   * The events a temple must subscribe to, from the server rather than written into the screen —
   * the only correct answer is the set the running application acts on, and a copy typed here drifts.
   */
  // ---- WhatsApp (E1, E5), behind MANAGE_TEMPLE_SETTINGS. ----
  whatsappSettings: (token?: string) =>
    request<WhatsAppSettingsView>("/api/v1/settings/whatsapp", { method: "GET", token }),

  saveWhatsAppSettings: (input: SaveWhatsAppSettingsInput, token?: string) =>
    request<WhatsAppSettingsView>("/api/v1/settings/whatsapp", {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  testWhatsAppSettings: (token?: string) =>
    request<WhatsAppSettingsView>("/api/v1/settings/whatsapp/test", { method: "POST", token }),

  /**
   * The temple's own address, used as Reply-To. Sending is always from the platform's address,
   * whose domain carries the records that keep mail out of a spam folder.
   */
  templeContactEmail: (token?: string) =>
    request<{ contactEmail: string | null }>("/api/v1/settings/whatsapp/contact-email", {
      method: "GET",
      token,
    }),

  saveTempleContactEmail: (contactEmail: string, token?: string) =>
    request<{ contactEmail: string | null }>("/api/v1/settings/whatsapp/contact-email", {
      method: "PUT",
      body: JSON.stringify({ contactEmail }),
      token,
    }),

  /** The verify token to paste into Meta's callback setup. Every read is audited. */
  revealWhatsAppVerifyToken: (token?: string) =>
    request<{ verifyToken: string }>("/api/v1/settings/whatsapp/verify-token", {
      method: "POST",
      token,
    }),

  paymentEvents: (token?: string) =>
    request<WebhookSubscriptionGroup[]>("/api/v1/settings/payments/events", { method: "GET", token }),

  /** Saves the gateway. The secret may be omitted to keep the stored one — it is never sent back. */
  savePaymentSettings: (input: SavePaymentSettingsInput, token?: string) =>
    request<PaymentSettingsView>("/api/v1/settings/payments", {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  /** Proves the stored credentials still reach the provider. Says nothing about webhooks arriving. */
  testPaymentSettings: (token?: string) =>
    request<PaymentSettingsView>("/api/v1/settings/payments/test", { method: "POST", token }),

  /** The webhook secret, to paste into the provider's dashboard. Every reveal is audited. */
  revealWebhookSecret: (token?: string) =>
    request<{ webhookSecret: string }>("/api/v1/settings/payments/webhook-secret", {
      method: "POST",
      token,
    }),

  // ---- Giving from inside the app (E7-S2/S6), where the donor is the account. ----
  /**
   * A one-time gift as the signed-in devotee. No name or email is sent — the server reads the donor
   * from the token. Only an 80G receipt needs more, because address and PAN are not ours to know.
   */
  giveOnce: (amountInr: number, eightyG?: EightyGInput, token?: string) =>
    request<DonationCheckout>("/api/v1/donations/one-time", {
      method: "POST",
      body: JSON.stringify({ amountInr, ...(eightyG ?? { wants80g: false }) }),
      token,
    }),

  /** The same gift, put towards a piece of equipment the kitchen wants. */
  giveTowardsItem: (itemId: string, amountInr: number, eightyG?: EightyGInput, token?: string) =>
    request<DonationCheckout>(`/api/v1/donations/wishlist/${itemId}`, {
      method: "POST",
      body: JSON.stringify({ amountInr, ...(eightyG ?? { wants80g: false }) }),
      token,
    }),

  // ---- Recurring donation self-service (E7-S3), authenticated donor. ----
  /** Sets up monthly giving for the signed-in devotee — a mandate, never a one-time charge. */
  startRecurringPlan: (amountInr: number, eightyG?: EightyGInput, token?: string) =>
    request<RecurringPlanView>("/api/v1/donations/recurring", {
      method: "POST",
      body: JSON.stringify({
        frequency: "MONTHLY",
        amountInr,
        consent: true,
        ...(eightyG ?? { wants80g: false }),
      }),
      token,
    }),

  myRecurringPlans: (token?: string) =>
    request<RecurringPlanView[]>("/api/v1/donations/recurring", { method: "GET", token }),

  cancelRecurringPlan: (id: string, token?: string) =>
    request<void>(`/api/v1/donations/recurring/${id}/cancel`, { method: "POST", token }),

  // ---- The platform notice board (E9-S1), the one thing here that crosses temples. ----
  /** Every notice ever raised, withdrawn ones included — the permanent board. */
  listNotices: (token?: string) =>
    request<PlatformNotice[]>("/api/v1/notices", { method: "GET", token }),

  /**
   * What belongs at the top of Today: notices this person has not cleared, inside their 30-day
   * window — plus any they did clear that has since been withdrawn, so the retraction reaches the
   * people most likely to have acted on the original.
   */
  noticeFeed: (token?: string) =>
    request<PlatformNotice[]>("/api/v1/notices/feed", { method: "GET", token }),

  /** Posts to every temple on the platform. No review stands between this and all of them. */
  raiseNotice: (input: RaiseNoticeInput, token?: string) =>
    request<{ id: string }>("/api/v1/notices", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** Takes one down, with a reason. The raising temple's own, or — for an operator — anyone's. */
  withdrawNotice: (id: string, reason: string, token?: string) =>
    request<void>(`/api/v1/notices/${id}/withdraw`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  /** Clears it from this person's Today screen, and never from a colleague's. */
  dismissNotice: (id: string, token?: string) =>
    request<void>(`/api/v1/notices/${id}/dismiss`, { method: "POST", token }),
};

/**
 * How loudly a notice asks to be read. Three, and only `URGENT` is loud on screen — a board where
 * everything shouts is a board nobody reads.
 */
export type NoticeSeverity = "INFORMATION" | "IMPORTANT" | "URGENT";

/**
 * One notice on the platform board (E9-S1).
 *
 * <p>`body` is plain text and always was — never HTML, because this is the one payload one temple
 * writes and another temple's browser renders. Render it as text; do not reach for
 * dangerouslySetInnerHTML.
 */
export interface PlatformNotice {
  id: string;
  severity: NoticeSeverity;
  subject: string;
  body: string;
  /** The raising temple's name, or "the platform" for an operator's or an automated notice. */
  raisedBy: string;
  raisedAt: string;
  withdrawn: boolean;
  withdrawnBy: string | null;
  withdrawnAt: string | null;
  /** Why it was taken down. Never null on a withdrawn notice; the server insists on one. */
  withdrawnReason: string | null;
  /** Raised by the reader's own temple. */
  mine: boolean;
  /** Whether this reader may take it down — decided by the server, never inferred here. */
  canWithdraw: boolean;
}

export interface RaiseNoticeInput {
  severity: NoticeSeverity;
  subject: string;
  body: string;
}

// ---------------------------------------------------------------------------
// Kitchens, and asking the store for ingredients (E10)
// ---------------------------------------------------------------------------

/** One of the kitchens a temple runs. Flat under the temple; exactly one may be main. */
export interface Kitchen {
  id: string;
  name: string;
  description: string | null;
  location: string | null;
  /** The temple's principal kitchen. A label — see `usesMealPlanner` for the flag that acts. */
  isMain: boolean;
  /**
   * Whether this kitchen plans its meals here. True and its stock leaves as CONSUMPTION when a meal
   * is recorded, and it may not ask the store; false and the ingredient request is its only door.
   * One kitchen, one door — which is what stops the same rice leaving the books twice.
   */
  usesMealPlanner: boolean;
  inChargeUserId: string | null;
  inChargeName: string | null;
  contactPhone: string | null;
  status: "ACTIVE" | "ARCHIVED";
  createdAt: string;
}

export interface KitchenInput {
  name: string;
  description?: string | null;
  location?: string | null;
  isMain: boolean;
  usesMealPlanner: boolean;
  inChargeUserId?: string | null;
  contactPhone?: string | null;
}

/**
 * What turning the meal planner on for a kitchen would settle, asked before it is settled.
 *
 * <p>The edit screen asks this the moment the checkbox is ticked, because saving deletes somebody's
 * drafts and withdraws approvals another person granted.
 */
export interface MealPlannerImpact {
  draftsDeleted: number;
  requestsDenied: number;
}

export type IngredientRequestStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "APPROVED"
  | "DENIED"
  | "ISSUED";

export interface IngredientRequestSummary {
  id: string;
  /** Human-readable and per temple — IR-2026-0041. It exists so somebody can say it down a phone. */
  reference: string;
  kitchenId: string;
  kitchenName: string;
  neededOn: string;
  purpose: string | null;
  status: IngredientRequestStatus;
  requestedBy: string;
  requestedByName: string;
  submittedAt: string | null;
  decidedByName: string | null;
  decidedAt: string | null;
  issuedAt: string | null;
  lineCount: number;
  dishCount: number;
}

export interface IngredientRequestLine {
  id: string;
  lineNo: number;
  ingredientId: string;
  ingredientName: string;
  quantity: number;
  unit: string;
  /** What the store actually handed over. Null until the issue is recorded; may be zero. */
  issuedQuantity: number | null;
  issuedUnit: string | null;
  note: string | null;
}

/**
 * A dish the kitchen says it is cooking, and how much of it.
 *
 * <p>Text and numbers, pointing at no recipe. Required before a request can be reviewed: writing
 * down what you are cooking is what makes a requester work out what they actually need rather than
 * padding the list, and it is the other half of the comparison an auditor reads a work order for.
 */
export interface IngredientRequestDish {
  id: string;
  lineNo: number;
  dishName: string;
  quantity: number;
  unit: string;
}

export interface IngredientRequestEvent {
  id: string;
  eventType: string;
  detail: string | null;
  actorName: string | null;
  at: string;
}

export interface IngredientRequestDetail {
  request: IngredientRequestSummary;
  lines: IngredientRequestLine[];
  dishes: IngredientRequestDish[];
  events: IngredientRequestEvent[];
}

export interface IngredientRequestLineInput {
  ingredientId: string;
  quantity: number;
  unit: string;
  note?: string | null;
}

export interface IngredientRequestDishInput {
  dishName: string;
  quantity: number;
  unit: string;
}

export interface IngredientRequestInput {
  kitchenId: string;
  neededOn: string;
  purpose?: string | null;
  lines: IngredientRequestLineInput[];
  dishes: IngredientRequestDishInput[];
}

/** Only the lines that differ from what was approved need appear. */
export interface RecordIssueInput {
  lines: { lineId: string; quantity: number; unit: string }[];
  batchOverrides?: { ingredientId: string; batchId: string }[];
  note?: string | null;
}
