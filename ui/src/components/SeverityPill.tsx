import { bandOf, type BandName } from "../lib/format";

const BAND_LABEL: Record<BandName, string> = {
  critical: "Critical",
  high: "High",
  medium: "Medium",
  low: "Low",
};

/**
 * The one severity control: band-colored pill with the 0–100 score ("Critical 98"). A null/absent score (an
 * ignored group has no band) renders a neutral "—" pill. Severity color comes only from the band, so the Feed
 * and the side panel are always identical for the same score.
 */
export default function SeverityPill({
  score,
  showScore = true,
}: {
  score: number | null | undefined;
  showScore?: boolean;
}) {
  const band = bandOf(score);
  if (band === "ignored") {
    return (
      <span className="pill pill-none" title="No severity (ignored)">
        —
      </span>
    );
  }
  return (
    <span className={`pill pill-${band}`}>
      <span>{BAND_LABEL[band]}</span>
      {showScore && score != null ? <span className="pill-score">{score}</span> : null}
    </span>
  );
}
