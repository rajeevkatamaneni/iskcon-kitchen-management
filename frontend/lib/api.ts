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

export const api = {
  listTenants: (token?: string) =>
    request<TenantSummary[]>("/api/v1/tenants", { method: "GET", token }),

  provisionTenant: (input: ProvisionTenantInput, token?: string) =>
    request<{ id: string; slug: string }>("/api/v1/tenants", {
      method: "POST",
      body: JSON.stringify(input),
      token,
    }),
};
