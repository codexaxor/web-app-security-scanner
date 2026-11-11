import SelectRepositories from "../components/SelectRepositories";
import PageLayout from "../components/PageLayout";

// Onboarding: pick repositories to scan (M1 screen, reachable from the shell).
export default function SelectPage() {
  return (
    <PageLayout
      title="Select repositories"
      subtitle="Choose which projects to scan from a connection."
      breadcrumbs={[{ label: "Sources", to: "/connect" }, { label: "Select repositories" }]}
    >
      <SelectRepositories />
    </PageLayout>
  );
}
