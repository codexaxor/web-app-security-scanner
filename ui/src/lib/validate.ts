// N-8: validate route ids and enum URL params BEFORE they are used to build an API path. An invalid id or an
// unknown enum value renders a not-found page and fires NO request (rather than letting the server 400/404 on a
// value our own routing produced).

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** True for a canonical UUID string. Used to guard `:groupId` and the `repositoryId` filter before fetching. */
export function isUuid(s: string | null | undefined): boolean {
  return s != null && UUID_RE.test(s);
}

/** Narrow a URL param to one of `allowed`, or null if it is absent/blank; a present-but-unknown value is invalid. */
export function oneOf<T extends string>(
  value: string | null | undefined,
  allowed: readonly T[],
): { ok: true; value: T | undefined } | { ok: false } {
  if (value == null || value === "") {
    return { ok: true, value: undefined };
  }
  return (allowed as readonly string[]).includes(value) ? { ok: true, value: value as T } : { ok: false };
}
