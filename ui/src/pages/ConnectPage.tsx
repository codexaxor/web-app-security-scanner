import ConnectGitLab from "../components/ConnectGitLab";
import { PageHeader } from "../components/ui";

// Onboarding: connect a GitLab group/project (M1 screen, reachable from the shell).
export default function ConnectPage() {
  return (
    <section>
      <PageHeader
        title="Sources"
        subtitle="Connect GitLab, GitHub, or a public repository to scan — all in one feed."
      />
      <ConnectGitLab onChanged={() => {}} />
    </section>
  );
}
