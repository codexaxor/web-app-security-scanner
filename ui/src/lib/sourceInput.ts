/**
 * Parses what the user pastes into the single Sources input (M8.8 item 7): a full browser URL or a bare path.
 * It is a CONVENIENCE for showing "what was detected" before submit — the server keeps the authoritative
 * validation (host allowlist, existence, scopes). Detection:
 *   - github.com → GitHub (1 segment = owner, ≥2 = owner/repo)
 *   - gitlab.com → GitLab SaaS; any other https host → self-managed GitLab (the server's allowlist is the gate)
 *   - a GitLab project is recognized by a "/-/…" suffix or ≥2 path segments; 1 segment is a group
 * It strips a trailing ".git", a "/-/tree|blob|…" suffix, and leading/trailing slashes, and rejects a non-https
 * URL. GitHub Enterprise Server hosts are not supported yet.
 */
export type SourceProvider = "gitlab" | "github";
export type SourceKind = "group" | "project" | "owner" | "repo";

export interface ParsedSource {
  provider: SourceProvider;
  host: string; // authority (host[:port])
  builtinHost: boolean; // github.com or gitlab.com
  path: string; // namespace/repo path — no scheme, no leading/trailing slash, no ".git", no "/-/" suffix
  kind: SourceKind;
}

export interface ParseError {
  error: string;
}

export function isParseError(r: ParsedSource | ParseError): r is ParseError {
  return (r as ParseError).error !== undefined;
}

function cleanPath(pathname: string): string {
  let p = pathname.replace(/^\/+/, "").replace(/\/+$/, "");
  const dash = p.indexOf("/-/"); // GitLab sub-routes: /-/tree, /-/blob, /-/merge_requests, …
  if (dash >= 0) {
    p = p.slice(0, dash);
  }
  p = p.replace(/\.git$/i, "").replace(/\/+$/, "");
  return p;
}

function gitlabKind(hadDashSuffix: boolean, segments: number): SourceKind {
  // A "/-/…" suffix is a definitive project marker; otherwise a single segment is a group, more is a project.
  return hadDashSuffix || segments >= 2 ? "project" : "group";
}

/** Parses a pasted full URL. Returns a {@link ParseError} for a non-URL / non-https / path-less input. */
export function parseSourceUrl(raw: string): ParsedSource | ParseError {
  const s = (raw ?? "").trim();
  if (!s) {
    return { error: "Enter a repository or group URL." };
  }
  const scheme = s.match(/^([a-zA-Z][\w+.-]*):\/\//);
  if (!scheme) {
    return { error: "Enter a full https URL." };
  }
  if (scheme[1].toLowerCase() !== "https") {
    return { error: "Use an https URL." };
  }
  let url: URL;
  try {
    url = new URL(s);
  } catch {
    return { error: "That is not a valid URL." };
  }
  const hadDash = url.pathname.includes("/-/");
  const path = cleanPath(url.pathname);
  if (!path) {
    return { error: "The URL has no group or repository path." };
  }
  const hostLc = url.host.toLowerCase();
  const segments = path.split("/").filter(Boolean);

  if (hostLc === "github.com") {
    return {
      provider: "github",
      host: "github.com",
      builtinHost: true,
      path: segments.length >= 2 ? segments.slice(0, 2).join("/") : segments[0],
      kind: segments.length >= 2 ? "repo" : "owner",
    };
  }
  // Everything else is GitLab: gitlab.com (built-in) or a self-managed host (allowlist enforced server-side).
  return {
    provider: "gitlab",
    host: url.host,
    builtinHost: hostLc === "gitlab.com",
    path,
    kind: gitlabKind(hadDash, segments.length),
  };
}

/** The kind for a bare path (no host) once the provider is known from the wizard steps. */
export function kindForPath(provider: SourceProvider, path: string): SourceKind {
  const segments = cleanPath("/" + path)
    .split("/")
    .filter(Boolean);
  if (provider === "github") {
    return segments.length >= 2 ? "repo" : "owner";
  }
  return gitlabKind(path.includes("/-/"), segments.length);
}
