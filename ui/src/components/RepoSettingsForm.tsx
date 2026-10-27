import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type RepositoryView, type RepositorySettingsPatch } from "../api";
import { invalidateFor } from "../queryClient";
import ErrorBanner from "./ErrorBanner";
import Button from "./Button";
import BranchPicker from "./BranchPicker";

/**
 * The repository settings form (OD-15 / D-076), shared by the modal `RepoSettingsDialog` and the repository
 * page's Settings tab so both use the SAME fields, PATCH API and validation. Sensitivity re-scores immediately
 * on save; include-dev-deps takes effect at the next scan; active toggles the lifecycle. "Save and rescan"
 * saves then starts a scan. `onSaved` fires after a successful save (the dialog closes; the tab stays open).
 * When `onCancel` is given a Cancel button is shown (the dialog); the inline tab omits it.
 */
export default function RepoSettingsForm({
  repo,
  onSaved,
  onCancel,
}: {
  repo: RepositoryView;
  onSaved?: () => void;
  onCancel?: () => void;
}) {
  const [sensitivity, setSensitivity] = useState(repo.sensitivity);
  const [includeDevDeps, setIncludeDevDeps] = useState(repo.includeDevDeps);
  const [active, setActive] = useState(repo.active);
  const [scanBranch, setScanBranch] = useState<string | null>(repo.scanBranch);

  // N-6: picking the branch equal to the current effective default while the repo already follows the default
  // (scanBranch null) is NOT a change — no PATCH, no re-baseline warning.
  const branchChanged =
    scanBranch !== repo.scanBranch && !(repo.scanBranch === null && scanBranch === repo.effectiveBranch);
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
      onSaved?.();
    },
  });

  return (
    <>
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
        {onCancel ? (
          <Button variant="secondary" onClick={onCancel} disabled={save.isPending}>
            Cancel
          </Button>
        ) : null}
        <Button variant="secondary" onClick={() => save.mutate(false)} disabled={save.isPending}>
          Save
        </Button>
        <Button variant="primary" onClick={() => save.mutate(true)} disabled={save.isPending || !active}>
          Save and rescan
        </Button>
      </div>
    </>
  );
}
