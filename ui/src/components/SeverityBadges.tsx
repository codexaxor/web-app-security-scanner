import type { OpenBySeverity } from "../api";

const ITEMS: [keyof OpenBySeverity, string, string][] = [
  ["critical", "C", "Critical"],
  ["high", "H", "High"],
  ["medium", "M", "Medium"],
  ["low", "L", "Low"],
];

/** Per-repo open-by-severity as one compact, labeled, non-wrapping line: "C 1 · H 2 · M 2 · L 0" (band-colored
 *  letters; zero counts muted). The count sits in its own `data-sev` span so it reads as just the number. */
export default function SeverityBadges({ counts }: { counts: OpenBySeverity }) {
  return (
    <span className="sevmini">
      {ITEMS.map(([k, letter, label]) => (
        <span key={k} className={`sevmini-item sev-${k}${counts[k] === 0 ? " is-zero" : ""}`} title={`${label}: ${counts[k]}`}>
          <span className="sevmini-letter">{letter}</span>
          <span data-sev={k}>{counts[k]}</span>
        </span>
      ))}
    </span>
  );
}
