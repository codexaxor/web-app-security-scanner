// M8.10 (D-093/D-094/D-098): the sign-up and operator API client. A NEW module, not yet wired into api.ts /
// routes (that is S11, after the M8.11 merge). It follows api.ts's rules: session cookie only (same-origin),
// never an Authorization header, the CSRF token echoed on unsafe requests, and server messages shown as plain
// text. Because api.ts keeps its CSRF token and request() private, unsafe operator calls read the token from
// /auth/session (fetchSession), which also refreshes api.ts's in-memory copy. S11 may fold this into api.ts.
import { ApiError, AuthRequiredError, fetchSession, type Paged } from "../api";

export interface SignUpRequest {
  email: string;
  password: string;
  workspaceName: string;
}

/** The single answer to every well-formed sign-up (no account enumeration, D-098). */
export interface SignUpAccepted {
  status: "RECEIVED";
  message: string;
}

export type SignupStatus = "pending" | "approved" | "rejected";

export interface SignupRequestView {
  id: string;
  email: string;
  workspaceName: string;
  status: SignupStatus;
  createdAt: string;
  decidedAt: string | null;
  workspaceId: string | null;
}

export type AccountStatus = "active" | "disabled";

export interface UserAdminView {
  id: string;
  email: string;
  status: AccountStatus;
  workspaceName: string;
  operator: boolean;
  createdAt: string;
  statusChangedAt: string | null;
}

/** A 429 from sign-up or login: how long to wait (seconds), from Retry-After. */
export class RateLimitedError extends ApiError {
  constructor(
    message: string,
    readonly retryAfterSeconds: number | null,
  ) {
    super(message, "RATE_LIMITED");
  }
}

/** Client-side mirror of the server's sign-up validation (the server stays authoritative). */
export const MIN_PASSWORD_CHARS = 12;
export const MAX_PASSWORD_BYTES = 72;
export const MAX_WORKSPACE_NAME = 80;

export function validateSignUp(req: SignUpRequest): Partial<Record<keyof SignUpRequest, string>> {
  const errors: Partial<Record<keyof SignUpRequest, string>> = {};
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(req.email.trim())) {
    errors.email = "Enter a valid email address.";
  }
  if ([...req.password].length < MIN_PASSWORD_CHARS) {
    errors.password = `Use at least ${MIN_PASSWORD_CHARS} characters.`;
  } else if (new TextEncoder().encode(req.password).length > MAX_PASSWORD_BYTES) {
    errors.password = `Use at most ${MAX_PASSWORD_BYTES} bytes.`;
  }
  const name = req.workspaceName.trim();
  if (name.length < 1 || name.length > MAX_WORKSPACE_NAME || /[\u0000-\u001f\u007f]/.test(name)) {
    errors.workspaceName = `Name your workspace (1–${MAX_WORKSPACE_NAME} characters).`;
  }
  return errors;
}

function retryAfter(res: Response): number | null {
  const v = res.headers.get("Retry-After");
  const n = v === null ? NaN : Number(v);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

async function errorFrom(res: Response): Promise<ApiError> {
  let data: { error?: string; message?: string } | null = null;
  try {
    data = (await res.json()) as { error?: string; message?: string };
  } catch {
    data = null;
  }
  const message = data?.message ?? `Request failed (${res.status})`;
  if (res.status === 429) {
    return new RateLimitedError(message, retryAfter(res));
  }
  return new ApiError(message, data?.error ?? null);
}

/** Public sign-up (no session needed, CSRF-exempt on the server). Always resolves the same 202 body. */
export async function signUp(req: SignUpRequest): Promise<SignUpAccepted> {
  const res = await fetch("/api/v1/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ ...req, email: req.email.trim(), workspaceName: req.workspaceName.trim() }),
  });
  if (res.status !== 202) {
    throw await errorFrom(res);
  }
  return (await res.json()) as SignUpAccepted;
}

/** What a failed login means for the user (S11 maps api.ts's login() onto this). */
export type LoginOutcome = "wrong-credentials" | "pending" | "inactive" | "rate-limited" | "error";

export function loginOutcome(status: number, code: string | null): LoginOutcome {
  if (status === 401) return "wrong-credentials";
  if (status === 403 && code === "ACCOUNT_PENDING") return "pending";
  if (status === 403 && code === "ACCOUNT_INACTIVE") return "inactive";
  if (status === 429) return "rate-limited";
  return "error";
}

async function operatorCall<T>(method: "GET" | "POST", path: string): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (method !== "GET") {
    const session = await fetchSession();
    if (!session) {
      throw new AuthRequiredError("Authentication required");
    }
    headers["X-CSRF-TOKEN"] = session.csrfToken;
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: method === "GET" ? undefined : "{}",
  });
  if (res.status === 401) {
    throw new AuthRequiredError("Authentication required");
  }
  if (!res.ok) {
    throw await errorFrom(res);
  }
  return (await res.json()) as T;
}

/** The operator API (`/api/v1/admin/**`, operator-only; D-094: account metadata only). */
export const operatorApi = {
  listSignUps: (status: SignupStatus, page: number, size: number) =>
    operatorCall<Paged<SignupRequestView>>(
      "GET",
      `/api/v1/admin/sign-ups?status=${encodeURIComponent(status)}&page=${page}&size=${size}`,
    ),
  approveSignUp: (id: string) =>
    operatorCall<SignupRequestView>("POST", `/api/v1/admin/sign-ups/${encodeURIComponent(id)}/approve`),
  rejectSignUp: (id: string) =>
    operatorCall<SignupRequestView>("POST", `/api/v1/admin/sign-ups/${encodeURIComponent(id)}/reject`),
  listUsers: (status: AccountStatus | null, page: number, size: number) =>
    operatorCall<Paged<UserAdminView>>(
      "GET",
      `/api/v1/admin/users?${status ? `status=${encodeURIComponent(status)}&` : ""}page=${page}&size=${size}`,
    ),
  disableUser: (id: string) =>
    operatorCall<UserAdminView>("POST", `/api/v1/admin/users/${encodeURIComponent(id)}/disable`),
  enableUser: (id: string) =>
    operatorCall<UserAdminView>("POST", `/api/v1/admin/users/${encodeURIComponent(id)}/enable`),
};
