import { FormEvent, useEffect, useState } from "react";
import {
  api,
  ConnectionRemovalResult,
  ConnectionTestResult,
  ConnectionView,
  RepositoryView,
  TargetType,
} from "../api";
import Button from "./Button";
import ConfirmDialog from "./ConfirmDialog";
import RotateTokenDialog from "./RotateTokenDialog";
import { Card, EmptyState } from "./ui";

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
  const [confirm, setConfirm] = useState<ConnectionView | null>(null);
  const [rotating, setRotating] = useState<ConnectionView | null>(null);
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
      setConfirm(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to remove connection");
    } finally {
      setBusy(false);
    }
  }

  const reposUsing = (id: string) => repos.filter((r) => r.connectionId === id).length;

  return (
    <div className="stack-4 onboarding">
      <div>
        <h2 className="section-head">GitLab connections</h2>
        {connections.length === 0 ? (
          <EmptyState title="No connections yet">Add a group or a single project below.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Target</th>
                  <th>Token expiry</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {connections.map((c) => {
                  const exp = expiry(c.tokenExpiresAt);
                  return (
                    <tr key={c.id} data-testid="connection-row">
                      <td>
                        <span className="type-badge">{c.targetType}</span>
                      </td>
                      <td>{c.targetType === "group" ? c.groupFullPath : c.projectFullPath}</td>
                      <td>
                        <span className={exp.cls} data-testid="connection-expiry">
                          {exp.text}
                        </span>
                      </td>
                      <td style={{ textAlign: "right" }}>
                        <Button variant="secondary" size="sm" disabled={busy} onClick={() => setRotating(c)}>
                          Rotate token
                        </Button>{" "}
                        <Button variant="danger" size="sm" disabled={busy} onClick={() => setConfirm(c)}>
                          Remove
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {removal && (
          <p className="ok-text">
            Connection removed. {removal.reassigned} repositor{removal.reassigned === 1 ? "y" : "ies"} reassigned,{" "}
            {removal.deactivated} deactivated.
          </p>
        )}
      </div>

      <Card>
        <form className="form">
          <h3>Add a connection</h3>
          <div className="row" role="radiogroup" aria-label="Target type">
            <label className="check">
              <input
                type="radio"
                name="targetType"
                checked={targetType === "group"}
                onChange={() => setTargetType("group")}
              />
              Group
            </label>
            <label className="check">
              <input
                type="radio"
                name="targetType"
                checked={targetType === "project"}
                onChange={() => setTargetType("project")}
              />
              Single project
            </label>
          </div>
          <div className="field">
            <label htmlFor="conn-url">Server URL</label>
            <input id="conn-url" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="conn-path">{targetType === "group" ? "Group path or URL" : "Project path or URL"}</label>
            <input
              id="conn-path"
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder={
                targetType === "group"
                  ? "e.g. celloscope/platform or a pasted group URL"
                  : "e.g. celloscope/platform/core-keycloak or a pasted project URL"
              }
            />
          </div>
          <div className="field">
            <label htmlFor="conn-token">Access token</label>
            <input
              id="conn-token"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              type="password"
              autoComplete="off"
            />
            <span className="help">Needs read_api and read_repository. Stored encrypted; never shown again.</span>
          </div>
          <div className="row">
            <Button type="submit" variant="secondary" disabled={busy} onClick={(e) => run(false, e)}>
              Test connection
            </Button>
            <Button type="button" variant="primary" disabled={busy} onClick={(e) => run(true, e)}>
              Add
            </Button>
          </div>
        </form>
      </Card>

      {error && <p className="field-error">{error}</p>}

      {result && !result.ok && (
        <div className="banner banner-error" role="alert">
          <span>
            <strong>{errorLabel(result.errorCode)}: </strong>
            {result.message}
          </span>
        </div>
      )}

      {result && result.ok && (
        <Card>
          <div className="ok-text">
            Connected to GitLab <strong>{result.gitlabVersion}</strong>,{" "}
            {result.targetType === "group" ? "group " : "project "}
            <strong>{result.targetType === "group" ? result.groupFullPath : result.projectFullPath}</strong>.
          </div>
          <div className="muted">Scopes: {(result.tokenScopes ?? []).join(", ")}</div>
          <div className="muted">
            Token expires: {result.tokenExpiresAt ?? "never"}
            {result.expiringSoon && <span className="expiry-warn"> — expiring soon, plan to rotate</span>}
          </div>
        </Card>
      )}

      {confirm ? (
        <ConfirmDialog
          title="Remove connection"
          confirmLabel="Remove connection"
          confirmVariant="danger"
          busy={busy}
          onConfirm={() => remove(confirm.id)}
          onCancel={() => setConfirm(null)}
        >
          <p>
            Remove the connection to{" "}
            <strong>{confirm.targetType === "group" ? confirm.groupFullPath : confirm.projectFullPath}</strong>?
          </p>
          <p className="muted">
            {reposUsing(confirm.id)} repositor{reposUsing(confirm.id) === 1 ? "y" : "ies"} using it will be
            reassigned to another connection for the same target, or deactivated if none remains (D-020).
          </p>
        </ConfirmDialog>
      ) : null}

      {rotating ? (
        <RotateTokenDialog
          connection={rotating}
          onClose={() => setRotating(null)}
          onRotated={() => refresh()}
        />
      ) : null}
    </div>
  );
}

/** Token expiry as a date + "N days left"; amber under 14 days, red when already expired. */
function expiry(iso: string | null): { text: string; cls: string } {
  if (!iso) {
    return { text: "No expiry", cls: "muted" };
  }
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) {
    return { text: "No expiry", cls: "muted" };
  }
  const date = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(when);
  const days = Math.ceil((when.getTime() - Date.now()) / 86400000);
  if (days < 0) {
    return { text: `${date} · expired`, cls: "expiry-expired" };
  }
  return { text: `${date} · ${days} day${days === 1 ? "" : "s"} left`, cls: days < 14 ? "expiry-warn" : "muted" };
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
