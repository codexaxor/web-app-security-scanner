import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import { firstRunComplete, firstRunSteps, nextStepKey, type ChecklistCounts } from "../lib/firstRunChecklist";
import Icon from "./icons";

/**
 * First-run checklist (M8.11 item 5). Shown on the Dashboard until a source, a repository and a scan all
 * exist, then it auto-hides (no dismiss button — approved change #6). Counts come from existing list
 * endpoints (totalElements with size=1), so no new API is needed.
 */
export default function FirstRunChecklist() {
  const sources = useQuery({
    // Keys sit under the invalidated families (connections / repositories / scans-recent) so adding a source,
    // selecting repositories or finishing a scan refreshes the checklist immediately (M8.11 review N-1).
    queryKey: qk.connections({ page: 0, size: 1 }),
    queryFn: () => api.listConnections(0, 1),
  });
  const repositories = useQuery({
    queryKey: qk.repositories({ page: 0, size: 1 }),
    queryFn: () => api.listRepositories({ page: 0, size: 1 }),
  });
  const scans = useQuery({
    queryKey: qk.scansCount,
    queryFn: () => api.listScansAll(0, 1),
  });

  // Until every count is known, render nothing (avoids flashing a partial checklist).
  if (!sources.data || !repositories.data || !scans.data) return null;

  const counts: ChecklistCounts = {
    sources: sources.data.totalElements,
    repositories: repositories.data.totalElements,
    scans: scans.data.totalElements,
  };
  if (firstRunComplete(counts)) return null;

  const steps = firstRunSteps(counts);
  const next = nextStepKey(counts);

  return (
    <section className="checklist" aria-label="Getting started" data-testid="first-run-checklist">
      <div className="checklist-head">
        <h2 className="section-head">Get started</h2>
        <p className="muted">Three steps to your first findings.</p>
      </div>
      <ol className="checklist-steps">
        {steps.map((s, i) => (
          <li key={s.key} className={s.done ? "checklist-step done" : "checklist-step"}>
            <span className="checklist-marker" aria-hidden="true">
              {s.done ? <Icon name="check" /> : i + 1}
            </span>
            <div className="checklist-body">
              <div className="checklist-label">{s.label}</div>
              <div className="muted checklist-desc">{s.description}</div>
            </div>
            {s.done ? (
              <span className="chip chip-solved">Done</span>
            ) : s.key === next ? (
              <Link className="btn btn-primary btn-sm" to={s.to}>
                {s.cta}
              </Link>
            ) : (
              <Link className="btn btn-ghost btn-sm" to={s.to}>
                {s.cta}
              </Link>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
