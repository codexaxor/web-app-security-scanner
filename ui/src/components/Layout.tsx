import { NavLink, Outlet } from "react-router";
import { useAuth } from "../auth";
import { useTheme, type Theme } from "../theme";
import SignInGate from "./SignInGate";
import Button from "./Button";

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

const NAV = [
  { to: "/dashboard", label: "Dashboard" },
  { to: "/feed", label: "Feed" },
  { to: "/repositories", label: "Repositories" },
  { to: "/ignored", label: "Ignored" },
  { to: "/settings", label: "Settings" },
  { to: "/connect", label: "Connect GitLab" },
];

/**
 * The app shell. When not signed in, the sign-in gate renders IN PLACE — the URL is untouched, so signing in
 * reveals the route the person asked for (post-refresh route restore). When signed in: a top bar with the
 * SecScan wordmark, the nav with a clear active state, and the signed-in user + Sign out on the right.
 */
export default function Layout() {
  const { signedIn, signOut } = useAuth();

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
    <div className="app">
      <header className="topbar">
        <span className="brand">
          <span className="brand-mark" aria-hidden="true" />
          SecScan
        </span>
        <nav className="nav" aria-label="Primary">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}
            >
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="topbar-user">
          <ThemeControl />
          <span className="muted">admin</span>
          <Button variant="ghost" size="sm" onClick={signOut}>
            Sign out
          </Button>
        </div>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
