import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router";
import { useAuth } from "../auth";
import { useTheme, type Theme } from "../theme";
import SignInGate from "./SignInGate";
import Button from "./Button";
import Icon, { type IconName } from "./icons";

const THEME_OPTS: [Theme, string][] = [
  ["light", "Light"],
  ["dark", "Dark"],
  ["system", "System"],
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

// D-084 / OD-25: the app shell is a left sidebar. Accessible names are the full labels (kept even when the
// drawer collapses), so role/name selectors stay stable across breakpoints.
const NAV: { to: string; label: string; icon: IconName }[] = [
  { to: "/dashboard", label: "Dashboard", icon: "dashboard" },
  { to: "/feed", label: "Feed", icon: "feed" },
  { to: "/repositories", label: "Repositories", icon: "repositories" },
  { to: "/ignored", label: "Ignored", icon: "ignored" },
  { to: "/settings", label: "Settings", icon: "settings" },
  { to: "/connect", label: "Sources", icon: "connect" },
];

/**
 * The app shell. When not signed in, the sign-in gate renders IN PLACE — the URL is untouched, so signing in
 * reveals the route the person asked for (post-refresh route restore). When signed in: a left sidebar with the
 * SecScan wordmark, primary nav with a clear active state, and a footer with the theme switch, the signed-in
 * user and Sign out. Below ~1024 px a top app-bar appears and the sidebar becomes an off-canvas drawer toggled
 * by the menu button (Esc or the scrim closes it, navigating closes it); the shell stays keyboard navigable.
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
          <span className="brand-mark" aria-hidden="true" />
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
          <span className="brand-mark" aria-hidden="true" />
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
          <span className="brand-mark" aria-hidden="true" />
          <span className="brand-name">SecScan</span>
        </div>
        <nav className="sidenav" aria-label="Primary">
          {NAV.map((n) => (
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
        </nav>
        <div className="sidebar-footer">
          <ThemeControl />
          <div className="sidebar-user">
            <span className="muted truncate" title={username ?? undefined}>
              {username ?? ""}
            </span>
            <Button variant="ghost" size="sm" onClick={signOut}>
              Sign out
            </Button>
          </div>
        </div>
      </aside>
      <main className="content" id="main">
        <Outlet />
      </main>
    </div>
  );
}
