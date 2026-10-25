import { exactTime, relativeTime } from "../lib/format";

/**
 * Relative time with the exact local timestamp (browser locale + timezone) as a hover tooltip. Used everywhere
 * a relative time is shown so the wording and the tooltip are identical across screens (S10a). Renders a
 * semantic <time>; absent/invalid input shows "—" with no tooltip.
 */
export default function RelativeTime({ iso }: { iso: string | null | undefined }) {
  const title = exactTime(iso);
  return (
    <time dateTime={iso ?? undefined} title={title ?? undefined}>
      {relativeTime(iso)}
    </time>
  );
}
