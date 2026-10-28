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
import SelectSourceRepositories from "./SelectSourceRepositories";
import { Card, EmptyState } from "./ui";
import ProviderBadge from "./ProviderBadge";
import { providerMeta, targetLabel } from "../lib/providerMeta";

/** The human target of a connection, across providers. */
function connectionTarget(c: ConnectionView): string {
  switch (c.targetType) {
    case "group":
      return c.groupFullPath ?? "";
    case "project":
      return c.projectFullPath ?? "";
    case "owner":
      return c.ownerLogin ?? "";
    case "repo":
      return c.repoFullName ?? "";
    default:
      return "";
  }
}

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
  // M8.7 add-source forms (GitHub + public).
  const [ghMode, setGhMode] = useState<"owner" | "repo">("owner");
  const [ghValue, setGhValue] = useState("");
  const [ghToken, setGhToken] = useState("");
  const [pubUrl, setPubUrl] = useState("");
  const [sourceError, setSourceError] = useState<string | null>(null);
  const [sourceOk, setSourceOk] = useState<string | null>(null);
  // SF-1: after adding a non-GitLab source, offer the "Select repositories" step for that connection.
  const [selecting, setSelecting] = useState<ConnectionView | null>(null);

  async function addSource(run: () => Promise<ConnectionView>, reset: () => void) {
    setBusy(true);
    setSourceError(null);
    setSourceOk(null);
    try {
      const c = await run();
      reset();
      setSourceOk(`Added ${providerMeta(c.provider).label} ${targetLabel(c.targetType).toLowerCase()}. Now select its repositories.`);
      setSelecting(c);
      await refresh();
    } catch (err) {
      setSourceError(err instanceof Error ? err.message : "Failed to add source");
    } finally {
      setBusy(false);
    }
  }

  async function refresh() {
    const [c, r] = await Promise.all([api.listConnections(0, 100), api.listRepositories({ size: 500 })]);
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
        <h2 className="section-head">Connected sources</h2>
        {connections.length === 0 ? (
          <EmptyState title="No sources yet">Add a GitLab group/project, a GitHub owner/repository, or a public repository below.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Provider</th>
                  <th>Host</th>
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
                        <ProviderBadge provider={c.provider} />
                      </td>
                      <td className="muted">{c.host}</td>
                      <td>
                        <span className="type-badge">{targetLabel(c.targetType)}</span>
                      </td>
                      <td>{connectionTarget(c)}</td>
                      <td>
                        <span className={exp.cls} data-testid="connection-expiry">
                          {c.credentialKind === "none" ? "public" : exp.text}
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
          <h3>Add a GitLab connection</h3>
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

      <Card>
        <form className="form" data-testid="add-github-form" onSubmit={(e) => e.preventDefault()}>
          <h3>Add GitHub</h3>
          <div className="row" role="radiogroup" aria-label="GitHub target type">
            <label className="check">
              <input type="radio" name="ghMode" checked={ghMode === "owner"} onChange={() => setGhMode("owner")} />
              Owner (user/org)
            </label>
            <label className="check">
              <input type="radio" name="ghMode" checked={ghMode === "repo"} onChange={() => setGhMode("repo")} />
              Single repository
            </label>
          </div>
          <div className="field">
            <label htmlFor="gh-value">{ghMode === "owner" ? "Owner login (user or org)" : "owner/name"}</label>
            <input
              id="gh-value"
              value={ghValue}
              onChange={(e) => setGhValue(e.target.value)}
              placeholder={ghMode === "owner" ? "e.g. octocat" : "e.g. octocat/Hello-World"}
            />
          </div>
          <div className="field">
            <label htmlFor="gh-token">Fine-grained PAT (read-only Contents + Metadata)</label>
            <input id="gh-token" value={ghToken} onChange={(e) => setGhToken(e.target.value)} type="password" autoComplete="off" />
            <span className="help">Entered only here; stored encrypted; never shown again (D-014).</span>
          </div>
          <div className="row">
            <Button
              type="submit"
              variant="primary"
              disabled={busy || !ghValue || !ghToken}
              onClick={() =>
                addSource(
                  () =>
                    ghMode === "owner"
                      ? api.addGitHubOwner(ghValue, ghToken)
                      : api.addGitHubRepo(ghValue, ghToken),
                  () => {
                    setGhValue("");
                    setGhToken("");
                  },
                )
              }
            >
              Add GitHub source
            </Button>
          </div>
        </form>
      </Card>

      <Card>
        <form className="form" data-testid="add-public-form" onSubmit={(e) => e.preventDefault()}>
          <h3>Add a public repository</h3>
          <div className="field">
            <label htmlFor="pub-url">Repository URL (https)</label>
            <input
              id="pub-url"
              value={pubUrl}
              onChange={(e) => setPubUrl(e.target.value)}
              placeholder="e.g. https://github.com/octocat/Hello-World"
            />
            <span className="help">Cloned anonymously over https. The host must be allowed.</span>
          </div>
          <div className="row">
            <Button
              type="submit"
              variant="primary"
              disabled={busy || !pubUrl}
              onClick={() => addSource(() => api.addPublicRepo(pubUrl), () => setPubUrl(""))}
            >
              Add public repository
            </Button>
          </div>
        </form>
      </Card>

      {sourceOk && <p className="ok-text" data-testid="source-ok">{sourceOk}</p>}
      {sourceError && <p className="field-error" data-testid="source-error">{sourceError}</p>}

      {selecting ? (
        <Card>
          <SelectSourceRepositories connection={selecting} onSelected={() => refresh()} />
          <div className="row">
            <Button variant="secondary" size="sm" onClick={() => setSelecting(null)}>
              Done
            </Button>
          </div>
        </Card>
      ) : null}

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
