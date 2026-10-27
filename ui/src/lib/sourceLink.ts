// Source-host deep links (D-084 / OD-26 amendment to D-067). Links to a file line or a commit on the source
// host are built ONLY by this code, ONLY from: the stored connection host, the stored project path, a
// 40-hex scan commit SHA, a repo-relative path (URL-encoded per segment) and an integer line — never from
// finding text or scanner output. Any input that fails validation yields null, so the UI renders plain text
// instead of a link. M8.6 covers GitLab shapes (including a self-managed host with a non-default port); the
// GitHub shapes arrive in M8.7.

// hostname, optionally :port — no scheme, no slash, no userinfo (`@`), no path. This also rejects a value that
// tries to smuggle a different host (e.g. "good.com@evil.com" or "evil.com/x").
const HOST_RE = /^[a-z0-9.-]+(:\d{1,5})?$/i;
// A GitLab project path with namespace: slash-separated segments of url-safe chars; no "..", no empty segment.
const PROJECT_SEGMENT_RE = /^[A-Za-z0-9._-]+$/;
const SHA_RE = /^[0-9a-f]{40}$/i;
// A scheme prefix such as javascript: or data: at the start of a path — rejected outright.
const SCHEME_RE = /^[a-z][a-z0-9+.-]*:/i;

function validHost(host: string | null | undefined): host is string {
  return !!host && HOST_RE.test(host);
}

function validProjectPath(path: string | null | undefined): path is string {
  if (!path) return false;
  const segs = path.split("/");
  return segs.length > 0 && segs.every((s) => PROJECT_SEGMENT_RE.test(s) && s !== ".." && s !== ".");
}

function validSha(sha: string | null | undefined): sha is string {
  return !!sha && SHA_RE.test(sha);
}

/** Validate a repo-relative file path and return it URL-encoded per segment, or null if it is unsafe. */
function encodeFilePath(filePath: string | null | undefined): string | null {
  if (!filePath) return null;
  if (filePath.startsWith("/")) return null; // absolute
  if (SCHEME_RE.test(filePath)) return null; // javascript:/data:/… scheme
  const segs = filePath.split("/");
  if (segs.some((s) => s === "" || s === "." || s === "..")) return null;
  return segs.map((s) => encodeURIComponent(s)).join("/");
}

export interface SourceRef {
  host: string | null | undefined;
  projectPath: string | null | undefined;
  commitSha: string | null | undefined;
  // "gitlab" (default) or "github" — selects the URL shape (M8.7 approval 16). Same validation either way.
  provider?: string | null;
}

// GitLab puts repository routes under `/-/`; GitHub does not. The infixes are the only shape difference.
function blobInfix(provider: string | null | undefined): string {
  return provider === "github" ? "/blob/" : "/-/blob/";
}
function commitInfix(provider: string | null | undefined): string {
  return provider === "github" ? "/commit/" : "/-/commit/";
}

/**
 * File-at-commit link, or null when any input is invalid.
 * GitLab: `https://<host>/<project>/-/blob/<sha>/<encoded path>#L<line>`.
 * GitHub: `https://<host>/<owner/name>/blob/<sha>/<encoded path>#L<line>`.
 */
export function sourceFileLink(
  ref: SourceRef,
  filePath: string | null | undefined,
  line: number | null | undefined,
): string | null {
  if (!validHost(ref.host) || !validProjectPath(ref.projectPath) || !validSha(ref.commitSha)) return null;
  const encoded = encodeFilePath(filePath);
  if (encoded === null) return null;
  const lineFrag = typeof line === "number" && Number.isInteger(line) && line >= 1 ? `#L${line}` : "";
  return `https://${ref.host}/${ref.projectPath}${blobInfix(ref.provider)}${ref.commitSha.toLowerCase()}/${encoded}${lineFrag}`;
}

/**
 * Commit link, or null when any input is invalid. GitLab: `…/-/commit/<sha>`; GitHub: `…/commit/<sha>`.
 */
export function sourceCommitLink(ref: SourceRef): string | null {
  if (!validHost(ref.host) || !validProjectPath(ref.projectPath) || !validSha(ref.commitSha)) return null;
  return `https://${ref.host}/${ref.projectPath}${commitInfix(ref.provider)}${ref.commitSha.toLowerCase()}`;
}
