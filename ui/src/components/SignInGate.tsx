import { type FormEvent, useState } from "react";
import { AuthRequiredError, login } from "../api";
import { useAuth } from "../auth";
import Button from "./Button";

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

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const info = await login(email, password);
      setPassword(""); // do not keep the password in component state after a successful sign-in
      signIn(info);
    } catch (err) {
      setError(
        err instanceof AuthRequiredError
          ? "Wrong email or password."
          : "Could not reach the server. Try again.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="form">
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
      {error && <p className="field-error">{error}</p>}
      <Button type="submit" variant="primary" busy={busy}>
        {busy ? "Signing in…" : "Continue"}
      </Button>
    </form>
  );
}
