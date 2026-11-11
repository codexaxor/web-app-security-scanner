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
  provider: string;
  host: string | null;
  credentialKind: string;
  targetType: string;
  baseUrl: string;
  gitlabGroupId: number | null;
  groupFullPath: string | null;
  gitlabProjectId: number | null;
  projectFullPath: string | null;
  ownerLogin: string | null;
  repoFullName: string | null;
  tokenScopes: string[];
  tokenExpiresAt: string | null;
}

export interface SourceRepoView {
  providerRepoId: string;
  fullName: string;
  defaultBranch: string | null;
  visibility: string | null;
  empty: boolean;
  notSelectableReason: string | null;
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

export interface OpenBySeverity {
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface LastScan {
  id: string;
  status: string;
  finishedAt: string | null;
}

export interface RepositoryView {
  id: string;
  gitlabProjectId: number | null;
  provider: string;
  host: string | null;
  providerRepoId: string;
  pathWithNamespace: string;
  defaultBranch: string | null;
  scanBranch: string | null;
  effectiveBranch: string | null;
  branchMissing: boolean;
  languages: string[];
  active: boolean;
  sensitivity: string;
  includeDevDeps: boolean;
  connectionId: string | null;
  connectionTargetType: TargetType | null;
  connectionLabel: string | null;
  inactiveReason: string | null;
  openBySeverity: OpenBySeverity;
  ignoredCount: number;
  lastScan: LastScan | null;
  warningCount: number;
}

export type Band = "critical" | "high" | "medium" | "low";
export type View = "focus" | "all";
export type IssueType = "sca" | "sast" | "secret" | "iac" | "eol";

export interface IssueGroupView {
  id: string;
  repositoryId: string;
  repositoryPath: string;
  repositoryBranch: string | null;
  type: IssueType;
  title: string;
  subtitle: string | null;
  level2Category: string | null;
  owaspCategory: string | null;
  status: string;
  severityScore: number | null;
  fixTimeMinutes: number | null;
  firstSeenAt: string;
  openCount: number;
  ignoredCount: number;
  solvedCount: number;
  fixedVersions: string[];
  primaryLocation: string | null;
  locationCount: number;
}

export interface FeedSummaryView {
  bands: { critical: number; high: number; medium: number; low: number };
  totalGroups: number;
  newFindings: number;
  solved: number;
  autoIgnored: number;
}

export interface ScoreLine {
  code: string;
  detail: string;
  delta: number;
}

export interface SubIssueView {
  id: string;
  groupId: string;
  repositoryId: string;
  type: IssueType;
  scanner: string;
  status: string;
  vulnId: string | null;
  ruleId: string | null;
  packageName: string | null;
  installedVersion: string | null;
  fixedVersion: string | null;
  filePath: string | null;
  line: number | null;
  secretPreview: string | null;
  toolSeverity: string | null;
  ignoreReason: string | null;
  score: number | null;
  band: string | null;
  scoreBreakdown: ScoreLine[] | null;
  cvss: string | null;
  severityLabel: string | null;
  cwe: string[] | null;
  cwePhrase: string | null;
  inKev: boolean | null;
  epss: number | null;
  epssAttribution: string | null;
  firstSeenScanId: string | null;
  lastSeenScanId: string | null;
  solvedAt: string | null;
  createdAt: string | null;
  ignoreRuleId: string | null;
  ruleScope: string | null;
  ruleReason: string | null;
  ruleCreatedBy: string | null;
  ruleGlobal: boolean;
  level2Category: string | null;
  owaspCategory: string | null;
  advisorySummary: string | null;
  host: string | null;
  commitSha: string | null;
  provider: string | null;
}

export interface TriageEventView {
  id: string;
  fromStatus: string | null;
  toStatus: string | null;
  actor: string;
  reasonCode: string | null;
  scanId: string | null;
  branch: string | null;
  at: string;
}

export interface ScanView {
  id: string;
  repositoryId: string;
  trigger: string;
  status: string;
  commitSha: string | null;
  branch: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string | null;
  warningCount: number;
}

export interface ScanTaskView {
  scanner: string;
  status: string;
  exitCode: number | null;
  durationMs: number | null;
}

export interface ScanDetailView {
  scan: ScanView;
  toolVersions: Record<string, string>;
  tasks: ScanTaskView[];
  findingCount: number;
}

export interface CategoryCount {
  category: string;
  count: number;
}

export interface BranchView {
  name: string;
  isDefault: boolean;
  isProtected: boolean;
  lastCommitAt: string | null;
}

// The branch-picker page carries searchTruncated (M8.8 S5b): true when a provider search could only scan a
// bounded prefix of the branches (GitHub has no server-side branch search).
export interface BranchPage extends Paged<BranchView> {
  searchTruncated: boolean;
}

export interface SelectResult {
  added: number;
  reactivated: number;
  alreadySelected: number;
  repositories: RepositoryView[];
}

export interface ScanWarningView {
  id: string;
  scanId: string;
  code: string;
  message: string;
  filePath: string | null;
  createdAt: string | null;
}

export interface IgnoredItemView {
  subIssueId: string;
  groupId: string;
  type: IssueType;
  scanner: string;
  vulnId: string | null;
  ruleId: string | null;
  packageName: string | null;
  filePath: string | null;
  line: number | null;
  reasonCode: string | null;
  ignoreRuleId: string | null;
  ruleScope: string | null;
  ruleReason: string | null;
  createdBy: string | null;
  ignoredAt: string | null;
}

export interface IgnoreRuleView {
  id: string;
  scope: string;
  repositoryId: string | null;
  matchValue: string;
  reason: string;
  createdBy: string;
  createdAt: string | null;
}

export interface DashboardSummaryView {
  groupBands: { critical: number; high: number; medium: number; low: number };
  totalGroups: number;
  findingBands: { critical: number; high: number; medium: number; low: number };
  totalFindings: number;
  byType: { type: string; count: number }[];
  scanHealth: {
    unhealthyRepoCount: number;
    failedPartialCount: number;
    withWarningsCount: number;
    repos: { repositoryPath: string; lastScanStatus: string; warningCount: number }[];
  };
  inactive: { inactiveRepoCount: number; openFindingsInInactiveRepos: number };
}

export interface WorkspaceSettings {
  epssAutoIgnoreEnabled: boolean;
}

export type Provider = "gitlab" | "github";

export interface FeedParams {
  repositoryId?: string;
  type?: IssueType;
  category?: string;
  search?: string;
  provider?: Provider;
  view?: View;
  band?: Band;
  includeInactive?: boolean;
  page?: number;
  size?: number;
}

export type RepoSort = "severity" | "name" | "lastscan";

export interface RepoListParams {
  search?: string;
  includeInactive?: boolean;
  hasOpenIssues?: boolean;
  lastScanFailed?: boolean;
  provider?: Provider;
  sort?: RepoSort;
  page?: number;
  size?: number;
}

export interface RepositorySettingsPatch {
  sensitivity?: string;
  includeDevDeps?: boolean;
  active?: boolean;
  // D-076: omit to leave unchanged; a string sets the scan branch; explicit null resets to the GitLab default.
  scanBranch?: string | null;
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

/** A 429 (login, sign-up or any API call): how long to wait, from Retry-After (seconds), when the server says. */
export class RateLimitedError extends ApiError {
  constructor(
    message: string,
    readonly retryAfterSeconds: number | null,
  ) {
    super(message, "RATE_LIMITED");
  }
}

/** M8.10 login: the password was right but the sign-up is still waiting for the operator (403 ACCOUNT_PENDING). */
export class AccountPendingError extends ApiError {
  constructor() {
    super("This account is waiting for approval.", "ACCOUNT_PENDING");
  }
}

/** M8.10 login: the password was right but the account is disabled (403 ACCOUNT_INACTIVE). */
export class AccountInactiveError extends ApiError {
  constructor() {
    super("This account is not active.", "ACCOUNT_INACTIVE");
  }
}

function retryAfterSeconds(res: Response): number | null {
  const v = res.headers.get("Retry-After");
  const n = v === null ? NaN : Number(v);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

async function errorBody(res: Response): Promise<{ message?: string; error?: string } | null> {
  try {
    const text = await res.text();
    return text ? (JSON.parse(text) as { message?: string; error?: string }) : null;
  } catch {
    return null;
  }
}

// D-092: the browser authenticates with a server session cookie (HttpOnly, set by the server at /auth/login),
// never an Authorization header — the SPA never constructs one. The only credential material the SPA holds is
// the CSRF token (a session synchronizer token returned by /auth/login and /auth/session), kept in memory and
// echoed on unsafe requests via X-CSRF-TOKEN. The password is never stored, and nothing goes to web storage.
export interface SessionInfo {
  username: string;
  demoMode: boolean;
  csrfToken: string;
  /** M8.10: the signed-in user's own workspace (shown in the user menu). */
  workspaceName: string;
  /** M8.10 (D-094): the platform operator — only they see the Operator section; the API enforces it. */
  operator: boolean;
}

let csrfToken: string | null = null;

const UNSAFE = new Set(["POST", "PUT", "PATCH", "DELETE"]);

// SF-3: a single unauthorized handler invoked by request() on ANY 401 — so direct (non-react-query) calls drop
// to the sign-in gate too, not only queries/mutations. auth.tsx registers it to clear session state + the query
// cache. Default no-op so api.ts stays usable in isolation (e.g. unit tests).
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

/** Signs in against the server session (JSON). Stores the CSRF token in memory; never retains the password. */
export async function login(username: string, password: string): Promise<SessionInfo> {
  const res = await fetch("/api/v1/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ username, password }),
  });
  if (res.status === 401) {
    throw new AuthRequiredError("Wrong email or password");
  }
  if (res.status === 429) {
    throw new RateLimitedError("Too many sign-in attempts.", retryAfterSeconds(res));
  }
  if (res.status === 403) {
    // M8.10: a 403 only ever follows a CORRECT password (wrong passwords are 401), so this reveals nothing to
    // someone who does not hold the password.
    const code = (await errorBody(res))?.error ?? null;
    if (code === "ACCOUNT_PENDING") throw new AccountPendingError();
    if (code === "ACCOUNT_INACTIVE") throw new AccountInactiveError();
  }
  if (!res.ok) {
    throw new ApiError(`Sign-in failed (${res.status})`, null);
  }
  const info = (await res.json()) as SessionInfo;
  csrfToken = info.csrfToken;
  return info;
}

/** M8.10 sign-up request (D-093). */
export interface SignUpRequest {
  email: string;
  password: string;
  workspaceName: string;
}

/** The single answer to every well-formed sign-up — the same whether or not the email is known (D-098). */
export interface SignUpAccepted {
  status: "RECEIVED";
  message: string;
}

/**
 * Public self sign-up (no session; CSRF-exempt on the server, so no token is sent). Always resolves the same
 * 202 body for a well-formed request; 400 is a format error only; 429 carries Retry-After.
 */
export async function signUp(req: SignUpRequest): Promise<SignUpAccepted> {
  const res = await fetch("/api/v1/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ ...req, email: req.email.trim(), workspaceName: req.workspaceName.trim() }),
  });
  if (res.status === 202) {
    return (await res.json()) as SignUpAccepted;
  }
  const data = await errorBody(res);
  if (res.status === 429) {
    throw new RateLimitedError(data?.message ?? "Too many sign-up attempts.", retryAfterSeconds(res));
  }
  throw new ApiError(data?.message ?? `Sign-up failed (${res.status})`, data?.error ?? null);
}

/** Reads the current session on load (a refresh stays signed in). Returns null when not signed in (401). */
export async function fetchSession(): Promise<SessionInfo | null> {
  const res = await fetch("/api/v1/auth/session", { credentials: "same-origin" });
  if (res.status === 401) {
    return null;
  }
  if (!res.ok) {
    throw new ApiError(`Could not read the session (${res.status})`, null);
  }
  const info = (await res.json()) as SessionInfo;
  csrfToken = info.csrfToken;
  return info;
}

/** Drops the in-memory CSRF token (nit 9.6: called from the auth layer on a 401 / sign-out). */
export function resetSession(): void {
  csrfToken = null;
}

/**
 * Ends the server session. Throws an {@link ApiError} if the server does not return 204 — the caller keeps the
 * user signed in and surfaces the error (nit 9.7). On success the in-memory CSRF token is cleared.
 */
export async function logout(): Promise<void> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (csrfToken) {
    headers["X-CSRF-TOKEN"] = csrfToken;
  }
  const res = await fetch("/api/v1/auth/logout", {
    method: "POST",
    headers,
    credentials: "same-origin",
    body: "{}",
  });
  if (res.status !== 204) {
    throw new ApiError(`Sign-out failed (${res.status})`, null);
  }
  csrfToken = null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  // The session cookie is sent automatically (same-origin); unsafe requests also carry the CSRF token.
  if (UNSAFE.has(method) && csrfToken) {
    headers["X-CSRF-TOKEN"] = csrfToken;
  }
  const res = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 401) {
    // Fire on EVERY 401, before throwing, so direct callers (not just react-query) drop to sign-in (SF-3).
    onUnauthorized();
    throw new AuthRequiredError("Authentication required");
  }
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  // N-4: a non-JSON body (e.g. an nginx HTML 502) must not blow up with a raw SyntaxError — fall back to null so
  // the error banner shows a clean "Request failed (<status>)".
  let data: { message?: string; error?: string } | null = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    const message = data?.message ?? `Request failed (${res.status})`;
    if (res.status === 429) {
      throw new RateLimitedError(message, retryAfterSeconds(res));
    }
    throw new ApiError(message, data?.error ?? null);
  }
  return data as T;
}

// --- M8.10 operator API (D-094: account metadata only; /api/v1/admin/** is operator-only on the server) ---
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

  // --- M8.7 Sources (GitHub + public) ---
  addGitHubOwner: (ownerLogin: string, token: string) =>
    request<ConnectionView>("POST", "/api/v1/sources/github/owner", { ownerLogin, token }),
  addGitHubRepo: (fullName: string, token: string) =>
    request<ConnectionView>("POST", "/api/v1/sources/github/repo", { fullName, token }),
  addPublicRepo: (url: string) =>
    request<ConnectionView>("POST", "/api/v1/sources/public", { url }),
  listSourceRepositories: (connectionId: string, search: string, page: number, size: number) =>
    request<Paged<SourceRepoView>>(
      "GET",
      `/api/v1/sources/connections/${connectionId}/repositories?search=${encodeURIComponent(search)}&page=${page}&size=${size}`,
    ),
  selectSourceRepositories: (connectionId: string, providerRepoIds: string[]) =>
    request<RepositoryView[]>("POST", `/api/v1/sources/connections/${connectionId}/select`, { providerRepoIds }),
  listProjects: (connectionId: string, page: number, size: number) =>
    request<Paged<ProjectView>>(
      "GET",
      `/api/v1/gitlab/connections/${connectionId}/projects?page=${page}&size=${size}`,
    ),
  selectRepositories: (connectionId: string, gitlabProjectIds: number[]) =>
    request<SelectResult>("POST", "/api/v1/repositories", { connectionId, gitlabProjectIds }),
  listRepositories: (p: RepoListParams = {}) =>
    request<Paged<RepositoryView>>("GET", `/api/v1/repositories${repoQuery(p)}`),
  getRepository: (id: string) => request<RepositoryView>("GET", `/api/v1/repositories/${id}`),
  listRepositoryScans: (id: string, page: number, size: number) =>
    request<Paged<ScanView>>("GET", `/api/v1/repositories/${id}/scans?page=${page}&size=${size}`),
  listRepositoryBranches: (repositoryId: string, search: string, page: number, size: number) =>
    request<BranchPage>(
      "GET",
      `/api/v1/repositories/${repositoryId}/branches?search=${encodeURIComponent(search)}&page=${page}&size=${size}`,
    ),

  // --- M8 ---
  patchRepository: (id: string, patch: RepositorySettingsPatch) =>
    request<RepositoryView>("PATCH", `/api/v1/repositories/${id}`, patch),
  startScan: (repositoryId: string) =>
    request<ScanView>("POST", `/api/v1/repositories/${repositoryId}/scans`),
  getScan: (scanId: string) => request<ScanDetailView>("GET", `/api/v1/scans/${scanId}`),
  listScanWarnings: (scanId: string, page: number, size: number) =>
    request<Paged<ScanWarningView>>("GET", `/api/v1/scans/${scanId}/warnings?page=${page}&size=${size}`),
  listScansAll: (page: number, size: number) =>
    request<Paged<ScanView>>("GET", `/api/v1/scans?page=${page}&size=${size}`),

  listFeed: (p: FeedParams) =>
    request<Paged<IssueGroupView>>("GET", `/api/v1/issue-groups${feedQuery(p)}`),
  feedSummary: (p: FeedParams) =>
    request<FeedSummaryView>("GET", `/api/v1/issue-groups/summary${feedQuery(p, true)}`),
  listIssueCategories: (repositoryId: string | undefined, view: View) =>
    request<CategoryCount[]>(
      "GET",
      `/api/v1/issue-groups/categories?view=${view}${repositoryId ? `&repositoryId=${repositoryId}` : ""}`,
    ),
  getGroup: (groupId: string) => request<IssueGroupView>("GET", `/api/v1/issue-groups/${groupId}`),
  listSubIssues: (groupId: string, page: number, size: number) =>
    request<Paged<SubIssueView>>(
      "GET",
      `/api/v1/issue-groups/${groupId}/sub-issues?page=${page}&size=${size}`,
    ),
  listSubIssueEvents: (subIssueId: string, page: number, size: number) =>
    request<Paged<TriageEventView>>(
      "GET",
      `/api/v1/sub-issues/${subIssueId}/events?page=${page}&size=${size}`,
    ),

  listIgnored: (page: number, size: number, reason?: string) =>
    request<Paged<IgnoredItemView>>(
      "GET",
      `/api/v1/ignored?page=${page}&size=${size}${reason ? `&reason=${encodeURIComponent(reason)}` : ""}`,
    ),
  listIgnoreRules: (page: number, size: number) =>
    request<Paged<IgnoreRuleView>>("GET", `/api/v1/ignore-rules?page=${page}&size=${size}`),
  createIgnoreRule: (body: { scope: string; repositoryId?: string | null; matchValue: string; reason: string }) =>
    request<IgnoreRuleView>("POST", "/api/v1/ignore-rules", body),
  ignoreSubIssue: (subIssueId: string, reason: string) =>
    request<IgnoreRuleView>("POST", `/api/v1/sub-issues/${subIssueId}/ignore`, { reason }),
  ignoreGroup: (groupId: string, reason: string) =>
    request<IgnoreRuleView>("POST", `/api/v1/issue-groups/${groupId}/ignore`, { reason }),
  deleteIgnoreRule: (ruleId: string) =>
    request<void>("DELETE", `/api/v1/ignore-rules/${ruleId}`),

  dashboardSummary: (includeInactive: boolean) =>
    request<DashboardSummaryView>("GET", `/api/v1/dashboard/summary?includeInactive=${includeInactive}`),

  getWorkspaceSettings: () => request<WorkspaceSettings>("GET", "/api/v1/workspace/settings"),
  putWorkspaceSettings: (body: WorkspaceSettings) =>
    request<WorkspaceSettings>("PUT", "/api/v1/workspace/settings", body),

  // --- M8.10 operator (the session's CSRF token is echoed by request() on the POSTs) ---
  listSignUps: (status: SignupStatus, page: number, size: number) =>
    request<Paged<SignupRequestView>>(
      "GET",
      `/api/v1/admin/sign-ups?status=${encodeURIComponent(status)}&page=${page}&size=${size}`,
    ),
  approveSignUp: (id: string) =>
    request<SignupRequestView>("POST", `/api/v1/admin/sign-ups/${encodeURIComponent(id)}/approve`, {}),
  rejectSignUp: (id: string) =>
    request<SignupRequestView>("POST", `/api/v1/admin/sign-ups/${encodeURIComponent(id)}/reject`, {}),
  listUsers: (status: AccountStatus | null, page: number, size: number) =>
    request<Paged<UserAdminView>>(
      "GET",
      `/api/v1/admin/users?${status ? `status=${encodeURIComponent(status)}&` : ""}page=${page}&size=${size}`,
    ),
  disableUser: (id: string) =>
    request<UserAdminView>("POST", `/api/v1/admin/users/${encodeURIComponent(id)}/disable`, {}),
  enableUser: (id: string) =>
    request<UserAdminView>("POST", `/api/v1/admin/users/${encodeURIComponent(id)}/enable`, {}),
};

// Builds the repositories-list query string from a validated params object. `search` is user text, so it is
// URL-encoded by URLSearchParams; the sort value is a closed enum validated server-side.
function repoQuery(p: RepoListParams): string {
  const q = new URLSearchParams();
  if (p.search) q.set("search", p.search);
  if (p.includeInactive) q.set("includeInactive", "true");
  if (p.hasOpenIssues) q.set("hasOpenIssues", "true");
  if (p.lastScanFailed) q.set("lastScanFailed", "true");
  if (p.provider) q.set("provider", p.provider);
  if (p.sort) q.set("sort", p.sort);
  q.set("page", String(p.page ?? 0));
  q.set("size", String(p.size ?? 50));
  return `?${q.toString()}`;
}

// Builds the feed/summary query string from a validated params object (never from free data). `summary` omits
// band/pagination, which the summary endpoint ignores.
function feedQuery(p: FeedParams, summary = false): string {
  const q = new URLSearchParams();
  if (p.repositoryId) q.set("repositoryId", p.repositoryId);
  if (p.type) q.set("type", p.type);
  if (p.category) q.set("category", p.category);
  if (p.search) q.set("search", p.search);
  if (p.provider) q.set("provider", p.provider);
  q.set("view", p.view ?? "focus");
  if (p.includeInactive) q.set("includeInactive", "true");
  if (!summary) {
    if (p.band) q.set("band", p.band);
    q.set("page", String(p.page ?? 0));
    q.set("size", String(p.size ?? 50));
  }
  return `?${q.toString()}`;
}
