import SelectRepositories from "../components/SelectRepositories";
import { PageHeader } from "../components/ui";
import Breadcrumbs from "../components/Breadcrumbs";

// Onboarding: pick repositories to scan (M1 screen, reachable from the shell).
export default function SelectPage() {
  return (
    <section>
      <Breadcrumbs items={[{ label: "Sources", to: "/connect" }, { label: "Select repositories" }]} />
      <PageHeader title="Select repositories" subtitle="Choose which projects to scan from a connection." />
      <SelectRepositories />
    </section>
  );
}
