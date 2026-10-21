import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type RepositoryView } from "../api";
import { invalidateFor } from "../queryClient";
import ErrorBanner from "./ErrorBanner";

/**
 * Repository settings (OD-15). Sensitivity re-scores immediately on save; include-dev-deps takes effect at the
 * next scan; active toggles the lifecycle (reactivating a detached repo is a 409, shown here). "Save and rescan"
 * saves then starts a scan.
 */
export default function RepoSettingsDialog({
  repo,
  onClose,
}: {
  repo: RepositoryView;
  onClose: () => void;
}) {
  const [sensitivity, setSensitivity] = useState(repo.sensitivity);
  const [includeDevDeps, setIncludeDevDeps] = useState(repo.includeDevDeps);
  const [active, setActive] = useState(repo.active);

  const save = useMutation({
    mutationFn: async (rescan: boolean) => {
      await api.patchRepository(repo.id, { sensitivity, includeDevDeps, active });
      if (rescan && active) {
        await api.startScan(repo.id);
      }
    },
    onSuccess: async (_d, rescan) => {
      await invalidateFor("settings");
      if (rescan) {
        await invalidateFor("scan");
      }
      onClose();
    },
  });

  return (
    <div className="overlay overlay-center" onClick={onClose}>
      <div className="modal" role="dialog" aria-label="Repository settings" onClick={(e) => e.stopPropagation()}>
        <h2>Settings — {repo.pathWithNamespace}</h2>
        {save.error ? <ErrorBanner error={save.error} /> : null}
        <div className="stack">
          <label>
            Sensitivity
            <select value={sensitivity} onChange={(e) => setSensitivity(e.target.value)}>
              <option value="low">Low (−10)</option>
              <option value="normal">Normal</option>
              <option value="high">High (+5)</option>
            </select>
          </label>
          <label className="row">
            <input
              type="checkbox"
              checked={includeDevDeps}
              onChange={(e) => setIncludeDevDeps(e.target.checked)}
            />
            Include dev / test dependencies (takes effect on the next scan)
          </label>
          <label className="row">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            Active (scans enabled)
          </label>
        </div>
        <div className="row" style={{ marginTop: "0.8rem", justifyContent: "flex-end" }}>
          <button onClick={onClose} disabled={save.isPending}>
            Cancel
          </button>
          <button onClick={() => save.mutate(false)} disabled={save.isPending}>
            Save
          </button>
          <button className="primary" onClick={() => save.mutate(true)} disabled={save.isPending || !active}>
            Save and rescan
          </button>
        </div>
      </div>
    </div>
  );
}
