import { Navigate, useSearchParams } from "react-router";
import { useAuth } from "../auth";
import AuthShell from "../components/AuthShell";
import SignInGate from "../components/SignInGate";
import { safeNextPath } from "../lib/safeNext";

/**
 * M8.13 sign-in route (/login). The public homepage and the app shell are separate now, so sign-in has its own
 * page instead of rendering in place of the shell. It reuses {@link SignInGate} (unchanged — it posts to
 * /auth/login and flips the auth state) and adds the two things a route needs:
 *   - a `?next=` destination, sanitized to a same-origin path ({@link safeNextPath}) so a crafted link can never
 *     turn sign-in into an open redirect;
 *   - once the session is live, redirect to that destination (or /dashboard).
 * It renders inside {@link AuthShell} (brand links to "/", a "Back to home" link), shared with /signup.
 * A signed-out visitor who deep-links a protected route is bounced here as /login?next=<that route> (see
 * Layout), so signing in lands them exactly where they were headed.
 */
export default function LoginPage() {
  const { checking, signedIn } = useAuth();
  const [params] = useSearchParams();
  const next = safeNextPath(params.get("next"));

  // While the session check runs, show the neutral frame only — a signed-in visitor never sees the form flash.
  if (checking) {
    return <AuthShell busy />;
  }
  if (signedIn) {
    return <Navigate to={next} replace />;
  }

  return (
    <AuthShell>
      <SignInGate />
    </AuthShell>
  );
}
