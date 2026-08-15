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

  constructor(payload: ErrorPayload) {
    super(payload.message);
    this.name = "ApiError";
    this.code = payload.code;
    this.action = payload.action;
    this.fieldErrors = payload.fieldErrors ?? [];
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

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

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

  throw new ApiError(payload);
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
export type PrincipalRole = "SUPER_ADMIN" | "TEMPLE_ADMIN" | "KITCHEN_STAFF" | "VOLUNTEER";
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

export interface AddUserInput {
  fullName: string;
  email: string;
  phone: string;
  role: UserRole;
  preferredChannel?: NotificationChannel;
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
  batchId: string;
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

export interface DonationView {
  id: string;
  type: string;
  donorName: string | null;
  anonymous: boolean;
  donatedOn: string;
  estimatedValueInr: number | null;
  ingredientCount: number;
  equipmentCount: number;
  acknowledged: boolean;
  notes: string | null;
  createdAt: string;
}

export interface RecordInKindDonationInput {
  anonymous: boolean;
  donorName?: string | null;
  donorPhone?: string | null;
  donorEmail?: string | null;
  estimatedValueInr?: number | null;
  donatedOn: string;
  notes?: string | null;
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
  targetServings: number;
  dayType: DayType;
  occasionName: string | null;
  status: MealStatus;
  clientName: string | null;
  clientContact: string | null;
  venue: string | null;
  cookedAt: string | null;
  ekadashiAcknowledged: boolean;
  createdAt: string;
}

/**
 * The temple's morning screen (E4-S8), in one payload.
 *
 * <p>Nullable fields say "not yours" rather than "none": kitchen staff hold neither VIEW_DONATIONS
 * nor MANAGE_VENDOR_PAYMENTS, so `giving` is absent for them. A zero would read as "nobody gave
 * anything this month", which is a different and wrong statement.
 */
export interface TodayView {
  date: string;
  calendar: TodayCalendarNote | null;
  meals: TodayMeal[];
  platesToday: number;
  itemsBelowThreshold: number;
  /** How many consumables the temple tracks at all — 0 below par means nothing when this is 0 too. */
  itemsTracked: number;
  unfilledShiftSpots: number;
  /** Shifts posted for today and tomorrow, for the same reason. */
  shiftsAhead: number;
  nextUnfilledShift: string | null;
  giving: { monthToDate: number; since: string } | null;
  deliveries: TodayDelivery[];
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

export interface TodayMeal {
  id: string;
  mealKind: string;
  /** "HH:mm:ss" — the order the kitchen works in. */
  readyBy: string;
  recipeName: string;
  targetServings: number;
  status: MealStatus;
  occasionName: string | null;
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
  targetServings: number;
  /** "HH:mm". Optional only for a kind that carries a default time. */
  readyBy?: string | null;
  clientName?: string | null;
  clientContact?: string | null;
  venue?: string | null;
  ekadashiAcknowledged?: boolean;
}

export interface MealKindInput {
  name: string;
  sortOrder: number;
  defaultReadyTime: string | null;
  needsClient: boolean;
  needsVenue: boolean;
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

/** What marking a meal cooked drew from stock (E3-S6). */
export interface ConsumptionPlan {
  recipeName: string;
  targetYield: number;
  sufficient: boolean;
  lines: { ingredientName: string; required: number; unit: string }[];
  shortfalls: { ingredientName: string; required: number; available: number; unit: string }[];
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

export interface StaffProfileView {
  id: string;
  userId: string;
  fullName: string;
  designation: string | null;
  active: boolean;
  createdAt: string;
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
  fromException: boolean;
}

export interface StaffWeek {
  staffProfileId: string;
  userId: string;
  fullName: string;
  designation: string | null;
  days: ResolvedDay[];
}

export interface WeekScheduleView {
  weekStart: string;
  staff: StaffWeek[];
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
}

export interface DonorInput {
  anonymous: boolean;
  name?: string;
  phone?: string;
  email?: string;
  address?: string;
  pan?: string;
  wants80g: boolean;
  consent: boolean;
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
  sponsoredQuantity: number;
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

export interface LedgerSummary {
  financialYearStart: string;
  monthToDateByCategory: Record<string, number>;
  financialYearToDateByCategory: Record<string, number>;
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
function exportFilename(response: Response, slug: string): string {
  const header = response.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (encoded) return decodeURIComponent(encoded[1]);
  const plain = /filename="([^"]+)"/i.exec(header);
  return plain ? plain[1] : `${slug}-ikms-data-export.xlsx`;
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
  // Who the backend understands the caller to be — role and tenant come from our own user record,
  // not the token. A 401 here means a valid Firebase identity with no account at a temple yet.
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
  listUsers: (token?: string) =>
    request<UserSummary[]>("/api/v1/users", { method: "GET", token }),

  addUser: (input: AddUserInput, token?: string) =>
    request<{ id: string }>("/api/v1/users", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

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

  archiveRecipe: (id: string, token?: string) =>
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

  // Donations (E3-S5). Recording is MANAGE_INVENTORY; reading is VIEW_DONATIONS.
  recordInKindDonation: (input: RecordInKindDonationInput, token?: string) =>
    request<{ id: string }>("/api/v1/donations/in-kind", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  listDonations: (token?: string) =>
    request<DonationView[]>("/api/v1/donations", { method: "GET", token }),

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

  cancelMealPlan: (id: string, token?: string) =>
    request<void>(`/api/v1/meal-plans/${id}/cancel`, { method: "POST", token }),

  markMealCooked: (
    id: string,
    input: { batchOverrides?: unknown[]; note?: string | null } = {},
    token?: string
  ) =>
    request<ConsumptionPlan>(`/api/v1/meal-plans/${id}/cooked`, {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  mealSufficiency: (from: string, to: string, token?: string) =>
    request<MealSufficiency[]>(`/api/v1/meal-plans/sufficiency?from=${from}&to=${to}`, {
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

  // ---- Staff schedule (E6-S1), behind MANAGE_STAFF_SCHEDULE. ---------------
  listStaffProfiles: (token?: string) =>
    request<StaffProfileView[]>("/api/v1/staff/profiles", { method: "GET", token }),

  createStaffProfile: (input: { userId: string; designation?: string | null }, token?: string) =>
    request<{ id: string }>("/api/v1/staff/profiles", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  getStaffProfile: (id: string, token?: string) =>
    request<StaffProfileDetailView>(`/api/v1/staff/profiles/${id}`, { method: "GET", token }),

  updateStaffProfile: (
    id: string,
    input: { designation?: string | null; active: boolean },
    token?: string
  ) =>
    request<void>(`/api/v1/staff/profiles/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
      token,
    }),

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

  myStaffSchedule: (token?: string) =>
    request<StaffProfileDetailView>("/api/v1/staff/schedule/me", { method: "GET", token }),

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

  duplicateShift: (id: string, shiftDate: string, token?: string) =>
    request<{ id: string }>(`/api/v1/shifts/${id}/duplicate`, {
      method: "POST",
      body: JSON.stringify({ shiftDate }),
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

  // ---- Public donation surface (E7-S1/S2/S6), unauthenticated, tenant by slug. ----
  donationPage: (slug: string) =>
    request<DonationPageInfo>(`/api/v1/public/t/${slug}/donation-page`, { method: "GET" }),

  donate: (slug: string, amountInr: number, donor: DonorInput) =>
    request<DonationCheckout>(`/api/v1/public/t/${slug}/donations`, {
      method: "POST",
      body: JSON.stringify({ amountInr, ...donor }),
    }),

  publicWishlist: (slug: string) =>
    request<WishlistItemView[]>(`/api/v1/public/t/${slug}/wishlist`, { method: "GET" }),

  sponsor: (slug: string, itemId: string, quantity: number, donor: DonorInput) =>
    request<DonationCheckout>(`/api/v1/public/t/${slug}/wishlist/${itemId}/sponsor`, {
      method: "POST",
      body: JSON.stringify({ quantity, ...donor }),
    }),

  wishlistSponsors: (slug: string, itemId: string) =>
    request<string[]>(`/api/v1/public/t/${slug}/wishlist/${itemId}/sponsors`, { method: "GET" }),

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

  donationSummary: (token?: string) =>
    request<LedgerSummary>("/api/v1/donations/ledger/summary", { method: "GET", token }),

  ledgerExportUrl: (): string => `${BASE_URL}/api/v1/donations/ledger/export`,

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

  // ---- Recurring donation self-service (E7-S3), authenticated donor. ----
  myRecurringPlans: (token?: string) =>
    request<RecurringPlanView[]>("/api/v1/donations/recurring", { method: "GET", token }),

  cancelRecurringPlan: (id: string, token?: string) =>
    request<void>(`/api/v1/donations/recurring/${id}/cancel`, { method: "POST", token }),
};
