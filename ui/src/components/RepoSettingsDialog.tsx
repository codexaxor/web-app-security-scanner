import { type RepositoryView } from "../api";
import Dialog from "./Dialog";
import RepoSettingsForm from "./RepoSettingsForm";

/**
 * Repository settings as a modal (OD-15). The fields, PATCH API and validation live in the shared
 * {@link RepoSettingsForm}, which the repository page's Settings tab also uses.
 */
export default function RepoSettingsDialog({
  repo,
  onClose,
}: {
  repo: RepositoryView;
  onClose: () => void;
}) {
  return (
    <Dialog title="Repository settings" onClose={onClose} variant="center">
      <h2>Settings</h2>
      <p className="muted">{repo.pathWithNamespace}</p>
      <RepoSettingsForm repo={repo} onSaved={onClose} onCancel={onClose} />
    </Dialog>
  );
}
