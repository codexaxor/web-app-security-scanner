import { FormEvent, useEffect, useState } from "react";
import {
  api,
  ConnectionRemovalResult,
  ConnectionTestResult,
  ConnectionView,
  RepositoryView,
  TargetType,
} from "../api";

/**
 * Connect screen: lists the workspace's GitLab connections (group or single project) and lets the user add a
 * new one or remove an existing one. The path field accepts a plain path or a pasted GitLab URL.
 */
export default function ConnectGitLab({ onChanged }: { onChanged: (anyConnections: boolean) => void }) {
  const [connections, setConnections] = useState<ConnectionView[]>([]);
  const [repos, setRepos] = useState<RepositoryView[]>([]);
  const [baseUrl, setBaseUrl] = useState("https://gitlab.celloscope.net");
  const [targetType, setTargetType] = useState<TargetType>("group");
  const [path, setPath] = useState("");
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ConnectionTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [removal, setRemoval] = useState<ConnectionRemovalResult | null>(null);

  async function refresh() {
    const [c, r] = await Promise.all([api.listConnections(0, 100), api.listRepositories(0, 500)]);
    setConnections(c.content);
    setRepos(r.content);
    onChanged(c.totalElements > 0);
  }

  useEffect(() => {
    refresh().catch((e) => setError(e instanceof Error ? e.message : "Failed to load connections"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function run(save: boolean, e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setResult(null);
    setError(null);
    setRemoval(null);
    try {
      const req = { baseUrl, token, targetType, path };
      const r = save ? await api.createConnection(req) : await api.testConnection(req);
      setResult(r);
      if (save && r.ok) {
        setPath("");
        setToken("");
        await refresh();
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unexpected error");
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: string) {
    setBusy(true);
    setError(null);
    setRemoval(null);
    try {
      const r = await api.deleteConnection(id);
      setRemoval(r);
      setConfirmId(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to remove connection");
    } finally {
      setBusy(false);
    }
  }

  const reposUsing = (id: string) => repos.filter((r) => r.connectionId === id).length;

  return (
    <section style={{ display: "grid", gap: "1.5rem", maxWidth: 640 }}>
      <div>
        <h2>GitLab connections</h2>
        {connections.length === 0 ? (
          <p style={{ color: "#555" }}>No connections yet. Add a group or a single project below.</p>
        ) : (
          <table style={{ borderCollapse: "collapse", width: "100%" }}>
            <thead>
              <tr style={{ textAlign: "left", borderBottom: "1px solid #ccc" }}>
                <th>Type</th>
                <th>Target</th>
                <th>Expires</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {connections.map((c) => (
                <tr key={c.id} style={{ borderBottom: "1px solid #eee" }}>
                  <td>{c.targetType}</td>
                  <td>{c.targetType === "group" ? c.groupFullPath : c.projectFullPath}</td>
                  <td>{c.tokenExpiresAt ?? "never"}</td>
                  <td style={{ textAlign: "right" }}>
                    {confirmId === c.id ? (
                      <span>
                        {reposUsing(c.id)} repo(s) will be reassigned or deactivated.{" "}
                        <button disabled={busy} onClick={() => remove(c.id)}>
                          Confirm
                        </button>{" "}
                        <button disabled={busy} onClick={() => setConfirmId(null)}>
                          Cancel
                        </button>
                      </span>
                    ) : (
                      <button disabled={busy} onClick={() => setConfirmId(c.id)}>
                        Remove
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {removal && (
          <p style={{ color: "#0a7d33" }}>
            Connection removed. {removal.reassigned} repositor{removal.reassigned === 1 ? "y" : "ies"} reassigned,{" "}
            {removal.deactivated} deactivated.
          </p>
        )}
      </div>

      <form style={{ display: "grid", gap: "0.75rem" }}>
        <h3 style={{ margin: 0 }}>Add a connection</h3>
        <div style={{ display: "flex", gap: "1rem" }}>
          <label>
            <input
              type="radio"
              name="targetType"
              checked={targetType === "group"}
              onChange={() => setTargetType("group")}
            />{" "}
            Group
          </label>
          <label>
            <input
              type="radio"
              name="targetType"
              checked={targetType === "project"}
              onChange={() => setTargetType("project")}
            />{" "}
            Single project
          </label>
        </div>
        <label>
          Server URL
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </label>
        <label>
          {targetType === "group" ? "Group path or URL" : "Project path or URL"}
          <input
            value={path}
            onChange={(e) => setPath(e.target.value)}
            placeholder={
              targetType === "group"
                ? "e.g. celloscope/platform or a pasted group URL"
                : "e.g. celloscope/platform/core-keycloak or a pasted project URL"
            }
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
            Add
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
            Connected to GitLab <strong>{result.gitlabVersion}</strong>,{" "}
            {result.targetType === "group" ? "group " : "project "}
            <strong>{result.targetType === "group" ? result.groupFullPath : result.projectFullPath}</strong>.
          </div>
          <div>Scopes: {(result.tokenScopes ?? []).join(", ")}</div>
          <div>
            Token expires: {result.tokenExpiresAt ?? "never"}
            {result.expiringSoon && <span style={{ color: "#b06f00" }}> — expiring soon, plan to rotate</span>}
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
    case "PROJECT_NOT_FOUND":
      return "Project not found or not visible to this token";
    case "RATE_LIMITED":
      return "GitLab rate limit reached; try again shortly";
    case "HOST_NOT_ALLOWED":
      return "That GitLab host is not allowed";
    case "CONNECTION_EXISTS":
      return "This workspace is already connected to that target";
    case "CONNECTION_TARGET_MISMATCH":
      return "That path resolves to a different target";
    default:
      return "Connection failed";
  }
}
