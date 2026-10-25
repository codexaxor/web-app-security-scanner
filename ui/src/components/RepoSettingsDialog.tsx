import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type RepositoryView, type RepositorySettingsPatch } from "../api";
import { invalidateFor } from "../queryClient";
import ErrorBanner from "./ErrorBanner";
import Dialog from "./Dialog";
import Button from "./Button";
import BranchPicker from "./BranchPicker";

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
  const [scanBranch, setScanBranch] = useState<string | null>(repo.scanBranch);

  // N-6: picking the branch equal to the current effective default while the repo already follows the default
  // (scanBranch null) is NOT a change — no PATCH, no re-baseline warning.
  const branchChanged =
    scanBranch !== repo.scanBranch &&
    !(repo.scanBranch === null && scanBranch === repo.effectiveBranch);
  // D-076: changing the branch of a repository that has already been scanned re-baselines its findings.
  const willReBaseline = branchChanged && repo.lastScan !== null;

  const save = useMutation({
    mutationFn: async (rescan: boolean) => {
      const patch: RepositorySettingsPatch = { sensitivity, includeDevDeps, active };
      if (branchChanged) {
        patch.scanBranch = scanBranch; // string = set; null = reset to the GitLab default
      }
      await api.patchRepository(repo.id, patch);
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
    <Dialog title="Repository settings" onClose={onClose} variant="center">
      <h2>Settings</h2>
      <p className="muted">{repo.pathWithNamespace}</p>
      {save.error ? <ErrorBanner error={save.error} /> : null}
      <div className="form">
        <BranchPicker
          repoId={repo.id}
          defaultBranch={repo.defaultBranch}
          value={scanBranch}
          onChange={setScanBranch}
        />
        {willReBaseline ? (
          <p className="expiry-warn">
            This repository has been scanned before. Scanning a different branch re-baselines its findings:
            issues not present on the new branch are closed as “branch changed” (not fixed), and they reopen the
            same way if you switch back. Ignored findings stay ignored.
          </p>
        ) : null}
        <div className="field">
          <label htmlFor="repo-sensitivity">Sensitivity</label>
          <select id="repo-sensitivity" value={sensitivity} onChange={(e) => setSensitivity(e.target.value)}>
            <option value="low">Low (−10)</option>
            <option value="normal">Normal</option>
            <option value="high">High (+5)</option>
          </select>
        </div>
        <label className="check">
          <input type="checkbox" checked={includeDevDeps} onChange={(e) => setIncludeDevDeps(e.target.checked)} />
          Include dev / test dependencies (takes effect on the next scan)
        </label>
        <label className="check">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          Active (scans enabled)
        </label>
      </div>
      <div className="modal-actions">
        <Button variant="secondary" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button variant="secondary" onClick={() => save.mutate(false)} disabled={save.isPending}>
          Save
        </Button>
        <Button variant="primary" onClick={() => save.mutate(true)} disabled={save.isPending || !active}>
          Save and rescan
        </Button>
      </div>
    </Dialog>
  );
}
