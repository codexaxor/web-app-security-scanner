import ConnectGitLab from "../components/ConnectGitLab";

// Onboarding: connect a GitLab group/project (M1 screen, reachable from the shell).
export default function ConnectPage() {
  return (
    <section>
      <h1>Connect GitLab</h1>
      <ConnectGitLab onChanged={() => {}} />
    </section>
  );
}
