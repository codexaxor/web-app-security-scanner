import type { ReactElement } from "react";

// Inline SVG icons (D-066: no icon library). Each icon is a stroke-based 24-unit glyph that inherits the
// current text color and scales to the font size via `1em`. Decorative by default (aria-hidden); pass a
// `title` only when the icon is the sole label for an action.

export type IconName =
  | "dashboard"
  | "feed"
  | "repositories"
  | "ignored"
  | "settings"
  | "connect"
  | "menu"
  | "dots"
  | "search"
  | "close"
  | "chevronLeft"
  | "chevronRight"
  | "scan"
  | "external"
  | "sca"
  | "sast"
  | "secret"
  | "iac"
  | "eol";

// Each entry is the inner markup of a 24x24 viewBox, stroke = currentColor.
const PATHS: Record<IconName, ReactElement> = {
  dashboard: (
    <>
      <rect x="3" y="3" width="7" height="9" rx="1" />
      <rect x="14" y="3" width="7" height="5" rx="1" />
      <rect x="14" y="12" width="7" height="9" rx="1" />
      <rect x="3" y="16" width="7" height="5" rx="1" />
    </>
  ),
  feed: (
    <>
      <line x1="4" y1="6" x2="20" y2="6" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="18" x2="14" y2="18" />
    </>
  ),
  repositories: (
    <>
      <path d="M4 5a1 1 0 0 1 1-1h9l2 2h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5Z" />
    </>
  ),
  ignored: (
    <>
      <path d="M3 3l18 18" />
      <path d="M10.6 5.1A9.7 9.7 0 0 1 12 5c5 0 9 4.5 9 7a11 11 0 0 1-2.2 3.1" />
      <path d="M6.2 6.2C3.9 7.7 3 9.9 3 11c0 2 3.5 6 9 6 1 0 2-.2 2.9-.5" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
    </>
  ),
  settings: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2.5l1.4 2.6 2.9-.5.6 2.9 2.6 1.4-1.3 2.6 1.3 2.6-2.6 1.4-.6 2.9-2.9-.5L12 21.5l-1.4-2.6-2.9.5-.6-2.9-2.6-1.4 1.3-2.6-1.3-2.6 2.6-1.4.6-2.9 2.9.5L12 2.5Z" />
    </>
  ),
  connect: (
    <>
      <path d="M8 13l-2 2a3 3 0 0 1-4-4l3-3a3 3 0 0 1 4 0" />
      <path d="M16 11l2-2a3 3 0 0 1 4 4l-3 3a3 3 0 0 1-4 0" />
      <line x1="9" y1="15" x2="15" y2="9" />
    </>
  ),
  menu: (
    <>
      <line x1="4" y1="7" x2="20" y2="7" />
      <line x1="4" y1="12" x2="20" y2="12" />
      <line x1="4" y1="17" x2="20" y2="17" />
    </>
  ),
  dots: (
    <>
      <circle cx="12" cy="5" r="1.4" />
      <circle cx="12" cy="12" r="1.4" />
      <circle cx="12" cy="19" r="1.4" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <line x1="16.5" y1="16.5" x2="21" y2="21" />
    </>
  ),
  close: (
    <>
      <line x1="5" y1="5" x2="19" y2="19" />
      <line x1="19" y1="5" x2="5" y2="19" />
    </>
  ),
  chevronLeft: <polyline points="15 5 8 12 15 19" />,
  chevronRight: <polyline points="9 5 16 12 9 19" />,
  scan: (
    <>
      <path d="M4 8V6a2 2 0 0 1 2-2h2" />
      <path d="M16 4h2a2 2 0 0 1 2 2v2" />
      <path d="M20 16v2a2 2 0 0 1-2 2h-2" />
      <path d="M8 20H6a2 2 0 0 1-2-2v-2" />
      <line x1="4" y1="12" x2="20" y2="12" />
    </>
  ),
  external: (
    <>
      <path d="M14 4h6v6" />
      <path d="M20 4l-9 9" />
      <path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" />
    </>
  ),
  sca: (
    <>
      <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3Z" />
      <path d="M4 7.5l8 4.5 8-4.5" />
      <line x1="12" y1="12" x2="12" y2="21" />
    </>
  ),
  sast: (
    <>
      <polyline points="9 8 5 12 9 16" />
      <polyline points="15 8 19 12 15 16" />
    </>
  ),
  secret: (
    <>
      <circle cx="8" cy="12" r="4" />
      <line x1="11.5" y1="12" x2="21" y2="12" />
      <line x1="17" y1="12" x2="17" y2="16" />
      <line x1="20" y1="12" x2="20" y2="15" />
    </>
  ),
  iac: (
    <>
      <path d="M3 9l9-5 9 5-9 5-9-5Z" />
      <path d="M3 9v6l9 5 9-5V9" />
    </>
  ),
  eol: (
    <>
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7 12 12 16 14" />
    </>
  ),
};

export default function Icon({
  name,
  title,
  className,
}: {
  name: IconName;
  title?: string;
  className?: string;
}) {
  return (
    <svg
      className={["icon", className ?? ""].filter(Boolean).join(" ")}
      viewBox="0 0 24 24"
      width="1em"
      height="1em"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
      focusable="false"
    >
      {PATHS[name]}
    </svg>
  );
}
