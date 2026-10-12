// Minimal API client for M1/M1b. /api is same-origin (Vite dev proxy / nginx in the container).
// Auth: M0 protects /api/** with HTTP Basic; until there is a login screen (later milestone) we keep the
// admin credentials in memory and attach them. They are never written to storage.

export type TargetType = "group" | "project";

export interface ConnectionTestResult {
  ok: boolean;
  targetType: TargetType | null;
  gitlabVersion: string | null;
  groupId: number | null;
  groupFullPath: string | null;
  projectId: number | null;
  projectFullPath: string | null;
  tokenScopes: string[] | null;
  tokenExpiresAt: string | null;
  expiringSoon: boolean;
  errorCode: string | null;
  message: string | null;
}

export interface ConnectionView {
  id: string;
  targetType: TargetType;
  baseUrl: string;
  gitlabGroupId: number | null;
  groupFullPath: string | null;
  gitlabProjectId: number | null;
  projectFullPath: string | null;
  tokenScopes: string[];
  tokenExpiresAt: string | null;
}

export interface ConnectionRemovalResult {
  reassigned: number;
  deactivated: number;
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
  connectionId: string | null;
  connectionTargetType: TargetType | null;
  connectionLabel: string | null;
  inactiveReason: string | null;
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
  targetType: TargetType;
  path: string;
}

export interface UpdateConnectionRequest {
  baseUrl: string;
  token: string;
  path: string;
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
    request<ConnectionTestResult>("POST", "/api/v1/gitlab/connections/test", req),
  createConnection: (req: ConnectionRequest) =>
    request<ConnectionTestResult>("POST", "/api/v1/gitlab/connections", req),
  updateConnection: (id: string, req: UpdateConnectionRequest) =>
    request<ConnectionTestResult>("PUT", `/api/v1/gitlab/connections/${id}`, req),
  listConnections: (page: number, size: number) =>
    request<Paged<ConnectionView>>("GET", `/api/v1/gitlab/connections?page=${page}&size=${size}`),
  deleteConnection: (id: string) =>
    request<ConnectionRemovalResult>("DELETE", `/api/v1/gitlab/connections/${id}`),
  listProjects: (connectionId: string, page: number, size: number) =>
    request<Paged<ProjectView>>(
      "GET",
      `/api/v1/gitlab/connections/${connectionId}/projects?page=${page}&size=${size}`,
    ),
  selectRepositories: (connectionId: string, gitlabProjectIds: number[]) =>
    request<RepositoryView[]>("POST", "/api/v1/repositories", { connectionId, gitlabProjectIds }),
  listRepositories: (page: number, size: number) =>
    request<Paged<RepositoryView>>("GET", `/api/v1/repositories?page=${page}&size=${size}`),
};
