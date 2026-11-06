import type { ReactNode } from "react";
import { Link } from "react-router";
import BrandMark from "./BrandMark";

/**
 * M8.13 (review B-2): the centred public frame shared by /login and /signup — a `main` landmark, the brand
 * linking back to the public homepage, the page's card, and a "Back to home" link. `busy` renders the frame
 * with no card while the session check runs (no sign-in form flash for a visitor who is already signed in).
 */
export default function AuthShell({ children, busy = false }: { children?: ReactNode; busy?: boolean }) {
  return (
    <main className="shell-centered" aria-busy={busy ? true : undefined}>
      <Link to="/" className="signin-brand" aria-label="SecScan home">
        <BrandMark />
        <span className="brand">SecScan</span>
      </Link>
      {busy ? null : children}
      {busy ? null : (
        <p className="auth-shell-home">
          <Link to="/">← Back to home</Link>
        </p>
      )}
    </main>
  );
}
