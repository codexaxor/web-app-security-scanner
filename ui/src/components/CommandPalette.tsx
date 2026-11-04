import { useEffect, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { useNavigate } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
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

const REPO_PAGE_SIZE = 6;

type Item = { key: string; label: string; sublabel?: string; to: string; icon: IconName };

/**
 * ⌘K / Ctrl+K quick-jump palette (M8.11 item 3). A modal dialog (role="dialog", aria-modal) with a focus trap:
 * focus opens on the input and Tab is kept inside; Esc closes; Up/Down move the selection; Enter navigates.
 * Results are pages (filtered client-side over the fixed list) + repositories (server-side search, first page).
 */
export default function CommandPalette() {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);

  // Global shortcut toggles the palette.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && (e.key === "k" || e.key === "K")) {
        e.preventDefault();
        setOpen((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (open) {
      setQ("");
      setActive(0);
      inputRef.current?.focus();
    }
  }, [open]);

  const search = q.trim();
  const repoQuery = useQuery({
    queryKey: qk.repositories({ palette: true, search: search || null }),
    queryFn: () => api.listRepositories({ search: search || undefined, page: 0, size: REPO_PAGE_SIZE }),
    enabled: open,
  });

  const items: Item[] = useMemo(() => {
    const ql = search.toLowerCase();
    const pages: Item[] = PAGES.filter((p) => !ql || p.label.toLowerCase().includes(ql)).map((p) => ({
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
  }, [search, repoQuery.data]);

  const activeIndex = items.length ? Math.min(active, items.length - 1) : 0;

  function go(item?: Item) {
    const target = item ?? items[activeIndex];
    if (!target) return;
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
            placeholder="Jump to a page or repository…"
            aria-label="Quick jump search"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setActive(0);
            }}
          />
          <kbd className="palette-kbd">Esc</kbd>
        </div>
        <ul className="palette-list" role="listbox" aria-label="Results">
          {items.length === 0 ? (
            <li className="palette-empty">No matches</li>
          ) : (
            items.map((it, i) => (
              <li key={it.key}>
                <button
                  type="button"
                  tabIndex={-1}
                  className={i === activeIndex ? "palette-item active" : "palette-item"}
                  aria-selected={i === activeIndex}
                  role="option"
                  onMouseMove={() => setActive(i)}
                  onClick={() => go(it)}
                >
                  <Icon name={it.icon} className="palette-item-icon" />
                  <span className="palette-item-label truncate">{it.label}</span>
                  {it.sublabel ? <span className="palette-item-sub truncate">{it.sublabel}</span> : null}
                </button>
              </li>
            ))
          )}
        </ul>
      </div>
    </div>
  );
}
