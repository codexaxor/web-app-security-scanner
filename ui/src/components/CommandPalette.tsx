import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { useLocation, useNavigate } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import { OPEN_PALETTE_EVENT } from "./PageLayout";
import Icon, { type IconName } from "./icons";

// Static destinations (the sidebar pages). Repositories are fetched live with the endpoint's server-side
// search (never all pages — only the first few, plus a "View all" row when there are more).
const PAGES: { label: string; to: string; icon: IconName }[] = [
  { label: "Dashboard", to: "/dashboard", icon: "dashboard" },
  { label: "Feed", to: "/feed", icon: "feed" },
  { label: "Ignored", to: "/ignored", icon: "ignored" },
  { label: "Repositories", to: "/repositories", icon: "repositories" },
  { label: "Sources", to: "/connect", icon: "connect" },
  { label: "Workspace settings", to: "/settings", icon: "settings" },
];

// M8.10: listed only for the operator (the same condition as the sidebar's Operator section).
const OPERATOR_PAGES: { label: string; to: string; icon: IconName }[] = [
  { label: "Sign-ups", to: "/admin/sign-ups", icon: "inbox" },
  { label: "Users", to: "/admin/users", icon: "users" },
];

const REPO_PAGE_SIZE = 6;
// The repository search hits the server; wait this long after the last keystroke before sending it (N-2).
// Page matches are filtered client-side on every keystroke and stay instant.
export const SEARCH_DEBOUNCE_MS = 150;

type Item = { key: string; label: string; sublabel?: string; to: string; icon: IconName };

/**
 * ⌘K / Ctrl+K quick-jump palette (M8.11 item 3). A modal dialog (role="dialog", aria-modal) with a focus trap:
 * focus opens on the input and Tab is kept inside; Esc closes; Up/Down move the selection; Enter navigates.
 * Results are pages (filtered client-side over the fixed list) + repositories (server-side search, first page).
 *
 * Accessibility (M8.11 review SF-4/SF-5): the input is an ARIA combobox that owns the listbox
 * (aria-controls) and points at the highlighted option (aria-activedescendant), so screen readers announce the
 * arrow-key selection while DOM focus stays in the input. Options are the listbox's direct children. On every
 * close (Esc, Enter, backdrop, Ctrl+K) focus returns to the element that opened the palette; if a navigation
 * removed that element, it goes to the main landmark instead of being lost on <body>.
 */
export default function CommandPalette({ operator = false }: { operator?: boolean }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const location = useLocation();
  const openerRef = useRef<HTMLElement | null>(null);
  const restorePending = useRef(false); // a close happened and focus has not been restored yet
  const navTarget = useRef<string | null>(null); // the path an Enter/click close is navigating to
  const uid = useId();
  const listId = `${uid}-list`;
  const optionId = (i: number) => `${uid}-opt-${i}`;

  // Global shortcut toggles the palette; the top-bar search field opens it via a custom event.
  useEffect(() => {
    const remember = () => {
      const el = document.activeElement;
      openerRef.current = el instanceof HTMLElement && el !== document.body ? el : null;
    };
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && (e.key === "k" || e.key === "K")) {
        e.preventDefault();
        setOpen((v) => {
          if (!v) remember();
          return !v;
        });
      }
    };
    const onOpen = () => {
      remember();
      setOpen(true);
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener(OPEN_PALETTE_EVENT, onOpen);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener(OPEN_PALETTE_EVENT, onOpen);
    };
  }, []);

  useEffect(() => {
    if (open) {
      restorePending.current = true;
      navTarget.current = null;
      setQ("");
      setActive(0);
      inputRef.current?.focus();
    }
  }, [open]);

  // Restore focus after a close. When the close navigated, wait until the router has committed the new
  // location (navigation renders after this component's own close), so we can tell whether the opener survived.
  const here = location.pathname + location.search;
  useEffect(() => {
    if (open || !restorePending.current) return;
    if (navTarget.current !== null && navTarget.current !== here) return;
    restorePending.current = false;
    navTarget.current = null;
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener && opener.isConnected) {
      opener.focus();
    } else {
      document.getElementById("main")?.focus();
    }
  }, [open, here]);

  const search = q.trim();
  const [repoSearch, setRepoSearch] = useState("");
  useEffect(() => {
    if (search === "") {
      setRepoSearch(""); // clearing (and re-opening) needs no round-trip delay
      return;
    }
    const t = setTimeout(() => setRepoSearch(search), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [search]);
  const repoQuery = useQuery({
    queryKey: qk.repositories({ palette: true, search: repoSearch || null }),
    queryFn: () => api.listRepositories({ search: repoSearch || undefined, page: 0, size: REPO_PAGE_SIZE }),
    enabled: open,
  });

  const items: Item[] = useMemo(() => {
    const ql = search.toLowerCase();
    const pages: Item[] = (operator ? [...PAGES, ...OPERATOR_PAGES] : PAGES).filter((p) => !ql || p.label.toLowerCase().includes(ql)).map((p) => ({
      key: "page:" + p.to,
      label: p.label,
      to: p.to,
      icon: p.icon,
    }));
    const repos: Item[] = (repoQuery.data?.content ?? []).map((r) => ({
      key: "repo:" + r.id,
      label: r.pathWithNamespace.split("/").filter(Boolean).pop() ?? r.pathWithNamespace,
      sublabel: r.pathWithNamespace,
      to: "/repositories/" + r.id,
      icon: "repositories",
    }));
    const total = repoQuery.data?.totalElements ?? 0;
    const more: Item[] =
      total > repos.length
        ? [
            {
              key: "more",
              label: "View all repositories",
              sublabel: `${total} total`,
              to: "/repositories" + (search ? `?search=${encodeURIComponent(search)}` : ""),
              icon: "search",
            },
          ]
        : [];
    return [...pages, ...repos, ...more];
  }, [search, repoQuery.data, operator]);

  const activeIndex = items.length ? Math.min(active, items.length - 1) : 0;

  function go(item?: Item) {
    const target = item ?? items[activeIndex];
    if (!target) return;
    navTarget.current = target.to;
    setOpen(false);
    navigate(target.to);
  }

  function onKeyDown(e: ReactKeyboardEvent<HTMLDivElement>) {
    if (e.key === "Escape") {
      e.preventDefault();
      setOpen(false);
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(a + 1, items.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      go();
    } else if (e.key === "Tab") {
      // Focus trap: the only tab stop is the input, so keep focus here.
      e.preventDefault();
      inputRef.current?.focus();
    }
  }

  if (!open) return null;

  return (
    <div className="palette-backdrop" onMouseDown={() => setOpen(false)}>
      <div
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label="Quick jump"
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <div className="palette-input-row">
          <Icon name="search" className="palette-search-icon" />
          <input
            ref={inputRef}
            className="palette-input"
            type="text"
            role="combobox"
            aria-label="Quick jump search"
            aria-autocomplete="list"
            aria-expanded={items.length > 0}
            aria-controls={listId}
            aria-activedescendant={items.length > 0 ? optionId(activeIndex) : undefined}
            placeholder="Jump to a page or repository…"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setActive(0);
            }}
          />
          <kbd className="palette-kbd">Esc</kbd>
        </div>
        <ul className="palette-list" role="listbox" id={listId} aria-label="Results">
          {items.map((it, i) => (
            <li
              key={it.key}
              id={optionId(i)}
              role="option"
              aria-selected={i === activeIndex}
              className={i === activeIndex ? "palette-item active" : "palette-item"}
              onMouseMove={() => setActive(i)}
              onClick={() => go(it)}
            >
              <Icon name={it.icon} className="palette-item-icon" />
              <span className="palette-item-label truncate">{it.label}</span>
              {it.sublabel ? <span className="palette-item-sub truncate">{it.sublabel}</span> : null}
            </li>
          ))}
        </ul>
        {items.length === 0 ? (
          <p className="palette-empty" role="status">
            No matches
          </p>
        ) : null}
      </div>
    </div>
  );
}
