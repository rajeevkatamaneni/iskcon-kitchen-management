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

/**
 * The clock the temple keeps, and the clock every date on every screen is written in.
 *
 * <p>Rajeev, 2026-09-05: *"ALL Date and Time values for that Temple MUST be in that Time zone
 * irrespective of where the Temples dedicated tenant is being accessed from."* It was a module
 * constant of `Asia/Kolkata` — correct for every temple onboarded so far, wrong for the first one
 * that is not, and wrong in seventeen files at once because a constant cannot be right in one place
 * and wrong in another.
 *
 * <p>It lives here beside {@link activeTempleId} and works the same way: written once when the
 * session resolves, read by the formatters without being passed through every component that calls
 * one. The alternative — threading a zone through `todayIso()`, `moment()` and `templeDay()` — would
 * mean every caller could forget it, and a caller who can forget is a caller who will.
 *
 * <p>The fallback is deliberately the platform's own zone and never the reader's. A browser in
 * California asking what day it is must not answer with California's day for a kitchen in Bengaluru;
 * that is the bug this exists to end, and falling back to `undefined` would reintroduce it in every
 * moment before the session lands.
 */
const TEMPLE_ZONE_KEY = "kms.templeZone";

/** The zone a temple keeps until one says otherwise — every temple on the platform today. */
export const PLATFORM_TIME_ZONE = "Asia/Kolkata";

export function templeTimeZone(): string {
  if (typeof window === "undefined") return PLATFORM_TIME_ZONE;
  return window.localStorage.getItem(TEMPLE_ZONE_KEY) || PLATFORM_TIME_ZONE;
}

export function setTempleTimeZone(zone: string | null) {
  if (typeof window === "undefined") return;
  if (zone) window.localStorage.setItem(TEMPLE_ZONE_KEY, zone);
  else window.localStorage.removeItem(TEMPLE_ZONE_KEY);
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
  /**
   * Where the temple is, which `GET /api/v1/tenants/{id}` has returned since T-008.
   *
   * <p>Required, not optional: the endpoint always sends both, and the correction screen has to open
   * on the coordinates the temple already has. An optional pair would let a blank form post a
   * silently relocated temple.
   */
  latitude: number;
  longitude: number;
  /** When this temple was last exported, or null if it never has been (E1-S15). */
  last_export_at: string | null;
}

/**
 * Correcting what a temple is, after it was provisioned (T-008, docket A1+A2).
 *
 * <p>Deliberately not `Partial<ProvisionTenantInput>`: `slug` is `updatable=false` on the server and
 * the admin's own name, email and phone belong to a person rather than to the temple. What is here
 * is what a temple provisioned wrongly needs corrected, plus the one field that was never settable
 * afterwards at all.
 *
 * <p>Two of these are not ordinary columns. `timezone` is what `calendar_days` is precomputed from,
 * so changing it has to re-trigger that precompute or the temple keeps tithi and Ekadashi rows
 * computed against the wrong zone. `is80gApproved` is a legal status a receipt quotes. Both are why
 * this sits behind MANAGE_TENANTS — the operator's, not the temple's (D-13).
 */
export interface UpdateTenantInput {
  name: string;
  address: string;
  latitude: number;
  longitude: number;
  timezone: string;
  currency: string;
  is80gApproved: boolean;
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
  /**
   * The IANA zone this temple keeps its day in. Null for a platform operator, who belongs to none.
   *
   * <p>Every date and time on every screen is written in it, whatever the reader's own clock says —
   * see {@link templeTimeZone}, which is where it is put when the session resolves.
   */
  timezone: string | null;
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
  /**
   * Only preparations a fasting day allows: no line uses a grain or a bean (E4-S6). The judgement
   * is the server's, made against each ingredient's own `is_ekadashi_prohibited` flag — the
   * `fastingCompatible` field on a summary is the category's coarser answer and is not the same
   * question.
   */
  ekadashiCompatible?: boolean;
}

export interface IngredientView {
  id: string;
  name: string;
  category: string;
  unit: string;
  /**
   * Whether this may not be cooked on Ekadashi (T-045).
   *
   * <p><strong>Required, not optional, and the six fixtures it breaks are being fixed rather than
   * routed around.</strong> The server has always sent it; the client type never declared it, so no
   * screen could show it and nothing could set it — the flag reached the column only from the
   * provisioning seed. **That seed is gone too as of D-18 (2026-09-08), so this control is now the
   * only way the flag is ever set, and `/recipes` carries a warning saying so.**
   * `EkadashiPolicy.of()` and `RecipeService` decide from this column which recipes may be cooked
   * on a fasting day.
   *
   * <p>The Java field is a primitive `boolean`, so an absent JSON key deserialises to `false` — the
   * permissive answer, silently. Optional here would reproduce that silence in TypeScript, where
   * `undefined` is falsy in exactly the same way: a grain would read as permitted because nobody
   * said otherwise. Required makes every fixture state the fact out loud.
   */
  ekadashiProhibited: boolean;
  /**
   * Whether this is a consumable supply rather than food (T-023, D-1).
   *
   * <p>LPG, leaf plates, dishwashing liquid, hand soap and first-aid kits are bought from a vendor,
   * received, stored, used up and wanted back when they run low — the ingredient lifecycle exactly.
   * D-1 rejected a `supply_items` table with a stock ledger of its own: it duplicates the whole
   * inventory chain to express a difference that is one boolean. So supplies stay in this catalogue
   * and keep appearing in the inventory, ingredient-request, purchase-order, donation and
   * vendor-supplies pickers. **Only the recipe picker excludes them**, because a mop is not an
   * ingredient of anything.
   *
   * <p><strong>Required, not optional, for the same reason `ekadashiProhibited` is.</strong> The
   * Java field is a primitive `boolean`, so an absent JSON key deserialises to `false`. Optional
   * here would reproduce that silence in TypeScript, where `undefined` is falsy in exactly the same
   * way — and `false` is the *permissive* answer: a leaf plate with no flag reads as food and turns
   * up in the recipe picker, which is the one thing this column exists to prevent.
   */
  supply: boolean;
  /**
   * Whether a recipe import created this row rather than a person typing it (T-119).
   *
   * <p>The column has existed since V69 and nothing read it: importing a library recipe creates
   * every ingredient the temple does not already have — silently, and on purpose, because standing
   * a review step in front of every import is what stops the feature being used at all — so the
   * catalogue fills with rows nobody chose and nothing could tell them from the rest. The
   * ingredients screen now labels them, filters to them and counts them, and `/recipes` says the
   * same count on the screen an import is started from.
   *
   * <p><strong>Required, not optional, for the reason `supply` and `ekadashiProhibited` above are
   * required.</strong> The Java field is a primitive `boolean`, so an absent JSON key deserialises
   * to `false`; optional here would reproduce that silence in TypeScript, where `undefined` is
   * falsy the same way — and `false` is the answer that shows nothing, so a fixture that forgot it
   * would leave every label absent and every count at zero with nothing failing. Required makes
   * each fixture say which kind of row it is.
   *
   * <p>It goes false the moment somebody edits and saves the ingredient (`IngredientService.update`
   * clears it in the same statement), which is what keeps the filter a queue that empties.
   */
  libraryDerived: boolean;
  aliases: string[];
  createdAt: string;
}

export interface CreateIngredientInput {
  name: string;
  category: string;
  unit: string;
  /** See `IngredientView.ekadashiProhibited`. `CreateIngredientRequest:29` has always accepted it. */
  ekadashiProhibited: boolean;
  /** See `IngredientView.supply`. Required, and for the same reason. */
  supply: boolean;
  aliases: string[];
}

export interface UpdateIngredientInput {
  name: string;
  category: string;
  unit: string;
  /** See `IngredientView.supply`. A thing can stop being a supply, or start being one. */
  supply: boolean;
  /**
   * The Ekadashi-prohibited flag, or omitted to leave it exactly as it is (T-121).
   *
   * <p><strong>Optional here, where `supply` beside it is required — and the difference is the
   * point.</strong> Everywhere else in this file, an optional boolean is the trap: the Java field
   * is a primitive, an absent JSON key deserialises to `false`, and a client that forgets one
   * silently un-sets it. `UpdateIngredientRequest.ekadashiProhibited` is a boxed `Boolean` on
   * purpose, and null there means "leave the stored value alone" — so omitting it is a *statement*
   * rather than a silence, and it is the only true statement a Kitchen Manager can make. They may
   * rename a prohibited ingredient; they may not decide what is prohibited. Sending `false` on
   * their behalf would either un-prohibit the row or earn them a 403 for a field the screen never
   * showed them.
   *
   * <p>So: the ingredients screen sends this only for a Temple Admin, and sends the value the
   * checkbox is showing. Everyone else omits it.
   */
  ekadashiProhibited?: boolean;
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
  /** What saved meal plans intend to draw out of this item, in the item's own unit (T-086). */
  committed: number;
  /**
   * On hand minus committed. Derived server-side on every read and stored nowhere — never
   * recompute it from the other two here, and never send it back.
   *
   * <p>May be negative, which says the plan has promised more than the store holds.
   */
  available: number;
  reorderThreshold: number | null;
  /** Low. Judges `available`, not `onHand`, and is always true where `available` is negative. */
  belowThreshold: boolean;
  expiringSoon: boolean;
  soonestExpiry: string | null;
  notes: string | null;
}

/** One meal's claim on one ingredient's stock (T-086), for the item detail screen's list. */
export interface CommittedMeal {
  mealPlanId: string;
  planDate: string;
  mealKind: string;
  eventName: string | null;
  recipeName: string;
  quantity: number;
  unit: string;
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
  /** The meals that claimed this item's stock, soonest first. Empty where nothing has (T-086). */
  committed: CommittedMeal[];
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
  equipment: { name: string; notes?: string | null }[];
}

// --- Equipment (E3-S4, servicing E3-S10) ----------------------------------

/** The state it is in. `SCRAPPED` is terminal and drops out of the default list. */
export type EquipmentCondition = "GOOD" | "NEEDS_REPAIR" | "IN_REPAIR" | "SCRAPPED";

/** How it came to the temple. Null where nobody recorded which. */
export type EquipmentSource = "PURCHASED" | "DONATED";

/**
 * The unit an interval was said in. The server stores the interval as a count of days and keeps
 * this so the form can show "every 6 months" back rather than "every 180 days".
 */
export type ServiceIntervalUnit = "DAYS" | "WEEKS" | "MONTHS" | "YEARS";

/**
 * What the next service date was counted from (E3-S10 D4). `PURCHASED` means nothing has ever been
 * serviced, and the screen says so — a derived date must never read as a service that happened.
 */
export type NextServiceBasis = "SERVICED" | "PURCHASED" | "NONE";

/**
 * Where a machine stands against its next service. Derived on every read, stored nowhere.
 *
 * <p>`NOT_SCHEDULED` and `NOT_SERVICED` are the two halves of one state that used to be one value
 * (T-120): nobody has decided how often this needs looking at, and somebody has decided it never
 * will. Both are invisible to every warning count, so neither nags — but one of them is a job
 * somebody still has to do, and until V122 a ladder and an unscheduled boiler were the same row.
 */
export type EquipmentServiceStatus =
  | "OK"
  | "DUE_SOON"
  | "OVERDUE"
  | "NOT_SCHEDULED"
  | "NOT_SERVICED";

export interface EquipmentView {
  id: string;
  name: string;
  storageLocation: string | null;
  condition: EquipmentCondition;
  acquisitionDate: string | null;
  source: EquipmentSource | null;
  notes: string | null;
  createdAt: string;

  serialNumber: string | null;
  purchaseCostInr: number | null;
  warrantyExpiry: string | null;

  /**
   * True for a thing that will never need servicing — a ladder, a trestle table (T-120). Never both
   * this and an interval: V122's CHECK constraint makes that pairing unrepresentable in the row.
   */
  neverNeedsServicing: boolean;
  /**
   * The interval in days, which is what the arithmetic runs on. Null where none is set — which
   * since V122 means only that nobody has decided yet, and no longer doubles as "never".
   */
  serviceIntervalDays: number | null;
  serviceIntervalUnit: ServiceIntervalUnit | null;
  /** The count in that unit — the six of "every six months". Null with no interval. */
  serviceIntervalCount: number | null;
  /** Who services it and how to reach them, as typed. Plain text — there is no list behind it. */
  serviceCompany: string | null;
  serviceCompanyPhone: string | null;

  /** The newest recorded service, or null where there has never been one. */
  lastServicedOn: string | null;
  nextServiceOn: string | null;
  nextServiceBasis: NextServiceBasis;
  serviceStatus: EquipmentServiceStatus;
}

/** One entry in the condition trail: from what, to what, why, and by whom. */
export interface EquipmentStateChange {
  id: string;
  fromCondition: EquipmentCondition | null;
  toCondition: EquipmentCondition;
  reason: string | null;
  actorUserId: string;
  actorName: string | null;
  createdAt: string;
}

/**
 * One visit. `servicedOn` is the day the work was done and `createdAt` the day somebody wrote it
 * down — a service done on Tuesday and recorded on Friday is a normal thing in a temple.
 */
export interface EquipmentServiceRecord {
  id: string;
  servicedOn: string;
  /** Who came, as typed on the day. On the row, so it stays true when the machine's company moves. */
  serviceCompany: string | null;
  workDone: string | null;
  costInr: number | null;
  actorUserId: string;
  actorName: string | null;
  createdAt: string;
}

/**
 * A machine with its two histories, each newest first — and deliberately two rather than one. A
 * service is a different event from a change of condition, and a grinder can be serviced for five
 * years without its condition ever moving off good.
 */
export interface EquipmentDetail {
  equipment: EquipmentView;
  history: EquipmentStateChange[];
  services: EquipmentServiceRecord[];
}

export interface CreateEquipmentInput {
  name: string;
  storageLocation?: string | null;
  condition?: EquipmentCondition | null;
  acquisitionDate?: string | null;
  source?: EquipmentSource | null;
  notes?: string | null;
  serialNumber?: string | null;
  purchaseCostInr?: number | null;
  warrantyExpiry?: string | null;
}

/**
 * Correcting an item's descriptive facts — a mistyped serial number, a warranty date nobody had to
 * hand on the day it was registered.
 *
 * <p>Condition is deliberately absent, and so is the service interval, mirroring
 * `UpdateEquipmentRequest` exactly: condition moves only through a recorded state change with a
 * reason, and the interval is a commitment of the temple's money that travels with the service
 * company through {@link ServiceScheduleInput}.
 */
export interface UpdateEquipmentInput {
  name: string;
  storageLocation?: string | null;
  acquisitionDate?: string | null;
  source?: EquipmentSource | null;
  notes?: string | null;
  serialNumber?: string | null;
  purchaseCostInr?: number | null;
  warrantyExpiry?: string | null;
}

/**
 * Setting or clearing how often a machine must be serviced, and who does it (E3-S10 D3).
 *
 * <p>Its own request and its own endpoint because the permission differs: registering equipment is
 * kitchen staff's, and committing the temple to a service contract is the administrator's. Both
 * halves of the interval go together — a null count with a null unit clears the schedule.
 */
export interface ServiceScheduleInput {
  intervalCount: number | null;
  intervalUnit: ServiceIntervalUnit | null;
  /**
   * The tick box for a thing that will never need servicing (T-120). Required and not optional, so
   * a caller cannot omit it and silently un-flag a ladder: this endpoint replaces the whole
   * schedule. Sending it true alongside a count is a contradiction and is refused with KMS-400001.
   */
  neverNeedsServicing: boolean;
  /** Plain text, and blank clears it. The managed list this replaced was reversed on 2026-09-04. */
  serviceCompany: string | null;
  serviceCompanyPhone: string | null;
}

/** Recording a service that has happened. Only the date is insisted on (E3-S10 D2). */
export interface RecordServiceInput {
  servicedOn: string;
  serviceCompany?: string | null;
  workDone?: string | null;
  costInr?: number | null;
}

export interface EquipmentFilters {
  /** Scrapped machines are out of the list until somebody asks for them. */
  includeScrapped?: boolean;
  location?: string;
  serviceStatus?: EquipmentServiceStatus;
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

/**
 * Adding an occasion to the temple's own catalogue (E4-S2), behind MANAGE_TEMPLE_SETTINGS.
 *
 * <p>A COMPUTED occasion is found in the Vaishnava calendar by its `matchText`; a MANUAL one falls
 * on a fixed `fixedMonth`/`fixedDay` every year. The server enforces the right combination for the
 * type, so neither pair is required here.
 */
export interface CreateOccasionInput {
  name: string;
  type: "COMPUTED" | "MANUAL";
  matchText?: string | null;
  fixedMonth?: number | null;
  fixedDay?: number | null;
  defaultServings?: number | null;
  notes?: string | null;
}

/**
 * Editing one. Separate from {@link CreateOccasionInput} for one reason, which is the backend's:
 * `UpdateOccasionRequest` has no `type` field, because a computed occasion and a fixed-date one are
 * different things and the server asks you to recreate rather than convert.
 */
export interface UpdateOccasionInput {
  name: string;
  matchText?: string | null;
  fixedMonth?: number | null;
  fixedDay?: number | null;
  defaultServings?: number | null;
  notes?: string | null;
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
 * <p>`defaultReadyTime` is null on purpose for the occasional kinds — a deity offering or an event
 * has no usual hour, so the planner is always asked rather than given a guess.
 */
export interface MealKindView {
  id: string;
  name: string;
  sortOrder: number;
  /** "HH:mm:ss", or null when this kind must always be given a time. */
  defaultReadyTime: string | null;
  /**
   * Meals of this kind are events (E4-S15): an occasion with its own name, its own dishes and its
   * own quantities. It reveals the event's name and *is this going outside?*, and asks nothing
   * further until the answer is yes.
   *
   * <p>One flag where there were three. `needsClient`, `needsVenue` and `needsPurpose` each
   * described one corner of the same shape, and `needsClient` existed only to derive catering.
   * Setting `needsClient` on the Event kind was rejected rather than kept: an in-house Bhajan
   * Prasadam has no client, and a form asking for one would be asking a question with no answer.
   */
  isEvent: boolean;
  /**
   * The plan must name which festival it is for (item 26) — the flag that makes a kind a feast.
   * A feast is a kind of meal and not a kind of day, because on Janmashtami the temple serves an
   * ordinary breakfast and then a feast: one day, two meals, only one of them the big one.
   */
  needsOccasion: boolean;
}

/**
 * How food that is leaving the temple gets to the people eating it (E4-S15 D6).
 *
 * <p>It decides what else the planner is asked. Somebody collecting their own food does not need us
 * to know where they are taking it, so a pickup stops at a contact; a delivery is ours to get there,
 * so it asks the address and when the guests sit down — which is what the travel estimate works
 * backwards from.
 */
export type Handover = "PICKUP" | "DELIVERY";

/**
 * What kind of day a meal was cooked on. Derived from the date and the calendar, never chosen.
 *
 * <p>`CATERING` was here and is gone (E4-S15). It was never a kind of day: it was a fact about the
 * meal. A temple that caters now plans an event that is going outside, and the day it falls on is
 * still an ordinary weekday, a weekend or a festival.
 */
export type DayType = "REGULAR" | "WEEKEND" | "FESTIVAL";
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
  /**
   * What this event is called (E4-S15) — "Children's Bhagavad-gita Reading". It is what the day
   * shows for the meal, so the Saturday reading appears under its own name rather than as *Event*
   * with no further identity. Null on Breakfast, Lunch, Dinner and everything else that is not one.
   */
  eventName: string | null;
  /** This food leaves the temple. What *Upcoming outside commitments* is keyed off. */
  isOutside: boolean;
  /**
   * Pickup or delivery, on an event going outside. Null on an in-house one — and null on the
   * outside plans V88 carried across, which predate the question being asked.
   */
  handover: Handover | null;
  contactName: string | null;
  contactPhone: string | null;
  deliveryAddress: string | null;
  /**
   * Where exactly, once the driver is there — "Clubhouse", "Block C, second gate" (V93).
   *
   * <p>Deliberately not part of the address and never geocoded: a sub-premise is the part a map
   * service is least likely to know and most likely to fail the whole lookup over. Being at the
   * right gate is what matters, and the last fifty metres is a phone call.
   */
  deliverySubLocation: string | null;
  /** Google's stable id for the picked address. Null where the address was typed, not chosen. */
  deliveryPlaceId: string | null;
  /**
   * Where the food is actually going, as the server stored it (T-044).
   *
   * <p>**The absence of this pair was a live defect, not an omission.** Because the view never
   * returned the coordinates, `MealComposer` had nothing to reopen an edit on and rebuilt the picked
   * place as `{ placeId, latitude: 0, longitude: 0 }`. The server's `isPlaced()` is
   * `latitude != null && longitude != null` — it never consults `placeId`, and `0` is not null — so
   * every edit of a placed delivery event re-pinned it to 0°N 0°E and the travel estimate became the
   * drive to the Gulf of Guinea.
   *
   * <p>Null is meaningful and is not the same as zero: it means the address was typed rather than
   * picked, and `api.ts`'s own reader falls back to `deliveryPlaceId` when it sees it. That is why
   * these are required-and-nullable rather than optional — `undefined` would collapse back into the
   * ambiguity the defect lived in.
   */
  deliveryLatitude: number | null;
  deliveryLongitude: number | null;
  /**
   * "HH:mm:ss" — when the guests sit down to eat, on a delivery. Not the ready-by: the travel
   * estimate (E4-S16) works backwards from this to say when to leave the temple.
   */
  guestsEatAt: string | null;
  /**
   * How long the temple allows for this drive, in minutes (V93).
   *
   * <p>Prefilled from Google once there is an address and a serving time, and editable by anybody
   * who knows the road better than a traffic model does. This is the figure the job card prints —
   * the temple's own, never a live one.
   */
  travelMinutes: number | null;
  /**
   * `ESTIMATED` (Google's, untouched — refreshed when the card is printed) or `MANUAL` (a person
   * set it, and printing leaves it alone). Null where there is no figure.
   */
  travelMinutesSource: string | null;
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
  /** The mirror of `kitchenNotes` for the people handing food out — the serving sheet carries it. */
  serverNotes: string | null;
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
  /**
   * What this dish was FIRST recorded at, before a correction replaced it (T-007). Null on every
   * dish of a meal nobody has corrected.
   *
   * <p>It exists because correcting overwrites `actualServings` in place — the original recording is
   * never rewritten as a *record*, but the figure a reader sees is the current one, so without this
   * the screen could not say "640, corrected from 400" without reading it back out of the stock
   * ledger. Required-and-nullable rather than optional, so a caller building one of these has to
   * say which case it is in.
   */
  originalActualServings: number | null;
  /** The consumed figure this dish was first recorded at. Null where nothing was corrected. */
  originalConsumedQuantity: number | null;
  cookedAt: string | null;
  ekadashiAcknowledged: boolean;
  createdAt: string;
}

/**
 * One meal — a date and a kind — assembled from the dish rows that share them (B5).
 *
 * <p>There is no meal-line table: one meal plan row is one dish. This is the grouping the whole
 * product means whenever it says "the meal": one job card per meal kind, recording per meal rather
 * than per dish, servings per meal kind.
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
  /** What this event is called (E4-S15), where the meal is one. Null for everything else. */
  eventName: string | null;
  contactName: string | null;
  contactPhone: string | null;
  deliveryAddress: string | null;
  purpose: string | null;
  kitchenNotes: string | null;
  /** The mirror of `kitchenNotes` for the people handing food out — the serving sheet carries it. */
  serverNotes: string | null;

  cardNumber: string | null;
  cardIssuedAt: string | null;

  recorded: boolean;
  recordedAt: string | null;
  recordedByName: string | null;
  recordingNote: string | null;

  /**
   * Whether a correction has been recorded against this meal (T-007), and by whom.
   *
   * <p>A correction is a compensating entry, not a reopening: the original recording stays exactly
   * where it was and stays readable, and these four fields are what let the screen say *"640 plates,
   * corrected from 400 by Anand on 8 September"* rather than silently showing a different number
   * than it showed yesterday. `corrected` can only go true once — a second correction is
   * `KMS-400137`.
   */
  corrected: boolean;
  correctedAt: string | null;
  correctedByName: string | null;
  correctionNote: string | null;

  dishes: MealPlanView[];
}

/**
 * A correction to what a meal actually served (T-007), behind `CORRECT_RECORDED_MEAL` — the Temple
 * Admin's alone (D-4), unlike the recording it corrects.
 *
 * <p>The shape mirrors `RecordMealInput`'s dishes deliberately: a correction restates the whole
 * meal, dish by dish, rather than sending a delta. A delta would need the client and the server to
 * agree about what the current figures are, and a meal is a *set* of stock movements — the one
 * thing this feature exists because nobody could previously enumerate.
 */
export interface CorrectMealInput {
  /**
   * Why the figures are being changed. Required and non-empty: a correction with no reason is
   * unreadable a month later, and this is the sentence the audit trail carries.
   */
  note: string;
  /**
   * Every dish of the meal, exactly as `RecordMealInput` takes them. A dish left out is refused
   * rather than assumed unchanged.
   *
   * <p>Both figures are required-and-nullable rather than optional, which is the difference between
   * this and `RecordMealInput.dishes` and is deliberate — on a correction, "I am not saying" and "I
   * am saying nothing was consumed" have to be distinguishable, and an omitted key cannot do it.
   */
  dishes: {
    mealPlanId: string;
    actualServings: number | null;
    consumedQuantity: number | null;
    notMade: boolean;
  }[];
}

/** What actually went out at one meal, typed in from the card that came back. */
export interface RecordMealInput {
  planDate: string;
  mealKind: string;
  /**
   * Which event this recording is for, and `null` for an everyday meal.
   *
   * <p><strong>Required and nullable on purpose, and the reason is a live defect (T-043).</strong>
   * The server has always resolved the meal with `require(planDate, mealKind, eventName)` —
   * "every event of every temple is called Event", so the date and the kind alone do not say which
   * preparation is being written down. This type omitted the field entirely, so the only caller
   * sent four fields, the server found nothing, and **no event meal could be recorded from any
   * screen** — silently, because ordinary Breakfast/Lunch/Dinner recording has no event name and
   * worked fine. TypeScript could not have caught it: the type agreed with the caller and both
   * disagreed with the server.
   *
   * <p>So it is not optional. Optional would let the same omission happen again and compile.
   * Required-and-nullable makes every caller say which case it is in, and an everyday meal says
   * `null` out loud.
   */
  eventName: string | null;
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
  /**
   * Machines past their service date, or null for somebody who does not book the engineer (E3-S11
   * D4). Null and zero are different statements and the screen draws neither: null means the count
   * is not this reader's, zero means nothing is late. Only overdue is counted — the amber ones stay
   * on the Equipment screen.
   */
  equipmentOverdue: number | null;
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

  /**
   * The event fields (E4-S15), honoured only by a kind flagged `isEvent` and dropped on the way in
   * by every other kind. They are asked for in a chain: an event has a name; an event going outside
   * also has a contact, name and phone both; a delivered one also has an address and the time the
   * guests eat. An in-house event stops at its name.
   */
  eventName?: string | null;
  isOutside?: boolean;
  handover?: Handover | null;
  contactName?: string | null;
  contactPhone?: string | null;
  deliveryAddress?: string | null;
  /** Where exactly, once the driver is there. Never geocoded — the van goes to the gate. */
  deliverySubLocation?: string | null;
  /** Google's id for a picked address; absent when it was typed. */
  deliveryPlaceId?: string | null;
  /**
   * The pin, sent back exactly as it came (T-044). See `MealPlanView` for what went wrong without it.
   *
   * <p>**Required-and-nullable in a record whose every other field is optional, deliberately.** The
   * defect was that a payload builder could omit these and still compile: `mealFacts()` in
   * `MealComposer` carries no return-type annotation and its result is *spread* into the request, and
   * spread properties are exempt from TypeScript's excess-property check. Optional here would leave
   * that hole open. Required forces the one construction site in the app to say what the pin is, and
   * `null` — meaning "typed, not picked" — is a thing it is allowed to say.
   */
  deliveryLatitude: number | null;
  deliveryLongitude: number | null;
  /** "HH:mm" — when the guests sit down, on a delivery. What the travel estimate works back from. */
  guestsEatAt?: string | null;
  /** How long to allow for the drive, in minutes. Prefilled from Google, editable. */
  travelMinutes?: number | null;
  /**
   * Whether a person set that figure themselves. It is what stops the job card refreshing it out
   * from under them on the sheet a driver is about to act on.
   */
  travelMinutesManual?: boolean;

  /** What the food is for, in the planner's own words (B6). No kind demands it; the card prints it. */
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
  /** What the people serving need to know. Printed on the job card’s serving sheet. */
  serverNotes?: string | null;
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
  /** Meals of this kind are events (E4-S15) — their own name, and the outside-event chain. */
  isEvent: boolean;
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

/**
 * Where today stands against the last day something could be ordered and still arrive (T-090).
 *
 * <p>Rajeev's rule: amber while there is still slack, red the day you hit the order-by date, and
 * past that it is not a warning any more but a fact. `TOO_LATE` is a different sentence rather than
 * a darker red — once the date has gone, telling somebody to order in time is useless, so the screen
 * says what is now true instead.
 */
export type OrderUrgency = "IN_TIME" | "ORDER_TODAY" | "TOO_LATE";

export interface MealSufficiency {
  mealPlanId: string;
  planDate: string;
  mealKind: string;
  readyBy: string;
  recipeName: string;
  status: "SUFFICIENT" | "SHORT" | "PLANNING";
  shortfalls: IngredientShortfall[];
  /**
   * The last day this meal's shortage could be ordered for — its own date minus the lead time.
   * Null unless `status` is `"SHORT"`: a covered meal has nothing to order and a meal outside the
   * buying window is making no claim about stock at all. Required-and-nullable rather than optional,
   * so a caller that forgets it is a type error rather than a silent `undefined`.
   */
  orderBy: string | null;
  /** Null exactly when `orderBy` is. */
  orderUrgency: OrderUrgency | null;
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

/**
 * One kind of meal over the reported period, and what a serving of it costs (E3-S9).
 *
 * <p>`mealKind` is whatever the temple calls it — meal kinds are its own rows, so there is no fixed
 * list here either. Kinds it did not cook in the period simply do not appear.
 */
export interface MealKindCost {
  mealKind: string;
  /** A meal is a date and a kind, so a lunch of three dishes counts once. */
  meals: number;
  /** The head count across the meals that recorded one. Never the sum of a meal's dishes. */
  servings: number;
  /** Meals nobody gave a head count. In `meals` and `estimatedTotal`, out of `costPerServing`. */
  mealsWithoutServings: number;
  estimatedTotal: number;
  /** Null where no meal of this kind has a head count — a figure divided by nothing is worse. */
  costPerServing: number | null;
  ingredientsPriced: number;
  ingredientsWithoutPrice: number;
  unpriced: UnpricedIngredient[];
}

/**
 * What each kind of meal costs over a period (E3-S9).
 *
 * <p>The same estimate the Today tile shows, kept split by kind instead of summed, because "what
 * does a public-prasadam plate cost against a feast plate" is a comparison and a daily total can
 * never answer it. The same caveat applies with the same force: `estimatedTotal` covers only the
 * priced ingredients, so `ingredientsWithoutPrice` belongs beside every figure taken from it.
 */
export interface CostByMealKind {
  from: string;
  to: string;
  meals: number;
  servings: number;
  mealsWithoutServings: number;
  estimatedTotal: number;
  ingredientsWithoutPrice: number;
  unpriced: UnpricedIngredient[];
  /** Dearest per serving first; a kind with no head count anywhere sits at the foot. */
  kinds: MealKindCost[];
}

/**
 * One kitchen, and what the temple store issued to it over the reported period (E10-S13).
 *
 * <p>`estimatedTotal` is a floor and never a total. It is what left the temple store against this
 * kitchen's requests; a kitchen that buys food itself spends money this application never sees. Say
 * "issued from the temple store", never "this kitchen's food cost".
 */
export interface KitchenIssueCost {
  kitchenId: string;
  kitchen: string;
  /** True where the kitchen plans its meals here now, so its newer food is counted as consumption. */
  usesMealPlanner: boolean;
  /** Requests the store filled for it. A request issued in two goes is still one request. */
  requests: number;
  /** Distinct ingredients that went over the counter to it. */
  ingredients: number;
  estimatedTotal: number;
  ingredientsPriced: number;
  ingredientsWithoutPrice: number;
  unpriced: UnpricedIngredient[];
}

/**
 * What the temple store issued to each kitchen over a period, costed (E10-S13).
 *
 * <p>An issue already records which kitchen the food went to, which makes it a cost attribution.
 * A kitchen that plans its meals here is costed through what it cooked; a kitchen that does not can
 * only be costed through what it was issued, which is why this report exists at all.
 */
export interface IssuedFromStore {
  from: string;
  to: string;
  requests: number;
  estimatedTotal: number;
  ingredientsWithoutPrice: number;
  unpriced: UnpricedIngredient[];
  /** Dearest first. A kitchen the store issued nothing to does not appear. */
  kitchens: KitchenIssueCost[];
}

// ---- Epic 5: Ordering & Vendors ------------------------------------------

export interface VendorView {
  id: string;
  name: string;
  contactPerson: string | null;
  /**
   * Null for a vendor nobody can message — a shop somebody walks into and pays at the counter.
   *
   * <p>Required-and-nullable rather than optional, deliberately: an optional property lets a spread
   * omit it silently and cannot be told apart from an absent key by a test. Every other nullable
   * field on this record is written the same way.
   */
  phone: string | null;
  email: string | null;
  address: string | null;
  gstin: string | null;
  preferredLanguage: string;
  notes: string | null;
  /**
   * When the temple's agreement with this vendor runs out, or null if there is no such date.
   *
   * <p>Recorded and warned about, never acted on. Nothing filters, sorts or schedules on it, and a
   * vendor whose contract ended last March stays active and stays selectable until a person decides
   * otherwise and says why.
   */
  contractEndDate: string | null;
  /** The server's judgement that the contract has run out, or runs out inside the warning window. */
  contractEndingSoon: boolean;
  active: boolean;
  whatsappReachable: boolean;
  createdAt: string;
}

/**
 * One entry in a vendor's active/inactive history (E5-S1): which way it went, why, and by whom.
 *
 * <p>Its own record rather than a line in the audit log, because reading the audit log needs
 * VIEW_AUDIT_LOG — which only a Temple Admin holds — and the person deciding whether to bring a
 * supplier back is often a Kitchen Manager. The reason is required going out and optional coming
 * back in, and no entry is ever edited: the table is append-only.
 */
export interface VendorStatusChange {
  id: string;
  fromActive: boolean | null;
  toActive: boolean;
  reason: string | null;
  actorUserId: string;
  actorName: string | null;
  createdAt: string;
}

export interface VendorSupplyView {
  ingredientId: string;
  ingredientName: string;
  lastPrice: number | null;
  /**
   * Days between asking this vendor for this ingredient and it arriving (T-090), or null where
   * nobody has recorded it. **Null is unknown, not same-day** — the screen prints an em dash, never
   * a nought, and the ordering screens fall back to the two-day assumption rather than planning as
   * though the goods are already in the van. Zero is a real and different answer: cash-and-carry.
   */
  leadTimeDays: number | null;
  preferred: boolean;
}

export interface VendorDetailView {
  vendor: VendorView;
  supplies: VendorSupplyView[];
  /** Most recent first, so the newest entry answers "why is this one inactive?". */
  statusHistory: VendorStatusChange[];
}

/**
 * Why a delivery line was refused, and how many times, over the reported period (E5-S9).
 *
 * <p>Lines, not quantities: two sacks of rice and forty litres of oil are one refusal each, and
 * adding 2 to 40 would give a number in no unit at all.
 */
export interface RejectionCount {
  /** DAMAGED, SPOILED, WRONG_ITEM or OTHER. */
  reason: string;
  lines: number;
}

/**
 * One supplier's record over the reported period (E5-S9).
 *
 * <p>Every percentage arrives with the counts it was made from, and both belong on screen. "50% on
 * time" says something different about a vendor with two orders and one with forty, and only the
 * denominator says which.
 */
export interface VendorPerformanceRow {
  vendorId: string;
  vendorName: string;
  /** False for a vendor the temple has dropped. They stay on the report, marked. */
  active: boolean;
  /** Orders placed in the period. Drafts and cancellations are excluded everywhere. */
  ordersPlaced: number;
  /**
   * Of those, the ones there is something to say about: their needed-by date has passed, or they
   * were abandoned. The denominator of `onTimePercent`.
   */
  ordersJudged: number;
  /**
   * Judged orders that scored a full hundred per cent — every item on them there in time.
   *
   * <p>Not the numerator of `onTimePercent`, deliberately (T-124). An order eight-tenths delivered
   * on the day moves the percentage and does not count here, and a reader should see both.
   */
  onTimeOrders: number;
  /**
   * Judged orders cancelled because the vendor never delivered them (T-124).
   *
   * <p>Each scores nothing. This is the count that says a zero came from a supplier who never
   * turned up rather than from one who turned up late.
   */
  abandonedOrders: number;
  /** Orders with no needed-by date: nothing to be late against, so outside both figures. */
  ordersWithoutNeededBy: number;
  /**
   * Orders **we** submitted after this vendor's agreed lead time (T-137, D-25).
   *
   * <p>Counted as placed and then set aside: we asked for something their notice period could not
   * deliver, so a delay on one of them is not theirs to answer for. Rajeev: "That is a FAVOR we are
   * asking."
   *
   * <p>It belongs on the screen beside the percentage, and that is part of the ruling rather than a
   * nicety — a figure whose exclusions are invisible cannot be checked. Same standard as
   * `abandonedOrders`.
   */
  ordersSentLate: number;
  /**
   * Orders closed part-delivered with the shortfall excused — *they fell short but made it right*
   * (T-142, D-26).
   *
   * <p>The supplier rang, apologised, offered a discount next time and said buy it elsewhere, and
   * the admin closing the order said so. Counted as placed and then set aside from **both** the
   * on-time figure and the fill rate: the black mark on a part-delivery is mostly the half-empty
   * lorry, so excusing only the lateness would waive almost nothing. That is deliberately unlike
   * `ordersSentLate`, which stays in the fill rate — ordering late excuses our timing, this excuses
   * their shortfall.
   *
   * <p>It belongs on the screen beside the percentages, for the reason the whole ruling turns on: a
   * number whose exclusions are invisible cannot be checked.
   */
  ordersExcused: number;
  /** Order lines that went into the on-time figure — the "of ten" in "eight of ten items". */
  itemsScored: number;
  /** Of those, the ones fully there in time — the "eight". */
  itemsOnTime: number;
  /**
   * The mean of the judged orders' scores, each of them the mean of its items' (T-124). Null where
   * nothing has been judged yet — a figure divided by nothing is worse than none.
   */
  onTimePercent: number | null;
  /** Order lines the fill rate could judge. Not the same population as `itemsScored`. */
  linesJudged: number;
  /** The share of an average ordered line that turned up and was kept. Capped at 100% per line. */
  fillRatePercent: number | null;
  rejectedLines: number;
  /** Commonest reason first. */
  rejections: RejectionCount[];
  /** Open right now, whenever placed — deliberately not filtered to the period. */
  openOrders: number;
  openCurrent: number;
  openDue1To30: number;
  openOverdue31Plus: number;
  /** False below five judged orders: shown with its figures, sorted below, and marked. */
  enoughToRank: boolean;
}

/**
 * How the temple's suppliers have performed (E5-S9).
 *
 * <p>Two clocks, deliberately. Everything counted over the period is selected by the date the order
 * was placed; the open-order and aging columns are present tense and unfiltered, because an order
 * left hanging since June is exactly what aging exists to surface.
 *
 * <p>On-time is scored per item (T-124): each item on an order contributes the fraction of it that
 * was there on or before the needed-by day, capped at one; an order is the mean of its items and a
 * vendor the mean of their orders. A cancellation marked "Vendor Never Delivered this Order" scores
 * nothing and is counted again as abandoned; a cancellation nobody marked is counted nowhere.
 */
export interface VendorPerformance {
  from: string;
  to: string;
  ordersPlaced: number;
  ordersJudged: number;
  onTimeOrders: number;
  abandonedOrders: number;
  ordersWithoutNeededBy: number;
  /** Orders we submitted after the vendor's lead time, excluded from on time — see the row. */
  ordersSentLate: number;
  /** Orders closed with their shortfall excused, out of both percentages — see the row. */
  ordersExcused: number;
  itemsScored: number;
  itemsOnTime: number;
  onTimePercent: number | null;
  linesJudged: number;
  fillRatePercent: number | null;
  rejectedLines: number;
  openOrders: number;
  openCurrent: number;
  openDue1To30: number;
  openOverdue31Plus: number;
  /** Worst on-time first; suppliers with too few judged orders to rank sit below that, by name. */
  vendors: VendorPerformanceRow[];
}

export interface VendorInput {
  name: string;
  contactPerson?: string | null;
  /** Omitted or null for a vendor with no number. Anything sent must still be a real E.164 number. */
  phone?: string | null;
  email?: string | null;
  address?: string | null;
  gstin?: string | null;
  preferredLanguage?: string | null;
  notes?: string | null;
  contractEndDate?: string | null;
}

export interface ShoppingListLineView {
  ingredientId: string;
  ingredientName: string;
  currentStock: number;
  unit: string;
  suggestedQty: number;
  /**
   * The delivery date written on the purchase order — when the temple wants the goods on the shelf.
   * Since T-130 that is the day of the earliest planned meal that demands it, with nothing
   * subtracted. Not an order-by date.
   */
  neededBy: string | null;
  /**
   * The last day this line can be ordered and still arrive (T-090). Null on a hand-added line, which
   * no meal demanded and which therefore has no such date.
   */
  orderBy: string | null;
  /**
   * The recorded lead time behind `orderBy`, or null where none was recorded and the two-day
   * assumption stood in — so the screen can say whether the date came from the vendor or from us.
   */
  leadTimeDays: number | null;
  /** Null exactly when `orderBy` is. */
  orderUrgency: OrderUrgency | null;
  suggestedVendorId: string | null;
  suggestedVendorName: string | null;
  shortfall: number;
  thresholdTopUp: number;
  poOutstanding: number;
  shortPurchaseOrders: string[];
  included: boolean;
  /** Whether a person has decided anything about this line: a quantity, an untick, or typing it in. */
  edited: boolean;
  /**
   * The day somebody unticked this line, and null while it is included. An untick persists, so a
   * line can come back months later still unticked — this is what lets the screen say so instead of
   * quietly leaving a shortfall off the list.
   */
  excludedSince: string | null;
}

export type PoStatus =
  | "DRAFT"
  | "SENT"
  | "PARTIALLY_RECEIVED"
  /**
   * A part-delivered order somebody ended, undelivered remainder and all (T-142, D-26).
   *
   * <p>Terminal, and not a cancellation: goods arrived against it and are owed for. It is what
   * releases the balance back to the shopping list, and it is the only status that can carry a
   * `closeOutcome`.
   */
  | "CLOSED"
  | "RECEIVED"
  | "CANCELLED";

/**
 * How closing a part-delivered order ended for the vendor (T-142, D-26).
 *
 * <p>**This is the whole of an admin's influence over a supplier's score, and it is a name rather
 * than a number.** Rajeev proposed a control that let the admin adjust the computed figure up or
 * down — "there is SO MUCH human interaction that no machine or app can capture" — and then ruled
 * against his own proposal: "Let us not let the admin adjust the score. Just show it to them." So
 * there is no field anywhere in this file that moves a percentage.
 *
 * - `VENDOR_LET_US_DOWN` — the one who went silent and never rang back. Scored exactly as computed:
 *   the quantity that never came is already in the percentage. The name is what lets a reader tell
 *   a 60% the temple blames from one it accepts.
 * - `SHORTFALL_EXCUSED` — the one who apologised, blamed the weather, offered a discount next time
 *   and said buy it elsewhere. The order leaves that vendor's on-time figure and fill rate
 *   entirely, and the count of such orders is on the scorecard beside both.
 * - `AS_COMPUTED` — neither. The figures stand as the receipts made them.
 *
 * Anything other than `AS_COMPUTED` requires a sentence.
 */
export type CloseOutcome = "VENDOR_LET_US_DOWN" | "SHORTFALL_EXCUSED" | "AS_COMPUTED";

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
  /**
   * Whether the cancellation was recorded against the vendor — the "Vendor Never Delivered this
   * Order" tick on the cancel form (T-124), in Rajeev's own words of 2026-09-09.
   *
   * <p>Declared beside `cancelReason` because the two are read together: the reason is what the
   * temple wrote down, and this is whether the temple is holding the vendor responsible for it.
   * Always `false` unless `status` is `"CANCELLED"` — the database refuses the pairing outright
   * (`purchase_orders_abandoned_is_a_cancellation`, V118), so a screen never has to defend against
   * a live order claiming a no-show.
   *
   * <p><strong>Required, never optional</strong>, like every other field here — an optional field
   * is exempt from the excess-property check when it is spread, so a fixture or a caller that drops
   * it type-checks and the value arrives as `undefined`, which reads as "not a no-show" and is
   * indistinguishable from the truth. See `PurchaseOrderLineView.ingredientId` for where this
   * convention was paid for.
   */
  vendorAbandoned: boolean;
  /**
   * Whether the nightly sweep cancelled this draft because its needed-by date had gone (T-137,
   * D-24a). Rajeev: "mark it as Auto Cancelled. Reason: Past need by date."
   *
   * <p>A draft holds its ingredients off the shopping list from the moment it is created, so one
   * nobody ever sends holds them hostage; cancelling it hands them back. The screens say "Auto
   * Cancelled" where they would otherwise say Cancelled, because nobody in the temple did it and
   * the person who finds the order will otherwise go looking for who did.
   */
  autoCancelled?: boolean;
  /**
   * The lead time governing this order, in days — the vendor's own number, agreed at onboarding
   * (T-137, D-25). Null or absent where nobody has said, which is silence and never zero.
   *
   * <p>**Two facts wear this name, and which one it is depends on `sentAt`.** On a draft it is read
   * live from the vendor's supply rows, so editing their profile moves it — nothing has been asked
   * of anybody yet. Once the order is sent it is the figure stamped on the order when it went out,
   * and a later edit cannot move it: "Any SLA Adjustments made to a vendor's profile will take
   * effect for the Orders after the change. No retroactive change here."
   */
  leadTimeDays?: number | null;
  /**
   * The last day this order could be placed and still arrive: `neededBy` minus `leadTimeDays`.
   * Null or absent exactly when either of those is.
   *
   * <p>Worked out by the server, in the one place that subtraction exists. A screen must not
   * recompute it — the whole of T-137 is that the planner badge, this screen, the Today dashboard
   * and the Mark sent gate cannot be allowed to give different answers about one order.
   */
  orderBy?: string | null;
  /**
   * Where today stands against `orderBy`, for an order still waiting to go out.
   *
   * <p>**Null once the order has been sent**, and that is not a missing answer: the zone is advice
   * about when to press the button, and what the order went out under is `sentAfterLeadTime`.
   */
  orderUrgency?: OrderUrgency | null;
  /**
   * Whether this order went out after the last day it could have been placed (T-137, D-25).
   *
   * <p>Decided once, on the server, at the moment of sending — never recomputed. What follows is
   * that a delay on this delivery is not counted against the vendor: it is excluded from their
   * on-time figure, and cancelling it does not offer the "Vendor Never Delivered this Order" tick.
   */
  sentAfterLeadTime?: boolean;
  /**
   * When somebody ended this part-delivered order, releasing its remainder (T-142, D-26).
   *
   * <p>Deliberately not `cancelledAt`: a closed order is not a cancellation, and every screen that
   * reasons about cancellations must go on reading a clean null there. Null or absent on every
   * order nobody has closed.
   */
  closedAt?: string | null;
  /**
   * Which of the three endings the person closing named, or null while the order is still live.
   *
   * <p>A name and never a number — see `CloseOutcome`. The database refuses an outcome on a live
   * order and a closed order with none (`purchase_orders_closure_is_a_closed_order`, V126), so a
   * screen never has to defend against either.
   */
  closeOutcome?: CloseOutcome | null;
  /**
   * Why the order was closed the way it was, in the words of the person who closed it. Required by
   * the server whenever `closeOutcome` says something about the vendor.
   */
  closeNote?: string | null;
  sentAt: string | null;
  cancelledAt: string | null;
  createdAt: string;
}

/**
 * What one order scored on delivery — shown at closing, and editable nowhere (T-142, D-26).
 *
 * <p>The figure the vendor scorecard reports for this order, from the same arithmetic rather than a
 * second copy of it. It is on the order's payload so that the person closing a part-delivered order
 * decides against a fact: 300 kg of 500 inside the window is 60%, computed by T-124 with nothing
 * new, and that number is in front of them while they choose what the shortfall meant.
 */
export interface OrderDeliveryScore {
  /**
   * The mean of this order's items, each the fraction of it that arrived on or before the needed-by
   * day, as a whole percentage. Null where there is nothing to score — no needed-by date to be late
   * against, or no lines — because a figure divided by nothing is worse than no figure.
   */
  percent: number | null;
  /** The items behind it: the "of ten" in "eight of ten items". */
  itemsScored: number;
  /** Of those, the ones fully there in time — the "eight". */
  itemsOnTime: number;
}

export interface PurchaseOrderLineView {
  id: string;
  /**
   * The catalogue ingredient this line is for, or null when the line is described instead (T-024).
   *
   * <p>Exactly one of `ingredientId` and `description` is set; the database says so with a CHECK.
   * `ingredientId === null` is therefore the discriminator, and it is the one to key a list on —
   * `key={l.ingredientId}` collides the moment two described lines sit on one order.
   *
   * <p><strong>Required-and-nullable, never optional.</strong> Wave 4c's T-044 established the
   * difference the expensive way: an optional field is exempt from the excess-property check when
   * it is spread, so a caller that drops it type-checks and the value arrives as `undefined`. A
   * required field that may be null makes every construction site state the fact.
   */
  ingredientId: string | null;
  /** The catalogue name, or null on a described line. Null here is not "unnamed" — see `description`. */
  ingredientName: string | null;
  /** What is being bought when it is not in the catalogue ("Plastic stool"), or null. */
  description: string | null;
  quantity: number;
  unit: string;
  expectedPrice: number | null;
  /**
   * The day a described line's goods were recorded as having arrived, or null (T-066).
   *
   * <p>Always null on a catalogue line — those are accounted for by a goods receipt and by nothing
   * else, and the database says so with `po_lines_only_a_described_line_arrives`. On a described
   * line it is the only way the line can ever be accounted for, because the store room does not
   * track a plastic stool and the server refuses a receipt against one (KMS-400129).
   *
   * <p>Non-null is what takes the line off the "did these arrive?" form on the order screen, and it
   * is what the vendor scorecard judges on-time against when an order has no goods receipt at all.
   *
   * <p><strong>Required-and-nullable, never optional</strong>, like every other field on this
   * interface — see `ingredientId`.
   */
  arrivedOn: string | null;
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
  /**
   * Whether a WhatsApp message from this temple has ever gone out successfully (T-136).
   *
   * <p>Rajeev's ruling, 2026-09-10: the Send on WhatsApp button is shown "only after a message has
   * actually gone through it successfully", not merely configured — and where it does not apply it
   * is not there at all, not disabled and not greyed.
   *
   * <p>A fact about the temple, not about this order. It says nothing about whether THIS order was
   * ever sent; that is `order.sentAt`. It rides here because this is the payload the order screen
   * already reads, under `MANAGE_PURCHASE_ORDERS`. The screen must not learn it from
   * `api.whatsappSettings()`, which is behind `MANAGE_TEMPLE_SETTINGS` — a permission whoever
   * raises a purchase order need not hold, and the button would then vanish for a reason that has
   * nothing to do with WhatsApp.
   *
   * <p><strong>Optional, against this file's own convention, and the reason is specific.</strong>
   * Every other field here is required-and-nullable because an omitted optional field arrives as
   * `undefined` and reads as the benign value while being indistinguishable from the truth — see
   * `PurchaseOrderLineView.ingredientId`. That danger is inverted here: `undefined` reads as "no
   * WhatsApp message has ever gone out", which hides the button, which is exactly the ruling's own
   * default and the safe direction. It is optional only because two existing fixtures
   * (`__tests__/goods-return.test.tsx`, `__tests__/described-po-line.test.tsx`) construct this
   * interface and were outside T-135's path contract. Make it required the next time somebody may
   * open those two files.
   */
  whatsappEverSent?: boolean;
  /**
   * What this order scored on delivery (T-142, D-26). Optional for the same narrow reason
   * `whatsappEverSent` is: existing fixtures construct this interface, and an absent score reads as
   * "nothing to show", which is the safe direction — a screen that cannot see a figure prints no
   * figure rather than a wrong one.
   *
   * <p>**Shown, never edited.** There is no call in this file that changes it, and there must not
   * be: "Let us not let the admin adjust the score. Just show it to them."
   */
  deliveryScore?: OrderDeliveryScore | null;
}

export interface PoLineInput {
  /** See `PurchaseOrderLineView.ingredientId`. Exactly one of this and `description` may be sent. */
  ingredientId: string | null;
  /** See `PurchaseOrderLineView.description`. Exactly one of this and `ingredientId` may be sent. */
  description: string | null;
  quantity: number;
  unit: string;
  expectedPrice?: number | null;
}

/**
 * "These arrived" — the acknowledgement that closes an order carrying lines the store room cannot
 * take in (T-066).
 *
 * <p>Only described lines (`ingredientId === null`) that have not already arrived may be sent. The
 * server refuses anything else with a not-found naming the line, rather than quietly skipping it.
 *
 * <p>There is no date here on purpose: the arrival is recorded as the temple's today. Backdating —
 * "they actually came on Tuesday" — needs a refusal for a date in the future or behind the order,
 * and so an error code, and is deliberately left for a later task.
 */
export interface RecordArrivalsInput {
  poLineIds: string[];
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
  /**
   * What was paid, in rupees per one of this line's `unit`. Null where no price was given — a
   * delivery that arrived ahead of its bill, or a gift in kind — and never to be shown as ₹0.
   */
  unitPrice: number | null;
  /**
   * How much of `receivedQty` has since gone back to the vendor (T-013), in this line's `unit`.
   * `0` where nothing has, which is every line in the ordinary case — required and never optional,
   * so a screen that shows it cannot silently render an absent field as a blank.
   *
   * Derived on the server from the returns recorded against this line, never stored on the receipt:
   * a receipt says what the storekeeper accepted on the day and is never edited afterwards.
   */
  returnedQty: number;
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
  /**
   * What the bill that came with the lorry actually says, per one of the PO line's unit. Optional:
   * omit it (or send null) where there is no bill. Sending it writes the figure back onto the
   * vendor's last-known price, so a null must stay a null and never become a 0.
   */
  unitPrice?: number | null;
}

export interface ReceiveDeliveryInput {
  idempotencyKey: string;
  deliveryNoteRef?: string | null;
  note?: string | null;
  lines: ReceiptLineInput[];
}

/**
 * Why goods already taken into stock went back to the vendor (T-013).
 *
 * `NOT_DELIVERED` is the one that is not a `RejectReason`: the quantity was keyed wrongly and
 * nothing is physically going back, because nothing physically came.
 */
export type ReturnReason = "DAMAGED" | "SPOILED" | "WRONG_ITEM" | "NOT_DELIVERED" | "OTHER";

/**
 * Sending part or all of one received line back to the vendor.
 *
 * One line per request, matching the server: a return is about the sack somebody opened, not about
 * the whole lorry. `quantity` is positive — how much went back — and becomes a negative movement in
 * the ledger, so the returned goods leave on-hand the same way every other quantity does.
 */
export interface ReturnGoodsInput {
  idempotencyKey: string;
  receiptLineId: string;
  quantity: number;
  reason: ReturnReason;
  note?: string | null;
}

export interface GoodsReturnView {
  id: string;
  receiptId: string;
  receiptLineId: string;
  ingredientId: string;
  ingredientName: string;
  quantity: number;
  unit: string;
  reason: ReturnReason;
  note: string | null;
  returnedByName: string | null;
  returnedAt: string;
  stockMovementId: string;
}

/**
 * Where a vendor invoice sits. `VOIDED` is new in T-010 and is a terminal third state, not a flag:
 * a bill that should never have been recorded is out of the pay cycle entirely, and every figure
 * that sums invoices must skip it.
 *
 * <p>A **credit note is deliberately not a status.** It reduces what is owed and leaves the invoice
 * in the cycle, so it lives on `creditedAmount` below. Voiding says the bill was never owed;
 * crediting says it was owed and is now owed less, and a temple arguing with a vendor a year later
 * needs those two to be different answers rather than one word.
 */
export type InvoiceStatus = "PENDING" | "PAID" | "VOIDED";

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

  /**
   * When the bill was struck, and why. Null on every invoice that still stands.
   *
   * <p>Required and nullable rather than optional, on the rule wave 4c paid for: an optional field
   * can be dropped by a spread and TypeScript will not say so, and a screen reading `undefined`
   * cannot tell "not voided" from "the server did not tell me". Absent is never an answer here.
   */
  voidedAt: string | null;
  voidReason: string | null;

  /**
   * The total of credit notes recorded against this invoice, in the temple's currency. `0` when
   * there are none — never null, because "no credits" and "not told" would otherwise read alike on
   * a screen that subtracts it.
   */
  creditedAmount: number;

  createdAt: string;
}

/** One recorded payment against a vendor invoice (E7-S8), behind MANAGE_VENDOR_PAYMENTS. */
export interface InvoicePaymentView {
  id: string;
  paidOn: string;
  /**
   * Signed. Positive for a payment; **negative for a row that reverses an earlier one** — the
   * table has said so since V40 and permitted it with `CHECK (amount <> 0)`, which is why a bounced
   * cheque needs no new column to record.
   */
  amount: number;
  method: string;
  reference: string | null;
  note: string | null;
  recordedByName: string | null;

  /**
   * The two ends of a reversal, and the reason there are two rather than a `voided` flag:
   * `invoice_payments` is append-only, so nothing is ever marked. A reversal is a second row.
   *
   * <p>On the compensating row, `reverses` names the payment it undoes. On the original,
   * `reversedBy` names the row that undid it — worked out by the server, so a screen never has to
   * scan the list to find out whether a payment still stands. Both null on an ordinary payment.
   */
  reverses: string | null;
  reversedBy: string | null;
  reverseReason: string | null;

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

/**
 * One dated, attributed, permanent note about how somebody behaved (E6-S16).
 *
 * <p>Three facts and no fourth. There is no severity, no category, no note type and no
 * acknowledgement, and none of them is missing by accident — see `V84__staff_conduct_notes.sql`.
 *
 * <p>Nothing here can be edited or deleted. The table refuses both at the database, so the API
 * offers no PUT and no DELETE and this shape has no `updatedAt`.
 */
export interface StaffConductNoteView {
  id: string;
  body: string;
  authorUserId: string;
  /** The author's name, so a note is attributable without a second request. */
  authorName: string;
  createdAt: string;
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

/**
 * Taking somebody back on (T-014). The inverse of {@link EndEmploymentInput}, and it has to be told
 * two things the server cannot work out for itself.
 *
 * <p>**`systemAccess` is not optional and is not inferred.** Ending an employment either disables
 * the account outright or drops the person back to being an ordinary devotee, and it stores nothing
 * anywhere about what their access had been — so there is no prior value to restore. The admin says
 * what they come back as, exactly as the hire form does. `null` means they return with no login,
 * which is a real and common answer for a cook.
 *
 * <p>`dateOfRejoining` is required for the same reason `lastWorkingDay` is: an admin recording a
 * reinstatement a week after it happened means the day it happened, and a server clock does not
 * know that.
 */
export interface ReinstateStaffInput {
  dateOfRejoining: string;
  systemAccess: SystemAccess | null;
  reason?: string | null;
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

/**
 * One date the server resolved as covered by approved leave (T-032).
 *
 * <p>The four fields are `WeekScheduleView.ResolvedDay`'s leave fields and no others, for the reason
 * that record already gives: the label is printed by the server so the browser keeps no copy of the
 * vocabulary, and a half day leaves the person in for part of it so the day's hours still stand.
 *
 * <p>It arrives resolved, per date, because the resolution order — approved leave, then the per-date
 * override, then the template — lives once, in `ScheduleResolver`. A screen that mapped leave spans
 * onto dates itself would be a second answer beside the server's, and the two would disagree the
 * first time that order changed.
 */
export interface ScheduleLeaveDay {
  date: string;
  leaveId: string;
  leaveType: LeaveType;
  /** What to print for the leave. */
  leaveLabel: string;
  /** A half day leaves them in for part of it, so the hours for that date still stand. */
  halfDayLeave: boolean;
}

export interface StaffProfileDetailView {
  profile: StaffProfileView;
  template: ScheduleDay[];
  exceptions: ScheduleExceptionView[];
  /**
   * Approved leave across `leaveFrom`–`leaveTo`, one entry per covered date (T-032).
   *
   * <p>Optional *and* nullable because the same payload is served by `/staff/profiles/{id}`, which
   * answers a manager's template question and resolves no leave: absent in a hand-built test object,
   * `null` on the wire, and neither is a lie about the other endpoint. Absent or null means *not
   * resolved*, never *no leave* — a screen must not read either as a clear fortnight.
   */
  leaveDays?: ScheduleLeaveDay[] | null;
  /** Inclusive window `leaveDays` was resolved across, so a screen can tell what it was told about. */
  leaveFrom?: string | null;
  leaveTo?: string | null;
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

/**
 * What one day of the schedule has to say about its own staffing (E6-S15).
 *
 * <p>Four states and no fifth, because each is a different sentence and the grid has to be able to
 * say which. `CREW_NOT_SET` is emphatically not `COVERED`: drawing the two alike is how a month of
 * unplanned days comes to look reassuring.
 */
export type CoverageState = "NOTHING_PLANNED" | "CREW_NOT_SET" | "COVERED" | "SHORT";

/**
 * One day with what it needs beside what it has (E6-S15).
 *
 * <p>`staffIn` and `volunteers` are the very figures at the foot of the week grid — one number,
 * computed once, on the server (E6-S11 D5, E6-S14 D2). Nothing in the browser adds up a column.
 *
 * <p>`shortBy` is the deepest meal's shortfall, not the day's sum: a day is short by the worst
 * moment in it. The meal that is deepest short travels with the number so the screen can name it
 * rather than leave a manager opening three meals to find out which.
 */
export interface DayCoverage {
  date: string;
  staffIn: number;
  volunteers: number;
  state: CoverageState;
  /** Never negative — a meal with more hands than it asked for is covered, not surplus. */
  shortBy: number;
  /** The meal that is deepest short; all four are null when the day is not short. */
  shortAt: string | null;
  shortAtReadyBy: string | null;
  shortAtRequired: number | null;
  shortAtRostered: number | null;
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

  /**
   * The meal this shift was posted for (D-14), or null on a shift that was not linked to one.
   * All three move together: a linked shift counts toward its meal and no other, an unlinked one
   * keeps counting toward every meal its hours span. `mealEventName` is null except where the meal
   * is a named event.
   *
   * Optional rather than required-nullable, and deliberately: the server always sends all three,
   * but every existing test fixture in the tree builds a `ShiftView` by hand, and making them
   * required would have meant editing test files that belong to other tasks' contracts to add three
   * nulls that prove nothing. A reader must handle `undefined`, which is the same branch as null.
   */
  mealDate?: string | null;
  mealKind?: string | null;
  mealEventName?: string | null;
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

  /** Link this shift to one meal (D-14). All three or none; the server refuses a half-filled link. */
  mealDate?: string | null;
  mealKind?: string | null;
  mealEventName?: string | null;
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

/** The four reasons a coordinator may give for taking somebody off a roster (T-080). */
export type ShiftRemovalReason = "SHIFT_CANCELLED" | "NO_LONGER_NEEDED" | "ROTA_CHANGED" | "OTHER";

export interface RemoveVolunteerInput {
  /** Safe to show, and what the volunteer is told. */
  reason: ShiftRemovalReason;
  /** Mandatory, and never sent — the audit trail and the roster only. */
  internalNote: string;
}

export interface RosterSignup {
  userId: string;
  fullName: string;
  source: string;
  signedUpAt: string;
  releasedAt: string | null;
  /**
   * Why a coordinator took this volunteer off the roster (T-080), and their own note on it. Both
   * null on a volunteer's OWN release — nobody is asked to justify withdrawing — so a non-null
   * reason is how the roster tells the temple's act from the devotee's. `releasedNote` is INTERNAL:
   * the roster and the audit trail, and nothing that sends.
   */
  releasedReason: ShiftRemovalReason | null;
  releasedNote: string | null;
  /**
   * Whether this volunteer turned up (T-016). Null means nobody has said yet — which is a different
   * fact from `false`, and the reason this is a nullable boolean rather than a plain one: every
   * reliability and hours-contributed figure downstream has to be able to tell "did not come" from
   * "was never marked", or a shift nobody got round to marking would read as a roster of no-shows.
   */
  attended: boolean | null;
  /**
   * When this shift's attendance was marked. Null until it has been, and unchanged by a later
   * correction (T-079) — it says when the roster was marked, not what the answer currently is.
   */
  attendanceRecordedAt: string | null;
  /**
   * When the mark was changed, and by whom (T-079, shown by T-099). **Null on a mark that stands as
   * first given — including a first answer given late**, which is not a correction of anything and
   * deliberately leaves these null.
   *
   * <p>The name is null too if the corrector's user row is gone, because `attendance_corrected_by`
   * is `ON DELETE SET NULL` — so a row can read "corrected on …" with no name while still being,
   * in fact, corrected. Read the timestamp for whether, the name for who.
   */
  attendanceCorrectedAt: string | null;
  attendanceCorrectedByName: string | null;
  reminders: RosterReminder[];
}

/**
 * Marking who actually turned up to one shift (T-016), behind `MANAGE_VOLUNTEER_SHIFTS`.
 *
 * <p>One call for the whole roster rather than one per volunteer: the coordinator is standing in
 * front of the crew with a list, and marking them one at a time would leave a half-marked shift as
 * a normal intermediate state that nothing downstream could interpret.
 */
export interface ShiftAttendanceInput {
  /** Every volunteer being marked. Somebody left out is left unmarked, not marked absent. */
  marks: { userId: string; attended: boolean }[];
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
  /** Servings on today's plan. Null when nothing is planned — the line is left out rather than zeroed. */
  platesToday: number | null;
  /** Last month's kitchen spend over the servings it produced. Null until there is enough of both. */
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

/**
 * What repeating an event forward actually did (E4-S15 D8).
 *
 * <p>What it makes is **copies, not a series.** Each one is a plan in its own right and can be
 * edited or cancelled without touching the others, so no screen ever has to ask *this one or all of
 * them?* A true recurrence rule was considered and deferred: the temple's problem is not wanting to
 * type the same Saturday reading fifty-two times.
 */
export interface RepeatEventResult {
  /** How many preparations were written. Six weekly copies of a two-dish event is twelve. */
  copied: number;
  weeksCopied: number;
  /** Weeks skipped because an Ekadashi falls there and the recipe does not suit it. */
  refusedOnFast: number;
}

/**
 * Something the temple has undertaken to send out of the building (E4-S15 D4).
 *
 * <p>One row per event, not per dish: an event of three preparations is one commitment, and three
 * lines for one delivery would read as three deliveries.
 */
export interface OutsideCommitment {
  planDate: string;
  eventName: string | null;
  mealKind: string;
  handover: Handover | null;
  contactName: string | null;
  contactPhone: string | null;
  deliveryAddress: string | null;
  /** "HH:mm:ss" — when the food must be ready, and when the guests sit down on a delivery. */
  readyBy: string;
  guestsEatAt: string | null;
  preparations: number;
}

/**
 * When to leave the temple for a delivery (E4-S16).
 *
 * <p>It says when to *leave*, not how long it takes: a driver can act on *leave by 11:15* and
 * nobody can act on *37 minutes*. The leave-by comes off the pessimistic end, because arriving
 * early is an inconvenience and arriving after the guests have sat down is the thing this exists to
 * prevent.
 *
 * <p>Unavailable is a first-class answer and never an error. `reason` is `NOT_A_DELIVERY`,
 * `NO_SERVING_TIME`, `NO_MAP_SERVICE`, `ADDRESS_NOT_FOUND` or `NO_ROUTE`.
 */
/**
 * Reusing a stretch of plan somebody already made (2026-09-05).
 *
 * @param days how many days to read from `sourceStart`. One is not a special case — it is how last
 *             year's Janmashtami is carried to this year, which cannot be offset arithmetic because
 *             the date moves with the Vaishnava calendar.
 * @param mealKinds which kinds to bring, by name. Absent means every main meal found.
 * @param eventNames which events to bring, by name. Absent means none: an event was arranged, and
 *             arranging it again is a decision rather than something inherited.
 */
export interface ReusePlanRequest {
  sourceStart: string;
  days: number;
  targetStart: string;
  mealKinds?: string[] | null;
  eventNames?: string[] | null;
}

/** What reusing a plan would do, worked out without writing anything. */
export interface ReusePlanPreview {
  sourceWasEmpty: boolean;
  kinds: { mealKind: string; dayCount: number; mealCount: number }[];
  /** `occurrences` is the honest signal for "does this repeat?" — nothing in the schema records it. */
  events: { eventName: string; occurrences: number; outside: boolean; lastSeen: string | null }[];
  excluded: { label: string; reason: string; on: string }[];
  headCounts: { mealKind: string; adults: number | null; children: number | null; seniors: number | null }[];
  days: {
    targetDate: string;
    sourceDate: string;
    /** The whole day is left alone. Nothing is ever overwritten. */
    alreadyPlanned: boolean;
    fastName: string | null;
    meals: {
      mealKind: string;
      eventName: string | null;
      recipeName: string;
      copied: boolean;
      /** Why not, in the words the screen prints. Null when it would be copied. */
      skippedReason: string | null;
    }[];
  }[];
  totals: { meals: number; daysWritten: number; daysLeftAlone: number; notCopied: number };
}

export interface ReusePlanResult {
  copied: number;
  daysWritten: number;
  daysLeftAlone: number;
  notCopied: number;
  sourceWasEmpty: boolean;
}

/** One address somebody might have meant, offered while they type. */
export interface PlaceSuggestion {
  /** Google's stable id. Stored on the plan, so the address survives a road being renamed. */
  placeId: string;
  /** The whole address as Google writes it — what goes in the box when this is picked. */
  description: string;
  /** The leading part, for a list where the full address is too long to scan. */
  primary: string;
  secondary: string;
}

/** Where a picked suggestion actually is, looked up once when it is chosen. */
export interface ResolvedPlace {
  placeId: string;
  formattedAddress: string;
  at: { latitude: number; longitude: number };
}

export interface TravelEstimate {
  available: boolean;
  /** "HH:mm:ss", or null. */
  leaveBy: string | null;
  optimisticMinutes: number | null;
  pessimisticMinutes: number | null;
  /** The time it was worked backwards from, so the screen can show its arithmetic. */
  guestsEatAt: string | null;
  reason: string | null;
}

/**
 * An event the temple has run before, offered while its name is being typed (E4-S15 D9).
 *
 * <p>The suggestion carries the last one's contact, handover and address with it, and that is the
 * point rather than a nicety: the temple's own artifacts contain no event register at all, so this
 * is a practice being introduced rather than digitised. If entering a Saturday reading costs three
 * minutes it will stop being entered, and the data is then worse than if events had never been
 * split out.
 */
export interface EventNameSuggestion {
  eventName: string;
  isOutside: boolean;
  handover: Handover | null;
  contactName: string | null;
  contactPhone: string | null;
  deliveryAddress: string | null;
}

/**
 * What a saved plan came back with (E4-S16).
 *
 * <p>There is exactly one thing that warns and it is never a refusal: a delivery address the map
 * service could not place (KMS-400078). The plan is saved and whole — a map service's opinion of a
 * street name is not a reason to throw away everything somebody typed — but it is worth saying,
 * because it is the one travel failure they can fix.
 */
export interface SavedMealPlan {
  id?: string;
  warning?: ErrorPayload;
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

  /**
   * Struck as wrongly recorded (T-012) — entered twice, or against the wrong donor.
   *
   * <p>A separate field and not a value of `status` above, deliberately: `status` says how the
   * payment went, and a gift can perfectly well have completed and then been voided. Folding them
   * would make one column answer two questions and lose whichever was asked second.
   *
   * <p>A voided row **stays in the ledger, marked**, and is **excluded from the period summary** —
   * the 80G figures are what the temple reports, and they must show what it actually received.
   * Required rather than optional so that a row built without it is a type error and not a gift
   * quietly counted.
   */
  voided: boolean;
  voidReason: string | null;
}


/**
 * One gift in full, for `/donations/[id]` (T-110).
 *
 * <p>Wider than `LedgerRow` on purpose. The ledger's donor column is anonymity-safe by construction
 * — a hundred rows on one screen is a hundred chances to leak one — whereas this is a single gift
 * opened deliberately by somebody about to put the temple's name on a tax document made out to this
 * person. An anonymous gift still carries nothing: the database's own CHECK guarantees such a row
 * holds no name, contact, address or PAN.
 */
export interface DonationDetail {
  id: string;
  type: string;
  category: string;
  donatedOn: string;
  status: string;
  voided: boolean;
  voidReason: string | null;

  /**
   * What the donor paid — and for a gift of goods, the temple's own estimate of worth instead.
   *
   * <p>**Never re-derived from the split.** T-081 kept this column meaning one payment precisely so
   * that one payment produces one receipt, which is why a split gift is one donation row and not two.
   */
  amountInr: number | null;

  /**
   * How much of that payment reached the wish-list item it names, or null where all of it did.
   *
   * <p>On this screen for exactly one reason — so a reader understands why the receipt's figure is
   * larger than the item's progress — and it never reaches the receipt itself.
   */
  wishlistApplied: number | null;
  currency: string | null;
  paymentMode: string | null;
  providerRef: string | null;
  linkedTo: string | null;
  anonymous: boolean;
  donorName: string | null;
  donorPhone: string | null;
  donorEmail: string | null;
  donorAddress: string | null;
  wants80g: boolean;
  /** Whether a PAN was captured, without carrying it. Revealing it is a separate, audited call. */
  hasPan: boolean;
  notes: string | null;
  acknowledgedAt: string | null;
  /** The permanent receipt number, or null where no receipt has been issued yet. */
  receiptNumber: string | null;
  receiptIssuedAt: string | null;
  temple80gApproved: boolean;
  /** False for a struck gift. The screen withholds the control rather than offering one that refuses. */
  canBeReceipted: boolean;
}

/** What issuing a receipt answers: the same document id and the same number on every later press. */
export interface DonationReceiptIssued {
  documentId: string;
  status: string;
  receiptNumber: string | null;
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
 * sent a tester chasing a network fault when the real answer was KMS-400030, the file was never
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
  // KMS-400081 unless the temple was exported in the last 24 hours — the export is the only copy.
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

  // Correcting a temple's own record (MANAGE_TENANTS). PATCH rather than PUT because `slug` and the
  // provisioning admin are not part of it and never come back up the wire.
  updateTenant: (id: string, input: UpdateTenantInput, token?: string) =>
    request<void>(`/api/v1/tenants/${id}`, {
      method: "PATCH",
      body: JSON.stringify(input),
      token,
    }),

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
    if (filters.ekadashiCompatible) params.set("ekadashiCompatible", "true");
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
   * Removes it outright. Refused with KMS-400102 for a recipe any meal plan has ever named — that
   * one is archived instead, so the record of what was cooked keeps its dish.
   */
  deleteRecipe: (id: string, token?: string) =>
    request<void>(`/api/v1/recipes/${id}`, { method: "DELETE", token }),

  // Ingredient catalogue (E2-S1).
  listIngredients: (token?: string) =>
    request<IngredientView[]>("/api/v1/ingredients", { method: "GET", token }),

  // How many ingredients a recipe import created and nobody has saved since (T-119). One integer
  // rather than the catalogue, for `/recipes` — the screen an import is started from, which has no
  // ingredient list of its own and would otherwise fetch several hundred rows to arrive at a
  // sentence. The ingredients screen holds the list already and counts what it is holding, so it
  // does not call this.
  countIngredientsAddedByImport: (token?: string) =>
    request<{ count: number }>("/api/v1/ingredients/library-derived-count", {
      method: "GET",
      token,
    }),

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

  // The Ekadashi flag, and after D-18 the only dietary flag there is. The endpoint has existed and
  // been audited since the ingredient module was built (`IngredientController:92-99`); only the
  // wrapper was missing (T-045), so the flag could be set nowhere but the provisioning seed — and
  // that seed is gone too, which is why `/recipes` now carries a warning saying so.
  //
  // NOTHING CALLS THIS as of T-121. Rajeev removed the one-click toggle on the ingredients row on
  // 2026-09-10 — "Ingredients don't go in and out of Ekadashi restriction EVER" — and the flag is
  // now a checkbox in the editing row, which rides on `updateIngredient` above. The wrapper and its
  // endpoint are left in place rather than deleted, because whether to withdraw a published,
  // audited route is Rajeev's call and not a tidy-up; see `IngredientService.setEkadashiFlag`.
  setIngredientEkadashiFlag: (id: string, ekadashiProhibited: boolean, token?: string) =>
    request<void>(`/api/v1/ingredients/${id}/ekadashi-flag`, {
      method: "PATCH",
      body: JSON.stringify({ ekadashiProhibited }),
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

  /**
   * Every stock movement drawn against one dish of a meal (T-007), newest first.
   *
   * <p>The read capability that was missing, and the reason correcting a meal was not simply a
   * screen over `compensateMovement`: consumption writes **one movement per (ingredient, batch)
   * draw**, so a dish is a *set* of movements and `listMovements` could filter by ingredient, type
   * and limit but never by what the movements were drawn for. `mealPlanId` is one dish's row — a
   * meal of three dishes is three calls, because that is the grain `reference_id` is stored at.
   */
  movementsForMeal: (mealPlanId: string, token?: string) =>
    request<StockMovement[]>(
      `/api/v1/inventory/movements?referenceId=${encodeURIComponent(mealPlanId)}`,
      { method: "GET", token }
    ),

  compensateMovement: (id: string, note: string, token?: string) =>
    request<{ id: string }>(`/api/v1/inventory/movements/${id}/compensate`, {
      method: "POST",
      body: JSON.stringify({ note }),
      token,
    }),

  /**
   * The equipment register (E3-S4), behind MANAGE_INVENTORY like the consumables beside it.
   *
   * <p>The screen asks only for `includeScrapped` and narrows by condition, location and service
   * status in the browser. Not because the server cannot: `serviceStatus` is the filter the Today
   * nudge links through, and it is here for that. It is that the three filters have to *combine*,
   * and each dropdown has to go on offering every value the register holds rather than only the
   * values that survive the filter already set — which a server round trip per filter cannot do
   * without asking twice for the same list. The register is tens of rows; the derived fields are
   * on every one of them, so the browser's answer and the server's are the same answer.
   */
  listEquipment: (filters: EquipmentFilters = {}, token?: string) => {
    const params = new URLSearchParams();
    if (filters.includeScrapped) params.set("includeScrapped", "true");
    if (filters.location) params.set("location", filters.location);
    if (filters.serviceStatus) params.set("serviceStatus", filters.serviceStatus);
    const query = params.toString();
    return request<EquipmentView[]>(`/api/v1/equipment${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  /** One machine with its service history and its condition trail, each newest first. */
  getEquipment: (id: string, token?: string) =>
    request<EquipmentDetail>(`/api/v1/equipment/${id}`, { method: "GET", token }),

  createEquipment: (input: CreateEquipmentInput, token?: string) =>
    request<{ id: string }>("/api/v1/equipment", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** Corrects the descriptive fields. Not the condition, and not the service interval. */
  updateEquipment: (id: string, input: UpdateEquipmentInput, token?: string) =>
    request<void>(`/api/v1/equipment/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Moves a machine to a new condition, with a reason. There is no field edit that does this — the
   * whole point of the state-change flow is that "sent for repair" never happens without a why.
   */
  changeEquipmentCondition: (
    id: string,
    input: { condition: EquipmentCondition; reason: string },
    token?: string,
  ) =>
    request<void>(`/api/v1/equipment/${id}/condition`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Brings a scrapped machine back into use, with the condition it returns in and a required
   * reason (D-15). A separate verb from `changeEquipmentCondition`, which goes on refusing every
   * post-scrap edit: a request body that could switch that guard off would be a guard in name only.
   * `REINSTATE_SCRAPPED_EQUIPMENT`, Temple Admin alone.
   */
  reinstateEquipment: (
    id: string,
    input: { condition: EquipmentCondition; reason: string },
    token?: string,
  ) =>
    request<void>(`/api/v1/equipment/${id}/reinstate`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** Sets or clears the service interval and the company that services it. MANAGE_EQUIPMENT_SERVICING. */
  setEquipmentServiceSchedule: (id: string, input: ServiceScheduleInput, token?: string) =>
    request<void>(`/api/v1/equipment/${id}/service-schedule`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Records a service that has happened. Append-only: there is no verb that edits or removes one,
   * and no "last serviced" field anywhere a person can type into.
   */
  recordEquipmentService: (id: string, input: RecordServiceInput, token?: string) =>
    request<{ id: string }>(`/api/v1/equipment/${id}/services`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // Donations (E3-S5). Recording is MANAGE_INVENTORY; reading is the ledger, behind VIEW_DONATIONS.
  /**
   * Strikes a hand-recorded gift entered twice or against the wrong donor (T-012). Behind the new
   * `VOID_DONATION`, the Temple Admin's alone (D-4) — recording stays on MANAGE_INVENTORY, so a
   * cook may create one of these and never undo one.
   *
   * <p>Where the gift was in kind, this also appends the compensating stock movement **in the same
   * transaction**, so the ledger and the store-room can never disagree about it. Refuses a second
   * void with `KMS-400134`.
   */
  voidDonation: (id: string, reason: string, token?: string) =>
    request<void>(`/api/v1/donations/${id}/void`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

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

  // Curating that catalogue is a temple-settings decision, so the three writes are
  // MANAGE_TEMPLE_SETTINGS while the two reads above are the planner's.
  createOccasion: (input: CreateOccasionInput, token?: string) =>
    request<{ id: string }>("/api/v1/occasions", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  updateOccasion: (id: string, input: UpdateOccasionInput, token?: string) =>
    request<void>(`/api/v1/occasions/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  deleteOccasion: (id: string, token?: string) =>
    request<void>(`/api/v1/occasions/${id}`, { method: "DELETE", token }),

  // Meal slots + plans (E4-S4/S5/S6).
  listMealKinds: (token?: string) =>
    request<MealKindView[]>("/api/v1/meal-kinds", { method: "GET", token }),

  // Curating the kinds and their times is a temple-settings decision (MANAGE_TEMPLE_SETTINGS).
  updateMealKind: (id: string, input: MealKindInput, token?: string) =>
    request<void>(`/api/v1/meal-kinds/${id}`, { method: "PUT", body: JSON.stringify(input), token }),

  createMealKind: (input: MealKindInput, token?: string) =>
    request<{ id: string }>("/api/v1/meal-kinds", { method: "POST", body: JSON.stringify(input), token }),

  // Refuses with MEAL_KIND_IN_USE (KMS-400126) when a meal is planned, recorded or rostered under
  // the kind — the refusal names which of the three, and points at renaming instead (T-038). A kind
  // that is already gone is a silent 204, so a second click never raises.
  deleteMealKind: (id: string, token?: string) =>
    request<void>(`/api/v1/meal-kinds/${id}`, { method: "DELETE", token }),

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
      /** Days of notice before a batch reaches its use-by date. 7 unless the temple changed it. */
      stockExpiryWarningDays: number;
      /** Days of notice before a vendor's agreement runs out. 30 unless the temple changed it. */
      contractEndWarningDays: number;
      /** Days of notice before a machine is due to be serviced. 30 unless the temple changed it. */
      equipmentServiceWarningDays: number;
    }>("/api/v1/settings", {
      method: "GET",
      token,
    }),

  /**
   * How much notice this temple wants, on all three of the things that warn ahead of a date.
   *
   * <p>Sent together on purpose. Stock and contracts were one shared number until the contract
   * horizon outgrew it, and saving them in one request is what stops one moving without the other.
   * The servicing horizon joined them with E3-S10 and is sent here too, from E3-S11's own control —
   * the server accepted it as optional only while this screen was still posting two of the three,
   * which would have quietly reset the third on every save.
   */
  setWarningHorizons: (
    horizons: {
      stockExpiryWarningDays: number;
      contractEndWarningDays: number;
      equipmentServiceWarningDays: number;
    },
    token?: string,
  ) =>
    request<void>("/api/v1/settings/warning-horizons", {
      method: "PUT",
      body: JSON.stringify(horizons),
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

  /**
   * The names this temple has used for its events before, most-recently-used first (E4-S15 D9).
   *
   * <p>At most ten, and `q` is optional — with nothing typed it answers with the last few, which is
   * what a planner about to enter the same Saturday reading actually wants.
   */
  eventNameSuggestions: (q: string, token?: string) =>
    request<EventNameSuggestion[]>(
      `/api/v1/meal-plans/event-names${q ? `?q=${encodeURIComponent(q)}` : ""}`,
      { method: "GET", token }
    ),

  /** What is going out of the temple from today onwards, soonest first (E4-S15 D4). */
  outsideCommitments: (token?: string) =>
    request<OutsideCommitment[]>("/api/v1/meal-plans/outside-commitments", { method: "GET", token }),

  /**
   * When to leave the temple for this delivery (E4-S16).
   *
   * <p>Always answers. Every way it can fail to produce a number — no map service, an address
   * nobody could place, a meal that is not a delivery — comes back as an unavailable estimate with
   * a reason, which the screen renders as one quiet line.
   */
  travelEstimate: (id: string, token?: string) =>
    request<TravelEstimate>(`/api/v1/meal-plans/${id}/travel-estimate`, { method: "GET", token }),

  /**
   * The same estimate for a delivery nobody has saved yet — what the composer asks while somebody
   * is still typing.
   *
   * <p>The saved version takes a plan id, which a form has not got. This takes the place instead:
   * the coordinates behind a picked address, or its place id if the coordinates are not to hand.
   * An address that was typed rather than picked has neither, and comes back `ADDRESS_NOT_FOUND` —
   * the honest answer, since nobody looked.
   */
  travelEstimateFor: (
    at: { placeId?: string | null; latitude?: number | null; longitude?: number | null },
    planDate: string,
    guestsEatAt: string,
    token?: string
  ) => {
    const q = new URLSearchParams({ planDate, guestsEatAt });
    if (at.latitude != null && at.longitude != null) {
      q.set("latitude", String(at.latitude));
      q.set("longitude", String(at.longitude));
    } else if (at.placeId) {
      q.set("placeId", at.placeId);
    }
    return request<TravelEstimate>(`/api/v1/meal-plans/travel-estimate?${q}`, {
      method: "GET",
      token,
    });
  },

  /**
   * Whether the delivery address box can offer suggestions.
   *
   * <p>Asked once when the form opens so it can choose between a picker and a plain box before
   * anybody types, rather than showing a picker that will never suggest anything.
   */
  placesAvailable: (token?: string) =>
    request<{ available: boolean }>("/api/v1/places/available", { method: "GET", token }),

  /**
   * Addresses matching what has been typed. Always answers; an empty list means no map service, or
   * nothing matched, and either way the box carries on as a plain text field.
   *
   * @param session a token generated once per search and kept until something is picked — it is
   *                what makes a whole search bill as one lookup rather than one per keystroke.
   */
  placeSuggestions: (q: string, session: string, token?: string) =>
    request<PlaceSuggestion[]>(
      `/api/v1/places/suggest?q=${encodeURIComponent(q)}&session=${encodeURIComponent(session)}`,
      { method: "GET", token }
    ),

  /** The address and coordinates behind a picked suggestion. Null where it could not be resolved. */
  resolvePlace: (placeId: string, session: string, token?: string) =>
    request<ResolvedPlace | null>(
      `/api/v1/places/${encodeURIComponent(placeId)}?session=${encodeURIComponent(session)}`,
      { method: "GET", token }
    ),

  /**
   * Repeats an event forward for a number of weeks (E4-S15 D8). Copies, not a series: each one is
   * editable and cancellable on its own.
   */
  repeatEvent: (id: string, weeks: number, token?: string) =>
    request<RepeatEventResult>(`/api/v1/meal-plans/${id}/repeat?weeks=${weeks}`, {
      method: "POST",
      token,
    }),

  createMealPlan: (input: CreateMealPlanInput, token?: string) =>
    request<SavedMealPlan>("/api/v1/meal-plans", {
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
    // 204 when there is nothing to say, 200 with a warning in the body in the one case that has
    // something to say — the address that could not be placed. `request` hands back undefined for
    // the 204, so a caller that only cares about success can ignore what comes out.
    request<SavedMealPlan | undefined>(`/api/v1/meal-plans/${id}`, {
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
  /**
   * What reusing a stretch of plan would do, without doing it.
   *
   * <p>A POST because it carries a body, not because it changes anything — the screen calls it on
   * every tick. One call answers both halves: what is in the source window, and what would land.
   */
  previewReuse: (input: ReusePlanRequest, token?: string) =>
    request<ReusePlanPreview>("/api/v1/meal-plans/reuse/preview", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** Writes what the preview said. Only ever adds; a target day with meals is left alone whole. */
  reusePlan: (input: ReusePlanRequest, token?: string) =>
    request<ReusePlanResult>("/api/v1/meal-plans/reuse", {
      method: "POST",
      body: JSON.stringify(input),
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

  /** How many meals went unrecorded in the range, and the servings each kind came to on `from`. */
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
   * Corrects what a recorded meal actually served (T-007), behind `CORRECT_RECORDED_MEAL`.
   *
   * <p>`id` is the meal's own `serviceId`, which is non-null exactly once the meal has been
   * recorded — so there is no case where this is callable and the identity is ambiguous, which is
   * why it takes an id where `recordMeal` takes a date, a kind and an event name.
   *
   * <p>What comes back is the meal as it now reads, `corrected` true, with each dish carrying both
   * its new figure and its `originalActualServings`. It is one call because it is one transaction:
   * the compensating stock movements and the meal record cannot commit separately, or the ledger
   * and the meal would disagree and nothing would say which was right.
   */
  correctRecordedMeal: (id: string, input: CorrectMealInput, token?: string) =>
    request<MealServiceView>(`/api/v1/meal-services/${id}/correct`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Queues a job card, issuing its number if this is the first print of that meal.
   *
   * <p>`eventName` is part of the key, not a detail. A meal is identified by its date, its kind and
   * — since `V89` — the event's name, because every event carries the same kind and two events on
   * one Saturday would otherwise share a card. Null for the three main meals, which have no event
   * name and never will.
   *
   * <p>`language` is the recipes appendix's, not the sheet's — the worksheet is always English.
   * Pass `"none"` for the worksheet on its own.
   */
  requestJobCard: (
    date: string,
    mealKind: string,
    eventName: string | null,
    language?: string,
    token?: string
  ) =>
    request<{ documentId: string; cardNumber: string; status: string }>(
      `/api/v1/job-cards?date=${date}&mealKind=${encodeURIComponent(mealKind)}` +
        (eventName ? `&eventName=${encodeURIComponent(eventName)}` : "") +
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
  jobCardLanguages: (date: string, mealKind: string, eventName: string | null, token?: string) =>
    request<{ languages: string[]; defaultLanguage: string }>(
      `/api/v1/job-cards/languages?date=${date}&mealKind=${encodeURIComponent(mealKind)}` +
        (eventName ? `&eventName=${encodeURIComponent(eventName)}` : ""),
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
  jobCardPrintUrl: (
    date: string,
    mealKind: string,
    eventName: string | null,
    language?: string
  ): string =>
    `${BASE_URL}/api/v1/job-cards/print?date=${date}&mealKind=${encodeURIComponent(mealKind)}` +
    (eventName ? `&eventName=${encodeURIComponent(eventName)}` : "") +
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

  /**
   * The same estimate split by kind of meal over a period, with a figure per serving (E3-S9).
   * Behind MANAGE_MEAL_PLANS, like the daily figure — it is the same fact asked a different way.
   */
  costByMealKind: (from: string, to: string, token?: string) =>
    request<CostByMealKind>(`/api/v1/materials-cost/by-meal-kind?from=${from}&to=${to}`, {
      method: "GET",
      token,
    }),

  /**
   * What the store issued to each kitchen over a period, costed (E10-S13). Behind MANAGE_INVENTORY
   * rather than MANAGE_MEAL_PLANS: this one reads the stock ledger, not the meal planner.
   */
  issuedFromStore: (from: string, to: string, token?: string) =>
    request<IssuedFromStore>(`/api/v1/issued-from-store?from=${from}&to=${to}`, {
      method: "GET",
      token,
    }),

  // ---- Vendors (E5-S1), behind MANAGE_VENDORS server-side. -----------------
  /**
   * The vendor list, optionally narrowed to the ones still in use.
   *
   * <p>The endpoint's question is the opposite of the screen's: it takes `includeInactive`, and
   * defaults it to false. This asked it `activeOnly`, a parameter the controller has never had, so
   * the flag was dropped on the floor and every call fell through to the default — the list showed
   * only active vendors whatever the checkbox said, and a deactivated vendor was unreachable from
   * this screen while still being named on the performance report.
   */
  listVendors: (activeOnly = false, token?: string) =>
    request<VendorView[]>(`/api/v1/vendors${activeOnly ? "" : "?includeInactive=true"}`, {
      method: "GET",
      token,
    }),

  getVendor: (id: string, token?: string) =>
    request<VendorDetailView>(`/api/v1/vendors/${id}`, { method: "GET", token }),

  /**
   * The vendor performance report (E5-S9). Behind MANAGE_VENDORS, like the vendor's own page — it
   * is a judgement about a supplier, read for the same reason.
   */
  vendorPerformance: (from: string, to: string, token?: string) =>
    request<VendorPerformance>(`/api/v1/vendor-performance?from=${from}&to=${to}`, {
      method: "GET",
      token,
    }),

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

  /**
   * Drop a vendor, with the reason it is being dropped. The reason is required — the server refuses
   * a blank one with KMS-400011 — and is kept as history, never overwritten.
   */
  deactivateVendor: (id: string, reason: string, token?: string) =>
    request<void>(`/api/v1/vendors/${id}/deactivate`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  /** Bring a vendor back. A reason is welcome here but not demanded. */
  reactivateVendor: (id: string, reason: string | null, token?: string) =>
    request<void>(`/api/v1/vendors/${id}/reactivate`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  /**
   * Write the whole supply row — ingredient, price, lead time and preference — creating it if this
   * vendor does not supply the ingredient yet and replacing it if they do.
   *
   * <p><strong>Every field is required-and-nullable, and that is the point (T-131).</strong> The
   * server's statement is an `INSERT … ON CONFLICT (vendor_id, ingredient_id) DO UPDATE SET
   * last_price = EXCLUDED.last_price, lead_time_days = EXCLUDED.lead_time_days, preferred =
   * EXCLUDED.preferred`, so **it writes all three columns on every call**. A caller that omits one
   * is not saying "leave it alone", it is silently erasing it — an edit that meant to change a lead
   * time would take the price and the preference down with it, which is the exact loss T-131 exists
   * to stop. Optional keys let `tsc` wave that through; required-and-nullable ones make forgetting
   * a compile error. Absent and null read identically to `objectContaining`, so the tests inspect
   * `Object.keys` too.
   *
   * <p>On `leadTimeDays`: null clears it back to "nobody has said". **Never send 0 for unknown** —
   * 0 means the goods come the same day, and the planner counts back from the two differently.
   */
  setVendorSupply: (
    id: string,
    input: {
      ingredientId: string;
      lastPrice: number | null;
      leadTimeDays: number | null;
      preferred: boolean;
    },
    token?: string
  ) =>
    request<void>(`/api/v1/vendors/${id}/supplies`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

  removeVendorSupply: (id: string, ingredientId: string, token?: string) =>
    request<void>(`/api/v1/vendors/${id}/supplies/${ingredientId}`, { method: "DELETE", token }),

  // ---- Shopping list (E5-S2), behind MANAGE_PURCHASE_ORDERS. ------------------
  listShoppingList: (token?: string) =>
    request<ShoppingListLineView[]>("/api/v1/shopping-list", { method: "GET", token }),

  // A line added by hand, for something no demand stream suggested (T-027). No `unit`: the server
  // writes the ingredient's own canonical_unit, and letting a caller pick a different one is how a
  // list ends up asking for 5 litres of rice.
  addShoppingListLine: (
    input: { ingredientId: string; suggestedQty: number; suggestedVendorId?: string | null },
    token?: string
  ) =>
    request<ShoppingListLineView>("/api/v1/shopping-list", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  // No `suggestedVendorId`: the vendor is derived on every read from the ingredient's preferred
  // supplier, and the column it used to be written to went with T-132. What gets snapshotted is the
  // vendor on the purchase order itself.
  updateShoppingListLine: (
    ingredientId: string,
    input: { suggestedQty?: number | null; included: boolean },
    token?: string
  ) =>
    request<void>(`/api/v1/shopping-list/${ingredientId}`, {
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

  /**
   * The orders raised for one vendor that a bill could still legitimately quote — sent, part
   * received or received; never a draft the vendor has not seen and never a cancelled order.
   *
   * Its one caller is the record-an-invoice screen, where the purchase order is a dropdown of this
   * list rather than a pasted id (T-082). Kept as a wrapper of its own rather than more optional
   * arguments on `listPurchaseOrders`, because the two answer different questions: that one browses
   * every order, this one offers the orders an invoice may be attached to, and "open" is a rule the
   * server owns.
   */
  listOpenPurchaseOrdersForVendor: (vendorId: string, token?: string) =>
    request<PurchaseOrderView[]>(
      `/api/v1/purchase-orders?openOnly=true&vendorId=${encodeURIComponent(vendorId)}`,
      { method: "GET", token }
    ),

  getPurchaseOrder: (id: string, token?: string) =>
    request<PurchaseOrderDetailView>(`/api/v1/purchase-orders/${id}`, { method: "GET", token }),

  // Answers with the order's number as well as its id (T-134). The shopping list's vendor tiles
  // confirm a created order by name — "PO-2026-0041 raised for Heritage Fresh Dairy", which is what
  // Rajeev asked for in D-24 §6 — and a uuid names nothing a person can repeat. Both are required
  // and never optional: the server sends both on every 201, and a screen that read an absent
  // `poNumber` as undefined would print a confirmation with a blank where the name should be.
  createPurchaseOrder: (input: CreatePurchaseOrderInput, token?: string) =>
    request<{ id: string; poNumber: string }>("/api/v1/purchase-orders", {
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

  /**
   * Sends an order the server has already refused as late — the override on KMS-400148 (T-137,
   * D-25).
   *
   * <p>Rajeev's rule is that ordering after a vendor's agreed lead time is allowed and is not a
   * mistake: "That is a FAVOR we are asking." So the first press is refused with what it will cost
   * — a delay on this delivery cannot then be counted against the supplier — and this is the second
   * press, which means it.
   *
   * <p>**A separate function rather than an argument on `sendPurchaseOrder`.** A boolean added to
   * that signature would sit where callers already pass a token, and `sendPurchaseOrder(id, t)`
   * would go on compiling while quietly waiving a vendor's promise on every send. Two names, each
   * with one meaning, and the dangerous one has to be typed out.
   */
  sendPurchaseOrderAnyway: (id: string, token?: string) =>
    request<void>(`/api/v1/purchase-orders/${id}/send`, {
      method: "POST",
      body: JSON.stringify({ sendAnyway: true }),
      token,
    }),

  /**
   * Cancels an order, saying whether the vendor is why (T-124).
   *
   * `vendorAbandoned` is the tick box "Vendor Never Delivered this Order". It is a permanent
   * statement about a supplier — it scores that order 0% on their record and names them as a
   * no-show — so it is a required argument rather than an optional one: every caller has to say
   * which of the two kinds of cancellation this is, and false is the answer for a temple cancelling
   * for its own reasons.
   */
  cancelPurchaseOrder: (id: string, reason: string, vendorAbandoned: boolean, token?: string) =>
    request<void>(`/api/v1/purchase-orders/${id}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason, vendorAbandoned }),
      token,
    }),

  /**
   * Closes a part-delivered order, saying how it ended for the vendor (T-142, D-26).
   *
   * <p>The remainder the vendor never brought is released back to the shopping list by this call —
   * not by a write, but because the list is derived on every read and a closed order stops covering
   * its ingredients.
   *
   * <p>`outcome` and `note` are both required arguments rather than an options object with
   * defaults, and that is the same reasoning `cancelPurchaseOrder` uses for `vendorAbandoned`.
   * Every caller has to say which of the three endings this is; a default would let a screen
   * quietly decline to say anything, or put words in somebody's mouth about a supplier. Pass `null`
   * for the note on `AS_COMPUTED`, which asserts nothing and needs none.
   *
   * <p>**There is no score argument and there must never be one.** Rajeev, having proposed exactly
   * that: "Let us not let the admin adjust the score. Just show it to them."
   */
  closePurchaseOrder: (
    id: string,
    outcome: CloseOutcome,
    note: string | null,
    token?: string,
  ) =>
    request<void>(`/api/v1/purchase-orders/${id}/close`, {
      method: "POST",
      body: JSON.stringify({ outcome, note }),
      token,
    }),

  /**
   * Records that described lines on an order turned up — a status transition, never a stock
   * movement (T-066).
   *
   * <p>This is the action KMS-400129 tells a storekeeper to perform, and which existed nowhere in
   * the application until T-066: an order of nothing but described lines could never be closed and
   * sat in the vendor scorecard's aging bucket for ever.
   *
   * <p>Addressed by the order rather than by the line, though it names lines, because it may close
   * the order — the caller then reloads the order, not a line.
   */
  recordArrivals: (id: string, input: RecordArrivalsInput, token?: string) =>
    request<void>(`/api/v1/purchase-orders/${id}/arrivals`, {
      method: "POST",
      body: JSON.stringify(input),
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

  // ---- Returning received goods to the vendor (T-013). ----------------------
  // Addressed by the receipt, not by the order: a return is about one delivery, and an order may
  // take several. Behind MANAGE_INVENTORY on the server — taking stock off the books is the store
  // room's job, not the buyer's.
  listGoodsReturns: (receiptId: string, token?: string) =>
    request<GoodsReturnView[]>(`/api/v1/goods-receipts/${receiptId}/returns`, {
      method: "GET",
      token,
    }),

  returnReceivedGoods: (receiptId: string, input: ReturnGoodsInput, token?: string) =>
    request<GoodsReturnView>(`/api/v1/goods-receipts/${receiptId}/returns`, {
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

  /**
   * Strikes a bill that should never have been recorded (T-010). A POST rather than a DELETE,
   * because nothing is removed: the row stays, marked, and the URL says what happens to it — the
   * principle `voidStaffPayment` already states, followed here.
   *
   * <p>Unlike `voidStaffPayment` this one carries a reason, and so it **refuses** a second void with
   * `KMS-400132` rather than returning quietly. A body-less void is a double-click; a void carrying
   * a reason is a second act, and swallowing it would discard what the admin typed.
   */
  voidInvoice: (id: string, reason: string, token?: string) =>
    request<void>(`/api/v1/vendor-invoices/${id}/void`, {
      method: "POST",
      body: JSON.stringify({ reason }),
      token,
    }),

  /** Records a credit note against a bill that stands: it was owed, and it is now owed less. */
  creditInvoice: (
    id: string,
    input: { amount: number; reason: string },
    token?: string,
  ) =>
    request<void>(`/api/v1/vendor-invoices/${id}/credit`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

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

  /**
   * Sends this message again to the recipients it failed for, and to nobody else (T-015).
   *
   * <p>Only delivery is retried: the letter's text is frozen the moment it is sent and stays frozen,
   * so this is not a way past the draft guard. `retried` is how many recipients were re-queued —
   * a message every copy of which arrived is `KMS-400138` rather than a cheerful zero, because a
   * silent success is the exact failure this endpoint exists to fix.
   */
  retryFailedDeliveries: (id: string, token?: string) =>
    request<{ retried: number }>(`/api/v1/communications/${id}/retry`, {
      method: "POST",
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

  /**
   * Takes somebody back on (T-014). The one route out of a state that was otherwise permanent: a
   * misclick on the termination form could not be corrected at all, because ending an employment
   * also locks the record against editing.
   *
   * <p>Refused with `KMS-400135` if they never left, and with `KMS-400136` if this temple raised a
   * record against them when they did (B9) — refused rather than warned, because that record carries
   * a reason to every temple on the platform and hiring back over it quietly would make it worth
   * less everywhere.
   */
  reinstateStaff: (id: string, input: ReinstateStaffInput, token?: string) =>
    request<void>(`/api/v1/staff/members/${id}/reinstate`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /** The whole PAN. Its own request because reading it lands on the audit trail. */
  revealStaffPan: (id: string, token?: string) =>
    request<{ pan?: string }>(`/api/v1/staff/members/${id}/pan`, { method: "GET", token }),

  // ---- Conduct notes (E6-S16), behind MANAGE_STAFF_CONDUCT_NOTES. ----------
  // Its own permission, not MANAGE_STAFF, and today only a Temple Admin holds it. Note what is
  // missing and keep it missing: there is no update and no delete, because the table refuses both.
  // A note written in error is answered by adding another one.

  /** Every conduct note on one person, newest first. */
  staffConductNotes: (id: string, token?: string) =>
    request<StaffConductNoteView[]>(`/api/v1/staff/members/${id}/conduct-notes`, {
      method: "GET",
      token,
    }),

  /** Writes one, permanently, attributed to whoever is signed in. */
  addStaffConductNote: (id: string, body: string, token?: string) =>
    request<{ id: string }>(`/api/v1/staff/members/${id}/conduct-notes`, {
      method: "POST",
      body: JSON.stringify({ body }),
      token,
    }),

  // ---- Bans (B9), behind MANAGE_STAFF. -------------------------------------
  // Note what is missing and keep it missing: nothing here searches, lists or reads a record this
  // temple did not raise. The only way another temple's record reaches a screen is as a finding
  // from a hire, and that is the control the whole feature rests on.

  banCategories: (token?: string) =>
    request<BanCategoryOption[]>("/api/v1/staff/ban-categories", { method: "GET", token }),

  /** The records this temple raised — its own, and only ever its own. */
  templeBans: (token?: string) =>
    request<EmploymentBanView[]>("/api/v1/staff/bans", { method: "GET", token }),

  /** Correcting a record. Only the temple that raised it may (KMS-400027). */
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

  /**
   * Where the kitchen is short of hands, day by day (E6-S15). Behind MANAGE_STAFF_SCHEDULE, the
   * permission that already gates the grid it draws on.
   *
   * <p>One endpoint asked twice rather than two endpoints: the foot of the week grid and the
   * thirty-day list are the same question over a different range, and two shapes would be two
   * things to keep agreeing about the same Thursday.
   */
  crewCoverage: (from: string, to: string, token?: string) =>
    request<DayCoverage[]>(`/api/v1/crew-coverage?from=${from}&to=${to}`, { method: "GET", token }),

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

  /** A volunteer stepping off their OWN shift. The caller's id, always — see releaseVolunteerFromShift. */
  releaseShift: (id: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/release`, { method: "POST", token }),

  /**
   * Marks who turned up to a shift (T-016), behind `MANAGE_VOLUNTEER_SHIFTS`.
   *
   * <p>The whole roster in one call, and once: a second blanket marking is `KMS-400139`. Changing
   * an answer afterwards is `correctShiftAttendance`, one named person at a time (T-079).
   */
  recordShiftAttendance: (shiftId: string, input: ShiftAttendanceInput, token?: string) =>
    request<void>(`/api/v1/shifts/${shiftId}/attendance`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  /**
   * Changes one volunteer's attendance mark (T-079), behind `MANAGE_VOLUNTEER_SHIFTS`.
   *
   * <p>A different call from `recordShiftAttendance` rather than a second use of it, and the
   * difference is the point. That one commits a screenful of ticks in a single press, so letting it
   * run twice would let a coordinator opening an already-marked roster out of habit replace every
   * considered answer at once. This one names one volunteer and one answer, and every use of it is
   * on the temple's audit trail with who changed it and when.
   *
   * <p>It is also how somebody a partial marking left out is finally marked: `attended` may be set
   * on a signup that carries no answer yet, which is the case that had no way out at all while a
   * mark was permanent.
   *
   * <p>Idempotent — asking again for the answer the row already gives changes nothing and records
   * nothing. A shift that has not started is still refused (`KMS-400144`): a mark being correctable
   * is not a licence to write one before the shift has run.
   */
  correctShiftAttendance: (shiftId: string, userId: string, attended: boolean, token?: string) =>
    request<void>(`/api/v1/shifts/${shiftId}/attendance/${userId}`, {
      method: "PUT",
      body: JSON.stringify({ attended }),
      token,
    }),

  /**
   * The coordinator taking a named volunteer off a roster (T-016), behind
   * `MANAGE_VOLUNTEER_SHIFTS`.
   *
   * <p>Deliberately a different endpoint from `releaseShift` rather than a parameter on it.
   * `releaseShift` is the volunteer's own, on `VolunteerShiftController`, and it acts on the
   * caller's id and nobody else's — that scoping is the whole of its security and must stay exactly
   * as it is. This one names the person being removed and is gated on managing the roster.
   */
  releaseVolunteerFromShift: (
    shiftId: string,
    userId: string,
    input: RemoveVolunteerInput,
    token?: string
  ) =>
    request<void>(`/api/v1/shifts/${shiftId}/signups/${userId}/release`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  joinWaitlist: (id: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/waitlist`, { method: "POST", token }),

  leaveWaitlist: (id: string, token?: string) =>
    request<void>(`/api/v1/shifts/${id}/waitlist`, { method: "DELETE", token }),

  myShifts: (token?: string) =>
    request<MyShiftView[]>("/api/v1/my-shifts", { method: "GET", token }),

  myWaitlist: (token?: string) =>
    request<MyWaitlistView[]>("/api/v1/my-waitlist", { method: "GET", token }),

  // ---- Tenant settings (E6-S7), behind MANAGE_TEMPLE_SETTINGS. -------------
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


  /** One gift in full — the donation screen's own read (T-110), behind VIEW_DONATIONS. */
  donation: (id: string, token?: string) =>
    request<DonationDetail>(`/api/v1/donations/${id}`, { method: "GET", token }),

  /**
   * What else this donor has given.
   *
   * <p>**Every gift, whatever became of it** — the server applies no status filter here, unlike the
   * ledger list, so failed, expired and struck gifts all come back. The screen shows the good ones
   * by default and reveals the rest on a toggle, which is a filter over rows already in hand rather
   * than a second request: the toggle is then instant, and nothing has been hidden anywhere but on
   * the screen.
   *
   * <p>The rows are matched on donor account, PAN fingerprint, phone **or** email. That is a
   * likeness, not a confirmed identity, and the screen says so in as many words.
   */
  donorHistory: (donationId: string, token?: string) =>
    request<LedgerRow[]>(`/api/v1/donations/ledger/donor/${donationId}`, { method: "GET", token }),

  /**
   * Issues the 80G receipt for a gift, or hands back the one already issued.
   *
   * <p>Safe to press twice: the same document id and the same receipt number come back every time.
   * One payment, one receipt — enforced by a unique index in the database, not only by this call.
   */
  issueDonationReceipt: (donationId: string, token?: string) =>
    request<DonationReceiptIssued>(`/api/v1/donations/${donationId}/receipt`, {
      method: "POST",
      token,
    }),

  /**
   * The receipt issued for a gift, or null where none has been.
   *
   * <p>The server answers "not yet" with 204, because on most gifts that is the ordinary answer and
   * not a failure. `request` hands back undefined for an empty body, which this narrows to null.
   */
  donationReceipt: async (donationId: string, token?: string): Promise<DocumentView | null> => {
    const document = await request<DocumentView | undefined>(
      `/api/v1/donations/${donationId}/receipt`,
      { method: "GET", token }
    );
    return document ?? null;
  },

  /** The receipt itself, fetched with the token and handed to the browser — never a plain link. */
  downloadDonationReceipt: async (donationId: string, token?: string): Promise<Blob> => {
    const response = await fetch(`${BASE_URL}/api/v1/donations/${donationId}/receipt/download`, {
      method: "GET",
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) {
      throw await errorFromBinaryResponse(
        response,
        "We couldn't download that receipt.",
        "Try again in a moment."
      );
    }
    return response.blob();
  },

  /**
   * Sends the donor word that their receipt has been issued, as often as they ask.
   *
   * <p>`sent: false` is not an error — an anonymous gift, or one taken at the gate with no phone
   * number and no email address, has nobody to send anything to. It never creates a second document.
   */
  sendDonationReceipt: (donationId: string, token?: string) =>
    request<{ sent: boolean }>(`/api/v1/donations/${donationId}/receipt/send`, {
      method: "POST",
      token,
    }),

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

  /**
   * Undoes a payment recorded in error — a bounced cheque, a mistyped amount, a payment entered
   * against the wrong bill (T-010).
   *
   * <p>**`reverse` and not `void`, and the name is the point.** `invoice_payments` is append-only,
   * so there is no row to mark: the server appends a compensating negative entry, exactly as the
   * stock ledger corrects itself, and V40 designed for this in 2025 with a signed `amount` and
   * `CHECK (amount <> 0)`. A URL saying `/void` would promise a mark that the database forbids.
   *
   * <p>The invoice's paid total is recomputed in the same transaction, so an invoice that reached
   * PAID on the reversed payment drops back to PENDING. Reversing an already-reversed payment is
   * refused with `KMS-400133`.
   */
  reverseInvoicePayment: (
    invoiceId: string,
    paymentId: string,
    reason: string,
    token?: string,
  ) =>
    request<void>(`/api/v1/vendor-invoices/${invoiceId}/payments/${paymentId}/reverse`, {
      method: "POST",
      body: JSON.stringify({ reason }),
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
