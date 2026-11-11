import type { OpenBySeverity } from "../api";

const ITEMS: [keyof OpenBySeverity, string][] = [
  ["critical", "Critical"],
  ["high", "High"],
  ["medium", "Medium"],
  ["low", "Low"],
];

/**
 * Severity count chips: one chip per band with its open count, band-colored when > 0 and muted (neutral) at
 * zero (design pass, item 2). Severity color comes only from the `--sev-*` tokens, so a chip is never confused
 * with a status chip. When every band is zero and `hideWhenEmpty` is set, renders a single muted "No open
 * issues" chip instead of four zeros.
 */
export default function SeverityChips({
  counts,
  hideWhenEmpty = false,
}: {
  counts: OpenBySeverity;
  hideWhenEmpty?: boolean;
}) {
  const total = counts.critical + counts.high + counts.medium + counts.low;
  if (hideWhenEmpty && total === 0) {
    return <span className="sev-chip is-zero">No open issues</span>;
  }
  return (
    <span className="sev-chips">
      {ITEMS.map(([k, label]) => (
        <span
          key={k}
          className={`sev-chip sev-${k}${counts[k] === 0 ? " is-zero" : ""}`}
          title={`${label}: ${counts[k]}`}
        >
          <span className="sev-chip-label">{label}</span>
          <span className="sev-chip-count" data-sev={k}>
            {counts[k]}
          </span>
        </span>
      ))}
    </span>
  );
}
