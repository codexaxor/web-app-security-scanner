import type { ReactNode } from "react";
import { Link } from "react-router";
import type { BandName } from "../lib/format";

// Small presentational primitives grouped in one module: Card, PageHeader, EmptyState, InfoTip, SeverityBar,
// Legend. Each is plain and style-only (classes from components.css).

type Bands = Record<BandName, number>;

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={["card", className ?? ""].filter(Boolean).join(" ")}>{children}</div>;
}

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle ? <div className="subtitle">{subtitle}</div> : null}
      </div>
      {actions ? <div className="actions">{actions}</div> : null}
    </header>
  );
}

/** A scan's own status as a colored chip (Completed green, Partial amber, Failed red, else neutral). Distinct
 *  from StatusChip, which is for issue status (New/To Do/Ignored/Solved). */
export function ScanStatusChip({ status }: { status: string }) {
  const cls =
    status === "completed"
      ? "chip-solved"
      : status === "partial"
        ? "chip-warn"
        : status === "failed"
          ? "chip-bad"
          : "chip-todo";
  const label = status ? status.charAt(0).toUpperCase() + status.slice(1) : "—";
  return <span className={`chip ${cls}`}>{label}</span>;
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty-title">{title}</div>
      {children ? <div>{children}</div> : null}
    </div>
  );
}

/** A title-based tooltip affordance: a small "?" that reveals `text` on hover/focus (native title), also exposed
 *  to assistive tech via aria-label. CSP-safe — no JS popovers. */
export function InfoTip({ text }: { text: string }) {
  return (
    <span className="tip" title={text} aria-label={text} role="img" tabIndex={0}>
      ?
    </span>
  );
}

const ORDER: BandName[] = ["critical", "high", "medium", "low"];
const BAND_LABEL: Record<BandName, string> = {
  critical: "critical",
  high: "high",
  medium: "medium",
  low: "low",
};

/** The segmented severity proportion bar (widths are data, hence inline — CSP-safe via CSSOM). */
export function SeverityBar({ bands }: { bands: Bands }) {
  const total = ORDER.reduce((n, k) => n + bands[k], 0) || 1;
  return (
    <div className="sevbar" aria-hidden="true">
      {ORDER.map((k) => (
        <span key={k} className={`seg-${k}`} style={{ width: `${(bands[k] / total) * 100}%` }} />
      ))}
    </div>
  );
}

/** Band legend. Each item's visible text begins with the count (e.g. "12 critical") so existing count oracles
 *  read the leading number; `prefix` makes the data-testids (`band-critical`, `group-critical`, …). When
 *  `linkTo` is given each item is a link to the pre-filtered Feed. */
export function Legend({
  bands,
  prefix,
  linkTo,
}: {
  bands: Bands;
  prefix: string;
  linkTo?: (band: BandName) => string;
}) {
  return (
    <div className="legend">
      {ORDER.map((k) =>
        linkTo ? (
          <Link key={k} className="legend-item" data-testid={`${prefix}-${k}`} to={linkTo(k)}>
            <span className={`legend-dot seg-${k}`} /> {bands[k]} {BAND_LABEL[k]}
          </Link>
        ) : (
          <span key={k} className="legend-item" data-testid={`${prefix}-${k}`}>
            <span className={`legend-dot seg-${k}`} /> {bands[k]} {BAND_LABEL[k]}
          </span>
        ),
      )}
    </div>
  );
}
