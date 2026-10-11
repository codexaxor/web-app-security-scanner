import { FormEvent, useState } from "react";
import { api, AuthRequiredError, clearCredentials, setCredentials } from "../api";

/**
 * Minimal in-memory sign-in so the SPA can call the HTTP Basic-protected API. A real login arrives in a later
 * milestone; credentials here live only in memory for the session. We verify them against a protected endpoint
 * so a wrong email/password is reported here instead of failing silently on the first real call.
 */
export default function SignInGate({ onSignedIn }: { onSignedIn: () => void }) {
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
      // 200 or 404 (no connection yet) both mean the credentials are accepted; 401 throws.
      await api.getConnection();
      onSignedIn();
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
    <form onSubmit={submit} style={{ display: "grid", gap: "0.75rem", maxWidth: 360 }}>
      <h2>Sign in</h2>
      <p style={{ color: "#555", margin: 0 }}>Use the bootstrap admin credentials.</p>
      <label>
        Email
        <input value={email} onChange={(e) => setEmail(e.target.value)} type="email" required />
      </label>
      <label>
        Password
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" required />
      </label>
      {error && <p style={{ color: "#b00020", margin: 0 }}>{error}</p>}
      <button type="submit" disabled={busy}>
        {busy ? "Signing in…" : "Continue"}
      </button>
    </form>
  );
}
