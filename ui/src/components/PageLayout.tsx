import { type ReactNode } from "react";
import Breadcrumbs, { type Crumb } from "./Breadcrumbs";
import Icon from "./icons";

export const OPEN_PALETTE_EVENT = "secscan:open-palette";

/** The visible top-bar search affordance — a button styled as a search field that opens the ⌘K palette. */
export function PaletteSearchField() {
  return (
    <button
      type="button"
      className="topbar-search"
      aria-label="Search repositories and pages (⌘K)"
      onClick={() => window.dispatchEvent(new CustomEvent(OPEN_PALETTE_EVENT))}
    >
      <Icon name="search" className="topbar-search-icon" />
      <span className="topbar-search-text">Search repositories and pages…</span>
      <kbd className="topbar-search-kbd">⌘K</kbd>
    </button>
  );
}

/**
 * The ONE page template (S11.2) every top-level and detail page uses, so page structure is identical
 * everywhere (the Repositories page is the visual baseline):
 *   top bar (breadcrumbs left, palette search right) → page header (title/subtitle/actions)
 *   → optional KPI strip → optional toolbar (filters left, view controls right) → content (children).
 * List pages put their table in a `.table-wrap` card with the pager in its footer (`.card-footer`).
 */
export default function PageLayout({
  breadcrumbs,
  title,
  subtitle,
  actions,
  kpi,
  toolbar,
  toolbarRight,
  children,
}: {
  breadcrumbs?: Crumb[];
  title: string;
  subtitle?: ReactNode;
  actions?: ReactNode;
  kpi?: ReactNode;
  toolbar?: ReactNode;
  toolbarRight?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="page" data-testid="page-layout">
      <div className="topbar">
        <div className="topbar-left">
          {breadcrumbs && breadcrumbs.length > 0 ? (
            <Breadcrumbs items={breadcrumbs} />
          ) : (
            <span className="topbar-here">{title}</span>
          )}
        </div>
        <PaletteSearchField />
      </div>
      <header className="page-header">
        <div className="page-header-main">
          <h1>{title}</h1>
          {subtitle ? <div className="subtitle">{subtitle}</div> : null}
        </div>
        {actions ? <div className="actions">{actions}</div> : null}
      </header>
      {kpi ? <div className="kpi-strip">{kpi}</div> : null}
      {toolbar || toolbarRight ? (
        <div className="toolbar">
          <div className="toolbar-main">{toolbar}</div>
          {toolbarRight ? <div className="toolbar-right">{toolbarRight}</div> : null}
        </div>
      ) : null}
      {children}
    </section>
  );
}
