import { useState } from "react";
import { api, type ConnectionTestResult, type ConnectionView } from "../api";
import Dialog from "./Dialog";
import Button from "./Button";

/**
 * Rotate a connection's token in place (D-078) via the existing PUT. The token lives only in this component's
 * local state, is dropped the moment it is submitted, is never echoed back by the server, and is never written
 * to any browser storage (D-067). On success the new scopes/expiry are shown.
 */
export default function RotateTokenDialog({
  connection,
  onClose,
  onRotated,
}: {
  connection: ConnectionView;
  onClose: () => void;
  onRotated: () => void;
}) {
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);

  const path = connection.targetType === "group" ? connection.groupFullPath : connection.projectFullPath;
  const target = path ?? "";

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const r = await api.updateConnection(connection.id, { baseUrl: connection.baseUrl, token, path: target });
      setToken(""); // drop the token from state immediately after submit (D-078) — whatever the outcome
      if (!r.ok) {
        setError(r.message ?? "Token rotation failed.");
        return;
      }
      setResult(r);
      onRotated();
    } catch (e) {
      setToken("");
      setError(e instanceof Error ? e.message : "Token rotation failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title="Rotate token" onClose={onClose} variant="center">
      <h2>Rotate token</h2>
      <p className="muted">{target}</p>
      {error ? <p className="field-error">{error}</p> : null}
      {result ? (
        <div className="ok-text" data-testid="rotate-result">
          <div>Token rotated.</div>
          <div className="muted">Scopes: {(result.tokenScopes ?? []).join(", ")}</div>
          <div className="muted">Token expires: {result.tokenExpiresAt ?? "never"}</div>
        </div>
      ) : (
        <div className="form">
          <div className="field">
            <label htmlFor="rotate-token">New access token</label>
            <input
              id="rotate-token"
              type="password"
              autoComplete="off"
              value={token}
              onChange={(e) => setToken(e.target.value)}
            />
            <span className="help">
              Needs read_api and read_repository. Stored encrypted; never shown again.
            </span>
          </div>
        </div>
      )}
      <div className="modal-actions">
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          {result ? "Close" : "Cancel"}
        </Button>
        {result ? null : (
          <Button variant="primary" onClick={submit} disabled={busy || token.length === 0}>
            Rotate
          </Button>
        )}
      </div>
    </Dialog>
  );
}
