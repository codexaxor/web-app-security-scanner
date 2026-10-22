import ConnectGitLab from "../components/ConnectGitLab";
import { PageHeader } from "../components/ui";

// Onboarding: connect a GitLab group/project (M1 screen, reachable from the shell).
export default function ConnectPage() {
  return (
    <section>
      <PageHeader title="Connect GitLab" subtitle="Connect a GitLab group or a single project to scan." />
      <ConnectGitLab onChanged={() => {}} />
    </section>
  );
}
