import { typeLabel, typeShort } from "../lib/labels";

/** Neutral badge showing the short type code (SCA/SAST/Secret/IaC/EOL); the full human label is the tooltip.
 *  Never severity-colored. */
export default function TypeBadge({ type }: { type: string }) {
  return (
    <span className="type-badge" title={typeLabel(type)}>
      {typeShort(type)}
    </span>
  );
}
