import { Link } from "react-router";
import Icon from "./icons";

export interface Crumb {
  label: string;
  to?: string;
}

/**
 * Breadcrumb trail for detail pages (M8.11 item 3). The last crumb is the current page (marked
 * aria-current="page", never a link); earlier crumbs with a `to` are links. Labels are plain text — repo paths
 * and group titles are rendered as text, never HTML (OD-13d).
 */
export default function Breadcrumbs({ items }: { items: Crumb[] }) {
  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      <ol>
        {items.map((c, i) => {
          const last = i === items.length - 1;
          return (
            <li key={i}>
              {c.to && !last ? (
                <Link to={c.to}>{c.label}</Link>
              ) : (
                <span aria-current={last ? "page" : undefined}>{c.label}</span>
              )}
              {!last ? <Icon name="chevronRight" className="breadcrumb-sep" /> : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
