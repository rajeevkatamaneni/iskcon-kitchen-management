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
  status: string;
  created_at: string;
  user_count: number;
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

export interface OpsTenant {
  id: string;
  name: string;
  slug: string;
  status: string;
}

export interface HealthStatus {
  /** "UP" when healthy, "DOWN" otherwise. */
  status: string;
  /** "UP" / "DOWN". */
  db: string;
  /** RUNNING, STANDBY, ABSENT (not on this instance), or ERROR. */
  scheduler: string;
  timestamp: string;
}

export interface TenantOps {
  tenantId: string;
  tenantName: string;
  sentToday: number;
  failedToday: number;
  suppressedToday: number;
  recentFailures: {
    id: string;
    recipientLabel: string;
    template: string;
    failedAt: string;
  }[];
  /** Null until the calendar engine exists (E4). */
  lastCalendarPrecompute: string | null;
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
  recipeId: string;
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

export interface Equipment {
  id: string;
  name: string;
  category: string;
  storageLocation: string | null;
  condition: string;
  acquisitionDate: string | null;
  source: string | null;
  notes: string | null;
  createdAt: string;
}

export interface EquipmentStateChange {
  id: string;
  fromCondition: string | null;
  toCondition: string;
  reason: string;
  actorUserId: string;
  actorName: string | null;
  createdAt: string;
}

export interface EquipmentDetail {
  equipment: Equipment;
  history: EquipmentStateChange[];
}

export interface CreateEquipmentInput {
  name: string;
  category: string;
  storageLocation?: string | null;
  condition?: string | null;
  acquisitionDate?: string | null;
  source?: string | null;
  notes?: string | null;
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

export const api = {
  // Who the backend understands the caller to be — role and tenant come from our own user record,
  // not the token. A 401 here means a valid Firebase identity with no account at a temple yet.
  whoami: (token?: string) =>
    request<WhoAmI>("/api/v1/whoami", { method: "GET", token }),

  listTenants: (token?: string) =>
    request<TenantSummary[]>("/api/v1/tenants", { method: "GET", token }),

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

  // Super-Admin ops (VIEW_PLATFORM_OPERATIONS). Aggregate platform metrics live in Cloud
  // Monitoring; these are the in-app per-temple operational drill-in.
  opsTenants: (token?: string) =>
    request<OpsTenant[]>("/api/v1/ops/tenants", { method: "GET", token }),

  tenantOps: (tenantId: string, token?: string) =>
    request<TenantOps>(`/api/v1/ops/tenants/${tenantId}`, { method: "GET", token }),

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
      throw new ApiError({
        code: "KMS-0000",
        message: "We couldn't download that file.",
        action: "Try again in a moment.",
        fieldErrors: [],
      });
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

  // Equipment register (E3-S4).
  listEquipment: (
    filters: { includeScrapped?: boolean; category?: string; location?: string } = {},
    token?: string
  ) => {
    const params = new URLSearchParams();
    if (filters.includeScrapped) params.set("includeScrapped", "true");
    if (filters.category) params.set("category", filters.category);
    if (filters.location) params.set("location", filters.location);
    const query = params.toString();
    return request<Equipment[]>(`/api/v1/equipment${query ? `?${query}` : ""}`, {
      method: "GET",
      token,
    });
  },

  getEquipment: (id: string, token?: string) =>
    request<EquipmentDetail>(`/api/v1/equipment/${id}`, { method: "GET", token }),

  createEquipment: (input: CreateEquipmentInput, token?: string) =>
    request<{ id: string }>("/api/v1/equipment", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),

  changeEquipmentCondition: (id: string, condition: string, reason: string, token?: string) =>
    request<void>(`/api/v1/equipment/${id}/condition`, {
      method: "POST",
      body: JSON.stringify({ condition, reason }),
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
};
