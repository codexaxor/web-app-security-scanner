// M8.13: sanitize the `?next=` parameter used after sign-in (and when a signed-out visitor is bounced to
// /login from a protected route). The destination must be a SAME-ORIGIN path: it has to start with a single
// "/", never "//" or "/\" (protocol-relative / backslash tricks browsers normalize to another origin), carry no
// control characters, and resolve to the same origin. Anything else — an absolute URL, a scheme, a bare word —
// falls back to the app home. This is the only gate against an open redirect, so it is deliberately strict and
// unit-tested against the known tricks.
export const DEFAULT_NEXT = "/dashboard";

export function safeNextPath(raw: string | null | undefined, fallback: string = DEFAULT_NEXT): string {
  if (typeof raw !== "string" || raw.length === 0) {
    return fallback;
  }
  // Must begin with exactly one "/" and NOT a second "/" or a "\" (these resolve to another origin).
  if (!/^\/(?![/\\])/.test(raw)) {
    return fallback;
  }
  // No backslashes anywhere, no control characters or whitespace that could smuggle a second target.
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f\\\s]/.test(raw)) {
    return fallback;
  }
  // Belt and suspenders: resolve against a sentinel origin and confirm it did not escape it.
  try {
    const resolved = new URL(raw, "http://secscan.invalid");
    if (resolved.origin !== "http://secscan.invalid") {
      return fallback;
    }
  } catch {
    return fallback;
  }
  return raw;
}
