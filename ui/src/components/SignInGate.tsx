import { type FormEvent, useState } from "react";
import { Link } from "react-router";
import { AccountInactiveError, AccountPendingError, AuthRequiredError, RateLimitedError, login } from "../api";
import { useAuth } from "../auth";
import { retryText } from "../lib/signupValidation";
import AwaitingApprovalPage from "../pages/AwaitingApprovalPage";
import Button from "./Button";

/**
 * M8.10 login outcomes → what the person sees. None of these reveals whether an email exists: a wrong password
 * and an unknown email are the same 401; the 403s only ever follow a CORRECT password.
 */
export function signInErrorMessage(err: unknown): string {
  if (err instanceof AuthRequiredError) return "Wrong email or password.";
  if (err instanceof AccountInactiveError) return "This account is not active. Contact the operator.";
  if (err instanceof RateLimitedError) return `Too many sign-in attempts. ${retryText(err.retryAfterSeconds)}`;
  return "Could not reach the server. Try again.";
}

/**
 * Server-session sign-in (D-092). Posts the credentials to {@code /auth/login}; on success the server sets the
 * session cookie and returns the session info (incl. the CSRF token, held in memory by api.ts). The password is
 * never stored and never written to web storage. A refresh keeps the user signed in (auth.tsx resumes the
 * session), so this gate only appears when there is no valid session.
 */
export default function SignInGate() {
  const { signIn } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const info = await login(email, password);
      setPassword(""); // do not keep the password in component state after a successful sign-in
      signIn(info);
    } catch (err) {
      if (err instanceof AccountPendingError) {
        setPassword("");
        setPending(true);
      } else {
        setError(signInErrorMessage(err));
      }
    } finally {
      setBusy(false);
    }
  }

  if (pending) {
    return <AwaitingApprovalPage onBack={() => setPending(false)} />;
  }

  return (
    <form onSubmit={submit} className="form signin-form">
      <h1>Sign in</h1>
      <p className="muted">Enter your SecScan credentials.</p>
      <div className="field">
        <label htmlFor="signin-email">Email</label>
        <input
          id="signin-email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          type="text"
          autoComplete="username"
          required
        />
      </div>
      <div className="field">
        <label htmlFor="signin-password">Password</label>
        <input
          id="signin-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          type="password"
          autoComplete="current-password"
          required
        />
      </div>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
      <Button type="submit" variant="primary" busy={busy}>
        {busy ? "Signing in…" : "Continue"}
      </Button>
      {/* M8.10: the space M8.11 reserved under the form now holds the sign-up link. */}
      <div className="signin-footer">
        <span className="muted">New to SecScan?</span> <Link to="/signup">Create account</Link>
      </div>
    </form>
  );
}
