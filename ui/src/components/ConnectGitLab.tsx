import { useEffect, useMemo, useState } from "react";
import { api, ConnectionRemovalResult, ConnectionView, RepositoryView } from "../api";
import { useToast, errorMessage } from "../toast";
import { isParseError, kindForPath, parseSourceUrl, type SourceKind } from "../lib/sourceInput";
import Button from "./Button";
import ConfirmDialog from "./ConfirmDialog";
import RotateTokenDialog from "./RotateTokenDialog";
import RowMenu from "./RowMenu";
import SelectSourceRepositories from "./SelectSourceRepositories";
import { Card, EmptyState } from "./ui";
import ProviderBadge from "./ProviderBadge";
import { targetLabel } from "../lib/providerMeta";

type Provider = "gitlab" | "github";
type GlHost = "saas" | "self";
type Access = "private" | "public";

const SELF_HOST_DEFAULT = "https://gitlab.celloscope.net";

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
 * The Sources screen (M8.8 item 7). A provider-first wizard — GitLab | GitHub, then (GitLab) self-managed |
 * gitlab.com, then private | public — showing only the fields each path needs, with one input that accepts a
 * pasted browser URL OR a path (the detected provider/host/kind is shown before submit) and a per-path help box.
 * Below it, the connected sources table with a ⋮ actions menu (Rotate token hidden for a public source) and a
 * one-line token expiry.
 */
export default function ConnectGitLab({ onChanged }: { onChanged: (anyConnections: boolean) => void }) {
  const toast = useToast();
  const [connections, setConnections] = useState<ConnectionView[]>([]);
  const [repos, setRepos] = useState<RepositoryView[]>([]);
  const [confirm, setConfirm] = useState<ConnectionView | null>(null);
  const [rotating, setRotating] = useState<ConnectionView | null>(null);
  const [removal, setRemoval] = useState<ConnectionRemovalResult | null>(null);
  const [selecting, setSelecting] = useState<ConnectionView | null>(null);

  // Wizard state.
  const [provider, setProvider] = useState<Provider>("gitlab");
  const [glHost, setGlHost] = useState<GlHost>("self");
  const [access, setAccess] = useState<Access>("private");
  const [selfUrl, setSelfUrl] = useState(SELF_HOST_DEFAULT);
  const [input, setInput] = useState("");
  const [token, setToken] = useState("");
  const [kindOverride, setKindOverride] = useState<SourceKind | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function refresh() {
    const [c, r] = await Promise.all([api.listConnections(0, 100), api.listRepositories({ size: 500 })]);
    setConnections(c.content);
    setRepos(r.content);
    onChanged(c.totalElements > 0);
  }

  useEffect(() => {
    refresh().catch((e) => setError(errorMessage(e, "Failed to load connections.")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // What we detect from the current input (a pasted URL, else a bare path interpreted in the chosen context).
  const detected = useMemo(() => {
    const raw = input.trim();
    if (!raw) {
      return null;
    }
    const urlParse = parseSourceUrl(raw);
    if (!isParseError(urlParse)) {
      return { host: urlParse.host, path: urlParse.path, kind: urlParse.kind, fromUrl: true, provider: urlParse.provider };
    }
    if (raw.includes("://")) {
      return { error: urlParse.error };
    }
    const host = provider === "github" ? "github.com" : glHost === "saas" ? "gitlab.com" : hostOf(selfUrl);
    const path = raw.replace(/^\/+|\/+$/g, "").replace(/\.git$/i, "");
    return { host, path, kind: kindForPath(provider, path), fromUrl: false, provider };
  }, [input, provider, glHost, selfUrl]);

  const effectiveKind: SourceKind | null =
    kindOverride ?? (detected && !("error" in detected) ? detected.kind : null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const added = await runAdd();
      setInput("");
      setToken("");
      setKindOverride(null);
      toast.success(`Added ${providerWord()} source. Now select its repositories.`);
      setSelecting(added);
      await refresh();
    } catch (e) {
      setError(errorMessage(e, "Could not add the source."));
    } finally {
      setBusy(false);
    }
  }

  function providerWord(): string {
    return provider === "github" ? "GitHub" : "GitLab";
  }

  function runAdd(): Promise<ConnectionView> {
    const raw = input.trim();
    if (access === "public") {
      // A public repo is always added by its full https URL (cloned anonymously).
      return api.addPublicRepo(raw);
    }
    if (provider === "github") {
      // owner or owner/repo (from a URL or a bare path).
      const path = detected && !("error" in detected) ? detected.path : raw;
      const kind = effectiveKind ?? kindForPath("github", path);
      return kind === "repo" ? api.addGitHubRepo(path, token) : api.addGitHubOwner(path, token);
    }
    // GitLab private: resolve baseUrl + path + group/project.
    const baseUrl =
      detected && !("error" in detected) && detected.fromUrl
        ? `https://${detected.host}`
        : glHost === "saas"
          ? "https://gitlab.com"
          : selfUrl.trim();
    const path = detected && !("error" in detected) ? detected.path : raw;
    const targetType = (effectiveKind === "group" ? "group" : "project") as "group" | "project";
    return api.createConnection({ baseUrl, token, targetType, path }).then(() => lastAdded(baseUrl, path));
  }

  // createConnection returns a test-result; re-read the freshly-added connection row for the select step.
  async function lastAdded(baseUrl: string, path: string): Promise<ConnectionView> {
    const c = await api.listConnections(0, 100);
    const host = hostOf(baseUrl);
    return (
      c.content.find((x) => x.host === host && connectionTarget(x) === path) ??
      c.content[c.content.length - 1]
    );
  }

  async function remove(id: string) {
    setBusy(true);
    setError(null);
    try {
      const r = await api.deleteConnection(id);
      setRemoval(r);
      setConfirm(null);
      toast.success("Source removed.");
      await refresh();
    } catch (e) {
      setError(errorMessage(e, "Failed to remove the source."));
    } finally {
      setBusy(false);
    }
  }

  const reposUsing = (id: string) => repos.filter((r) => r.connectionId === id).length;
  const needsToken = access === "private";
  const showGlHostStep = provider === "gitlab";
  const showSelfUrl = provider === "gitlab" && glHost === "self" && !(detected && "fromUrl" in detected && detected.fromUrl);
  const submitDisabled = busy || !input.trim() || (needsToken && !token);

  return (
    <div className="stack-4">
      <Card>
        <form className="form" data-testid="add-source-form" onSubmit={(e) => e.preventDefault()}>
          <h3>Add a source</h3>

          <Segmented
            label="Provider"
            value={provider}
            options={[
              ["gitlab", "GitLab"],
              ["github", "GitHub"],
            ]}
            onChange={(v) => {
              setProvider(v as Provider);
              setKindOverride(null);
            }}
          />

          {showGlHostStep ? (
            <Segmented
              label="GitLab host"
              value={glHost}
              options={[
                ["self", "Self-managed"],
                ["saas", "gitlab.com"],
              ]}
              onChange={(v) => setGlHost(v as GlHost)}
            />
          ) : null}

          <Segmented
            label="Access"
            value={access}
            options={[
              ["private", "Private (token)"],
              ["public", "Public (no token)"],
            ]}
            onChange={(v) => setAccess(v as Access)}
          />

          {showSelfUrl ? (
            <div className="field">
              <label htmlFor="self-url">Server URL</label>
              <input id="self-url" value={selfUrl} onChange={(e) => setSelfUrl(e.target.value)} />
            </div>
          ) : null}

          <div className="field">
            <label htmlFor="source-input">{inputLabel(provider, access)}</label>
            <input
              id="source-input"
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                setKindOverride(null);
              }}
              placeholder={inputPlaceholder(provider, glHost, access)}
              autoComplete="off"
            />
            {detected && "error" in detected ? (
              <span className="field-error">{detected.error}</span>
            ) : detected ? (
              <span className="help" data-testid="detected">
                Detected: {providerWord()} {detected.kind} <code>{detected.path}</code> on {detected.host}
              </span>
            ) : null}
          </div>

          {provider === "gitlab" && access === "private" && detected && !("error" in detected) ? (
            <Segmented
              label="This is a"
              value={effectiveKind ?? "project"}
              options={[
                ["group", "Group"],
                ["project", "Project"],
              ]}
              onChange={(v) => setKindOverride(v as SourceKind)}
            />
          ) : null}

          {needsToken ? (
            <div className="field">
              <label htmlFor="source-token">Access token</label>
              <input
                id="source-token"
                value={token}
                onChange={(e) => setToken(e.target.value)}
                type="password"
                autoComplete="off"
              />
            </div>
          ) : null}

          <HelpBox provider={provider} glHost={glHost} access={access} />

          {error ? <p className="field-error" data-testid="source-error">{error}</p> : null}

          <div className="row">
            <Button type="button" variant="primary" disabled={submitDisabled} onClick={submit}>
              {busy ? "Adding…" : "Add source"}
            </Button>
          </div>
        </form>
      </Card>

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

      <div>
        <h2 className="section-head">Connected sources</h2>
        {connections.length === 0 ? (
          <EmptyState title="No sources yet">
            Add a GitLab group/project, a GitHub owner/repository, or a public repository above.
          </EmptyState>
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
                  <th aria-label="Actions"></th>
                </tr>
              </thead>
              <tbody>
                {connections.map((c) => {
                  const exp = expiry(c.tokenExpiresAt);
                  const isPublic = c.credentialKind === "none";
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
                        <span className={`nowrap ${exp.cls}`} data-testid="connection-expiry">
                          {isPublic ? "Public · no token" : exp.text}
                        </span>
                      </td>
                      <td style={{ textAlign: "right" }}>
                        <RowMenu label="Source actions">
                          {(close) => (
                            <>
                              {isPublic ? null : (
                                <button
                                  type="button"
                                  className="rowmenu-item"
                                  onClick={() => {
                                    close();
                                    setRotating(c);
                                  }}
                                >
                                  Rotate token
                                </button>
                              )}
                              <button
                                type="button"
                                className="rowmenu-item danger"
                                onClick={() => {
                                  close();
                                  setConfirm(c);
                                }}
                              >
                                Remove
                              </button>
                            </>
                          )}
                        </RowMenu>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {removal ? (
          <p className="muted">
            Source removed. {removal.reassigned} repositor{removal.reassigned === 1 ? "y" : "ies"} reassigned,{" "}
            {removal.deactivated} deactivated.
          </p>
        ) : null}
      </div>

      {confirm ? (
        <ConfirmDialog
          title="Remove source"
          confirmLabel="Remove source"
          confirmVariant="danger"
          busy={busy}
          onConfirm={() => remove(confirm.id)}
          onCancel={() => setConfirm(null)}
        >
          <p>
            Remove the connection to <strong>{connectionTarget(confirm)}</strong>?
          </p>
          <p className="muted">
            {reposUsing(confirm.id)} repositor{reposUsing(confirm.id) === 1 ? "y" : "ies"} using it will be
            reassigned to another connection for the same target, or deactivated if none remains (D-020).
          </p>
        </ConfirmDialog>
      ) : null}

      {rotating ? (
        <RotateTokenDialog connection={rotating} onClose={() => setRotating(null)} onRotated={() => refresh()} />
      ) : null}
    </div>
  );
}

function Segmented({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: [string, string][];
  onChange: (v: string) => void;
}) {
  return (
    <div className="field">
      <span className="field-label">{label}</span>
      <div className="segmented" role="group" aria-label={label}>
        {options.map(([v, text]) => (
          <button
            key={v}
            type="button"
            className={value === v ? "active" : ""}
            aria-pressed={value === v}
            onClick={() => onChange(v)}
          >
            {text}
          </button>
        ))}
      </div>
    </div>
  );
}

function HelpBox({ provider, glHost, access }: { provider: Provider; glHost: GlHost; access: Access }) {
  if (access === "public") {
    return (
      <div className="help-box" data-testid="help-box">
        <strong>Public repository</strong>
        <p>Paste the repository's https URL. It is cloned anonymously — no token is stored.</p>
        <p>The host must be allowed by the server (GitHub and GitLab SaaS are allowed by default).</p>
      </div>
    );
  }
  if (provider === "github") {
    return (
      <div className="help-box" data-testid="help-box">
        <strong>GitHub fine-grained token</strong>
        <p>
          Create a fine-grained personal access token with <strong>Contents: Read</strong> and{" "}
          <strong>Metadata: Read</strong>, resource owner = the org/user you are adding.
        </p>
        <p>
          Create it at github.com → Settings → Developer settings → Fine-grained tokens. It is entered only here
          and stored encrypted.
        </p>
      </div>
    );
  }
  return (
    <div className="help-box" data-testid="help-box">
      <strong>GitLab access token</strong>
      <p>
        Create a token with <strong>read_api</strong> and <strong>read_repository</strong> (Settings → Access
        Tokens). It is entered only here and stored encrypted.
      </p>
      {glHost === "self" ? (
        <p>A self-managed host must be in the server's SECSCAN_GITLAB_ALLOWED_HOSTS allowlist.</p>
      ) : null}
    </div>
  );
}

function inputLabel(provider: Provider, access: Access): string {
  if (access === "public") {
    return "Repository URL (https)";
  }
  return provider === "github" ? "Owner, owner/repo, or URL" : "Group/project path or URL";
}

function inputPlaceholder(provider: Provider, glHost: GlHost, access: Access): string {
  if (access === "public") {
    return "https://github.com/owner/name";
  }
  if (provider === "github") {
    return "octocat or octocat/Hello-World";
  }
  return glHost === "saas" ? "group/subgroup/project or a pasted URL" : "team/app or a pasted URL";
}

function hostOf(url: string): string {
  try {
    return new URL(url.includes("://") ? url : `https://${url}`).host;
  } catch {
    return url;
  }
}

/** Token expiry on ONE line: "Nov 13, 2026 · 16 days". Amber under 14 days, red when already expired. */
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
  return { text: `${date} · ${days} day${days === 1 ? "" : "s"}`, cls: days < 14 ? "expiry-warn" : "muted" };
}
