import { FormEvent, useState } from "react";
import { api, ConnectionTestResult } from "../api";

/** Connect screen: enter server URL, group path and token; Test shows version + expiry or a clear error. */
export default function ConnectGitLab({ onConnected }: { onConnected: () => void }) {
  const [baseUrl, setBaseUrl] = useState("https://gitlab.celloscope.net");
  const [groupPath, setGroupPath] = useState("");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(save: boolean, e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setResult(null);
    setError(null);
    try {
      const req = { baseUrl, token, groupPath };
      const r = save ? await api.saveConnection(req) : await api.testConnection(req);
      setResult(r);
      if (save && r.ok) onConnected();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unexpected error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section style={{ display: "grid", gap: "1rem", maxWidth: 560 }}>
      <h2>Connect GitLab</h2>
      <form style={{ display: "grid", gap: "0.75rem" }}>
        <label>
          Server URL
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </label>
        <label>
          Group path
          <input
            value={groupPath}
            onChange={(e) => setGroupPath(e.target.value)}
            placeholder="e.g. services"
          />
        </label>
        <label>
          Access token (read_api, read_repository)
          <input value={token} onChange={(e) => setToken(e.target.value)} type="password" />
        </label>
        <div style={{ display: "flex", gap: "0.5rem" }}>
          <button type="submit" disabled={busy} onClick={(e) => run(false, e)}>
            Test connection
          </button>
          <button type="button" disabled={busy} onClick={(e) => run(true, e)}>
            Save
          </button>
        </div>
      </form>

      {error && <p style={{ color: "#b00020" }}>{error}</p>}

      {result && !result.ok && (
        <div style={{ color: "#b00020" }}>
          <strong>{errorLabel(result.errorCode)}</strong>
          <div>{result.message}</div>
        </div>
      )}

      {result && result.ok && (
        <div style={{ color: "#0a7d33" }}>
          <div>
            Connected to GitLab <strong>{result.gitlabVersion}</strong>, group{" "}
            <strong>{result.groupFullPath}</strong>.
          </div>
          <div>Scopes: {(result.tokenScopes ?? []).join(", ")}</div>
          <div>
            Token expires: {result.tokenExpiresAt ?? "never"}
            {result.expiringSoon && (
              <span style={{ color: "#b06f00" }}> — expiring soon, plan to rotate</span>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

function errorLabel(code: string | null): string {
  switch (code) {
    case "UNREACHABLE":
      return "Cannot reach GitLab (is the VPN connected?)";
    case "INVALID_TOKEN":
      return "The token is invalid or revoked";
    case "MISSING_SCOPES":
      return "The token is missing required scopes";
    case "TOKEN_EXPIRED":
      return "The token has expired";
    case "GROUP_NOT_FOUND":
      return "Group not found or not visible to this token";
    case "RATE_LIMITED":
      return "GitLab rate limit reached; try again shortly";
    default:
      return "Connection failed";
  }
}
