import { type FormEvent, useState } from "react";
import { api, AuthRequiredError, clearCredentials, setCredentials } from "../api";
import { useAuth } from "../auth";

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
    <form onSubmit={submit} className="signin">
      <h2>Sign in</h2>
      <p className="muted">Use the bootstrap admin credentials.</p>
      <label>
        Email
        <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" required />
      </label>
      <label>
        Password
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" required />
      </label>
      {error && <p className="field-error">{error}</p>}
      <button type="submit" disabled={busy}>
        {busy ? "Signing in…" : "Continue"}
      </button>
    </form>
  );
}
