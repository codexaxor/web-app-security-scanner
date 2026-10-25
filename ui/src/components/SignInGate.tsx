import { type FormEvent, useState } from "react";
import { api, AuthRequiredError, clearCredentials, setCredentials } from "../api";
import { useAuth } from "../auth";
import Button from "./Button";

/**
 * In-memory sign-in (OD-13a). Credentials live only in memory for the session; a refresh re-signs-in. We verify
 * them against a protected endpoint so a wrong email/password is reported here instead of failing on the first
 * real call.
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
    setCredentials(email, password);
    try {
      await api.listConnections(0, 1); // a 200 means the credentials are accepted; 401 throws
      signIn();
    } catch (err) {
      clearCredentials();
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
      <p className="muted">Use the bootstrap admin credentials.</p>
      <div className="field">
        <label htmlFor="signin-email">Email</label>
        <input
          id="signin-email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          type="email"
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
      <Button type="submit" variant="primary" disabled={busy}>
        {busy ? "Signing in…" : "Continue"}
      </Button>
    </form>
  );
}
