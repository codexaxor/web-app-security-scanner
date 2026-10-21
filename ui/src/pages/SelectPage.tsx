import SelectRepositories from "../components/SelectRepositories";

// Onboarding: pick repositories to scan (M1 screen, reachable from the shell).
export default function SelectPage() {
  return (
    <section>
      <h1>Select repositories</h1>
      <SelectRepositories />
    </section>
  );
}
