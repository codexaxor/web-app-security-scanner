// First-run checklist logic (M8.11 item 5). Pure, so it is unit-tested without the component. The three
// onboarding steps are derived from existing list counts — add a source, add a repository, run the first scan.
// When all three are done the checklist is complete and the Dashboard hides it (no dismiss button, since that
// would need storage — approved change #6).
export interface ChecklistCounts {
  sources: number;
  repositories: number;
  scans: number;
}

export interface ChecklistStep {
  key: "source" | "repository" | "scan";
  label: string;
  description: string;
  cta: string;
  to: string;
  done: boolean;
}

export function firstRunSteps(c: ChecklistCounts): ChecklistStep[] {
  return [
    {
      key: "source",
      label: "Add a source",
      description: "Connect GitLab, GitHub, or a public repository.",
      cta: "Add a source",
      to: "/connect",
      done: c.sources > 0,
    },
    {
      key: "repository",
      label: "Add a repository",
      description: "Select the projects you want SecScan to watch.",
      cta: "Add a repository",
      to: "/select",
      done: c.repositories > 0,
    },
    {
      key: "scan",
      label: "Run your first scan",
      description: "Scan a repository to see its findings in the feed.",
      cta: "Go to Repositories",
      to: "/repositories",
      done: c.scans > 0,
    },
  ];
}

export function firstRunComplete(c: ChecklistCounts): boolean {
  return c.sources > 0 && c.repositories > 0 && c.scans > 0;
}

/** The step the user should do next: the first not-done step, or null when complete. */
export function nextStepKey(c: ChecklistCounts): ChecklistStep["key"] | null {
  return firstRunSteps(c).find((s) => !s.done)?.key ?? null;
}
