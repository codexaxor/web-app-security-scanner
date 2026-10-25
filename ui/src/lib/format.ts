// Small display helpers. All inputs are server data rendered as text (OD-13d).

/** Parse a server ISO timestamp to a Date, or null when absent/unparseable. */
function parseInstant(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null;
  }
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

const RTF = new Intl.RelativeTimeFormat(undefined, { numeric: "always" });
const ABS_DATE = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });
const EXACT = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "long" });

/**
 * A single relative-time formatter (Intl.RelativeTimeFormat, a browser built-in — no dependency). Past and
 * future both read naturally ("9 hours ago" / "in 9 hours"). Buckets: <60s → "just now"; minutes; hours; days;
 * anything older than ~30 days collapses to an absolute date (browser locale). Null/invalid → "—".
 */
export function relativeTime(iso: string | null | undefined): string {
  const d = parseInstant(iso);
  if (!d) {
    return "—";
  }
  const diffMs = Date.now() - d.getTime(); // >0 = in the past
  const absSec = Math.abs(diffMs) / 1000;
  if (absSec < 60) {
    return "just now";
  }
  const sign = diffMs >= 0 ? -1 : 1; // past → negative value → "… ago"
  if (absSec < 3600) {
    return RTF.format(sign * Math.round(absSec / 60), "minute");
  }
  if (absSec < 86400) {
    return RTF.format(sign * Math.round(absSec / 3600), "hour");
  }
  const days = absSec / 86400;
  if (days < 30) {
    return RTF.format(sign * Math.round(days), "day");
  }
  return ABS_DATE.format(d);
}

/** Exact local timestamp (browser locale, with timezone) for a title tooltip; null when absent/unparseable. */
export function exactTime(iso: string | null | undefined): string | null {
  const d = parseInstant(iso);
  return d ? EXACT.format(d) : null;
}

export function fixTime(minutes: number | null | undefined): string {
  if (minutes == null) {
    return "—";
  }
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const h = minutes / 60;
  return Number.isInteger(h) ? `${h} hr` : `${h.toFixed(1)} hr`;
}

export const BANDS = ["critical", "high", "medium", "low"] as const;
export type BandName = (typeof BANDS)[number];

/** The severity band for a 0–100 score (mirrors core Scoring.band); null → "ignored" (no band). */
export function bandOf(score: number | null | undefined): BandName | "ignored" {
  if (score == null) {
    return "ignored";
  }
  if (score >= 90) return "critical";
  if (score >= 70) return "high";
  if (score >= 40) return "medium";
  return "low";
}

export function titleCase(s: string): string {
  return s.length ? s[0].toUpperCase() + s.slice(1) : s;
}

const CVE_RE = /^CVE-\d{4}-\d{4,}$/i;
const GHSA_RE = /^GHSA-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}$/i;

/**
 * An advisory URL built by OUR code from a REGEX-VALIDATED id, never from free data (OD-13d). Returns null when
 * the id is not a recognised CVE/GHSA, so the UI shows plain text instead of a link.
 */
export function advisoryLink(id: string | null | undefined): string | null {
  if (!id) {
    return null;
  }
  if (CVE_RE.test(id)) {
    return `https://nvd.nist.gov/vuln/detail/${id.toUpperCase()}`;
  }
  if (GHSA_RE.test(id)) {
    return `https://github.com/advisories/${id.toUpperCase()}`;
  }
  return null;
}
