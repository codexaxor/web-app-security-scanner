import type { OpenBySeverity } from "../api";

const ITEMS: [keyof OpenBySeverity, string][] = [
  ["critical", "Critical"],
  ["high", "High"],
  ["medium", "Medium"],
  ["low", "Low"],
];

/** Per-repo open-by-severity as four labeled mini severity pills (count-only, band-colored). Zero bands are
 *  dimmed, not hidden, so the row layout stays stable. `data-sev` + title give the band for tooltip and tests. */
export default function SeverityBadges({ counts }: { counts: OpenBySeverity }) {
  return (
    <span className="sevmini">
      {ITEMS.map(([k, label]) => (
        <span
          key={k}
          className={`pill pill-${k}${counts[k] === 0 ? " is-zero" : ""}`}
          data-sev={k}
          title={`${label}: ${counts[k]}`}
        >
          {counts[k]}
        </span>
      ))}
    </span>
  );
}
