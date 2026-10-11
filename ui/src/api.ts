// Minimal API client for M1. /api is same-origin (Vite dev proxy / nginx in the container).
// Auth: M0 protects /api/** with HTTP Basic; until there is a login screen (later milestone) we keep the
// admin credentials in memory and attach them. They are never written to storage.

export interface ConnectionTestResult {
  ok: boolean;
  gitlabVersion: string | null;
  groupId: number | null;
  groupFullPath: string | null;
  tokenScopes: string[] | null;
  tokenExpiresAt: string | null;
  expiringSoon: boolean;
  errorCode: string | null;
  message: string | null;
}

export interface ConnectionView {
  baseUrl: string;
  groupFullPath: string;
  gitlabGroupId: number;
  tokenScopes: string[];
  tokenExpiresAt: string | null;
}

export interface ProjectView {
  gitlabProjectId: number;
  pathWithNamespace: string;
  defaultBranch: string | null;
  languages: string[];
  selectable: boolean;
  notSelectableReason: string | null;
  alreadySelected: boolean;
  active: boolean;
}

export interface RepositoryView {
  id: string;
  gitlabProjectId: number;
  pathWithNamespace: string;
  defaultBranch: string | null;
  languages: string[];
  active: boolean;
  sensitivity: string;
}

export interface Paged<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ConnectionRequest {
  baseUrl: string;
  token: string;
  groupPath: string;
}

export class AuthRequiredError extends Error {}
export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: string | null,
  ) {
    super(message);
  }
}

let authHeader: string | null = null;

export function setCredentials(email: string, password: string): void {
  authHeader = "Basic " + btoa(`${email}:${password}`);
}

export function clearCredentials(): void {
  authHeader = null;
}

export function hasCredentials(): boolean {
  return authHeader !== null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (authHeader) {
    headers["Authorization"] = authHeader;
  }
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) {
    throw new AuthRequiredError("Authentication required");
  }
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const message = data?.message ?? `Request failed (${res.status})`;
    throw new ApiError(message, data?.error ?? null);
  }
  return data as T;
}

export const api = {
  testConnection: (req: ConnectionRequest) =>
    request<ConnectionTestResult>("POST", "/api/v1/gitlab/connection/test", req),
  saveConnection: (req: ConnectionRequest) =>
    request<ConnectionTestResult>("POST", "/api/v1/gitlab/connection", req),
  getConnection: async (): Promise<ConnectionView | null> => {
    try {
      return await request<ConnectionView>("GET", "/api/v1/gitlab/connection");
    } catch (e) {
      if (e instanceof ApiError) return null; // 404 when none configured
      throw e;
    }
  },
  listProjects: (page: number, size: number) =>
    request<Paged<ProjectView>>("GET", `/api/v1/gitlab/projects?page=${page}&size=${size}`),
  selectRepositories: (gitlabProjectIds: number[]) =>
    request<RepositoryView[]>("POST", "/api/v1/repositories", { gitlabProjectIds }),
  listRepositories: (page: number, size: number) =>
    request<Paged<RepositoryView>>("GET", `/api/v1/repositories?page=${page}&size=${size}`),
};
