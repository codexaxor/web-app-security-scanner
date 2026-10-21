import { NavLink, Outlet } from "react-router";
import { useAuth } from "../auth";
import SignInGate from "./SignInGate";

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
 * reveals the route the person asked for (post-refresh route restore). When signed in, the nav + routed page.
 */
export default function Layout() {
  const { signedIn, signOut } = useAuth();

  if (!signedIn) {
    return (
      <main className="shell shell-centered">
        <h1 className="brand">SecScan</h1>
        <SignInGate />
      </main>
    );
  }

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">SecScan</span>
        <nav className="nav">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <button className="signout" onClick={signOut}>
          Sign out
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
