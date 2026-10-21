import type { OpenBySeverity } from "../api";

/** The per-repo / dashboard open-by-severity counts as four coloured pills. Zero bands are dimmed, not hidden,
 *  so the row layout stays stable. */
export default function SeverityBadges({ counts }: { counts: OpenBySeverity }) {
  const items: [string, number, string][] = [
    ["Critical", counts.critical, "sev-critical"],
    ["High", counts.high, "sev-high"],
    ["Medium", counts.medium, "sev-medium"],
    ["Low", counts.low, "sev-low"],
  ];
  return (
    <span className="row" style={{ gap: "0.3rem" }}>
      {items.map(([label, n, cls]) => (
        <span
          key={label}
          className={`badge ${cls}`}
          style={{ opacity: n === 0 ? 0.35 : 1 }}
          title={`${label}: ${n}`}
          data-sev={label.toLowerCase()}
        >
          {n}
        </span>
      ))}
    </span>
  );
}
