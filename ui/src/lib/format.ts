// Small display helpers. All inputs are server data rendered as text (OD-13d).

export function relativeTime(iso: string | null | undefined): string {
  if (!iso) {
    return "—";
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return "—";
  }
  const secs = Math.round((Date.now() - then) / 1000);
  const abs = Math.abs(secs);
  const units: [number, string][] = [
    [60, "s"],
    [3600, "m"],
    [86400, "h"],
    [2592000, "d"],
    [31536000, "mo"],
  ];
  if (abs < 60) {
    return `${abs}s ago`;
  }
  for (let i = 1; i < units.length; i++) {
    if (abs < units[i][0]) {
      return `${Math.round(abs / units[i - 1][0])}${units[i - 1][1]} ago`;
    }
  }
  return `${Math.round(abs / 31536000)}y ago`;
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
