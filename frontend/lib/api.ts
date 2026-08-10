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
};
