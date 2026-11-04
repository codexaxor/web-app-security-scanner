import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router";
import { useAuth } from "../auth";
import { useTheme, type Theme } from "../theme";
import SignInGate from "./SignInGate";
import Button from "./Button";
import BrandMark from "./BrandMark";
import CommandPalette from "./CommandPalette";
import Icon, { type IconName } from "./icons";

const THEME_OPTS: [Theme, string][] = [
  ["light", "Light"],
  ["dark", "Dark"],
];

function ThemeControl() {
  const { theme, setTheme } = useTheme();
  return (
    <div className="segmented theme-control" role="group" aria-label="Theme">
      {THEME_OPTS.map(([t, label]) => (
        <button
          key={t}
          className={theme === t ? "active" : ""}
          aria-pressed={theme === t}
          data-theme-opt={t}
          onClick={() => setTheme(t)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

// D-084 / OD-25 + M8.11: the app shell is a left sidebar, now GROUPED and collapsible. Accessible names are the
// full labels (kept even when the drawer collapses), so role/name selectors stay stable across breakpoints.
// `/select`, `/repositories/:id` and `/feed/:groupId` are contextual routes reached from these items, not nav
// entries of their own.
type NavItem = { to: string; label: string; icon: IconName };
const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  { label: "Overview", items: [{ to: "/dashboard", label: "Dashboard", icon: "dashboard" }] },
  {
    label: "Findings",
    items: [
      { to: "/feed", label: "Feed", icon: "feed" },
      { to: "/ignored", label: "Ignored", icon: "ignored" },
    ],
  },
  {
    label: "Sources & repos",
    items: [
      { to: "/repositories", label: "Repositories", icon: "repositories" },
      { to: "/connect", label: "Sources", icon: "connect" },
    ],
  },
  { label: "Workspace", items: [{ to: "/settings", label: "Workspace settings", icon: "settings" }] },
];

function NavGroup({ group, pathname }: { group: (typeof NAV_GROUPS)[number]; pathname: string }) {
  const hasActive = group.items.some((i) => pathname === i.to || pathname.startsWith(i.to + "/"));
  const [open, setOpen] = useState(true);
  // Always reveal the group that holds the current route, even if the user had collapsed it.
  const expanded = open || hasActive;
  return (
    <div className="sidenav-group">
      <button
        type="button"
        className="sidenav-group-label"
        aria-expanded={expanded}
        onClick={() => setOpen((v) => !v)}
      >
        <span>{group.label}</span>
        <Icon name="chevronDown" className={expanded ? "sidenav-caret" : "sidenav-caret collapsed"} />
      </button>
      {expanded ? (
        <div className="sidenav-items">
          {group.items.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              title={n.label}
              className={({ isActive }) => (isActive ? "sidenav-link active" : "sidenav-link")}
            >
              <Icon name={n.icon} className="sidenav-icon" />
              <span className="sidenav-label">{n.label}</span>
            </NavLink>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function UserMenu({ username, onSignOut }: { username: string | null; onSignOut: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div className="user-menu" ref={ref}>
      <button
        type="button"
        className="user-menu-trigger"
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={username ? `Account: ${username}` : "Account"}
        onClick={() => setOpen((v) => !v)}
      >
        <span className="user-avatar" aria-hidden="true">
          {(username ?? "?").slice(0, 1).toUpperCase()}
        </span>
        <span className="truncate user-menu-name">{username ?? ""}</span>
        <Icon name="chevronDown" className="user-menu-caret" />
      </button>
      {open ? (
        <div className="user-menu-pop">
          <div className="user-menu-section">
            <span className="user-menu-heading">Theme</span>
            <ThemeControl />
          </div>
          <div className="user-menu-section">
            <Button variant="ghost" size="sm" onClick={onSignOut}>
              Sign out
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/**
 * The app shell. When not signed in, the sign-in gate renders IN PLACE — the URL is untouched, so signing in
 * reveals the route the person asked for (post-refresh route restore). When signed in: a deep-navy left sidebar
 * (brand chrome, constant across light/dark) with the SecScan wordmark, grouped collapsible nav with a clear
 * active state, and a user menu (theme toggle + Sign out) in the footer. Below ~1024 px a navy top app-bar
 * appears and the sidebar becomes an off-canvas drawer toggled by the menu button (Esc or the scrim closes it,
 * navigating closes it); the shell stays keyboard navigable.
 */
export default function Layout() {
  const { checking, signedIn, username, demoMode, signOut } = useAuth();
  const [navOpen, setNavOpen] = useState(false);
  const location = useLocation();

  // Close the drawer on navigation and on Esc.
  useEffect(() => setNavOpen(false), [location.pathname]);
  useEffect(() => {
    if (!navOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setNavOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [navOpen]);

  // While resuming a session on load, render nothing (not the sign-in gate) to avoid a sign-in flash on refresh.
  if (checking) {
    return <main className="shell-centered" aria-busy="true" />;
  }

  if (!signedIn) {
    return (
      <main className="shell-centered">
        <div className="signin-brand">
          <BrandMark />
          <span className="brand">SecScan</span>
        </div>
        <SignInGate />
      </main>
    );
  }

  return (
    <div className={navOpen ? "app nav-open" : "app"}>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <CommandPalette />
      {demoMode ? (
        <div className="demo-ribbon" role="status">
          DEMO MODE — do not use real credentials or private repositories
        </div>
      ) : null}
      <header className="appbar">
        <button
          type="button"
          className="appbar-menu"
          aria-label="Open navigation"
          aria-expanded={navOpen}
          aria-controls="sidebar"
          onClick={() => setNavOpen((v) => !v)}
        >
          <Icon name="menu" />
        </button>
        <span className="brand">
          <BrandMark />
          SecScan
        </span>
      </header>
      {navOpen ? (
        <button
          type="button"
          className="scrim"
          aria-label="Close navigation"
          tabIndex={-1}
          onClick={() => setNavOpen(false)}
        />
      ) : null}
      <aside className="sidebar" id="sidebar">
        <div className="sidebar-brand">
          <BrandMark />
          <span className="brand-name">SecScan</span>
        </div>
        <nav className="sidenav" aria-label="Primary">
          {NAV_GROUPS.map((g) => (
            <NavGroup key={g.label} group={g} pathname={location.pathname} />
          ))}
        </nav>
        <div className="sidebar-footer">
          <UserMenu username={username} onSignOut={signOut} />
        </div>
      </aside>
      <main className="content" id="main">
        <Outlet />
      </main>
    </div>
  );
}
