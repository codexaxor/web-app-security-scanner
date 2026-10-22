import { typeAcronym, typeBadge, typeLabel } from "../lib/labels";

/** Neutral badge showing the short plain type name ("Dependency", "Code", …); the full name + acronym are the
 *  tooltip. Never severity-colored; the acronym (SCA/SAST/IaC/EOL) never appears as visible text. */
export default function TypeBadge({ type }: { type: string }) {
  return (
    <span className="type-badge" title={`${typeLabel(type)} (${typeAcronym(type)})`}>
      {typeBadge(type)}
    </span>
  );
}
