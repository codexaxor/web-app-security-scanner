import { FormEvent, useState } from "react";
import { setCredentials } from "../api";

/**
 * Minimal in-memory sign-in so the SPA can call the HTTP Basic-protected API. A real login arrives in a later
 * milestone; credentials here live only in memory for the session.
 */
export default function SignInGate({ onSignedIn }: { onSignedIn: () => void }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  function submit(e: FormEvent) {
    e.preventDefault();
    setCredentials(email, password);
    onSignedIn();
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
      <button type="submit">Continue</button>
    </form>
  );
}
