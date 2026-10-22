import SelectRepositories from "../components/SelectRepositories";
import { PageHeader } from "../components/ui";

// Onboarding: pick repositories to scan (M1 screen, reachable from the shell).
export default function SelectPage() {
  return (
    <section>
      <PageHeader title="Select repositories" subtitle="Choose which projects to scan from a connection." />
      <SelectRepositories />
    </section>
  );
}
