import { Link } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { invalidateFor, qk } from "../queryClient";
import InlineError from "../components/InlineError";
import { useToast, errorMessage } from "../toast";
import Toggle from "../components/Toggle";
import { Card, PageHeader } from "../components/ui";

/**
 * Workspace settings (S7 / R5): the EPSS auto-ignore toggle (D-060). Turning it on auto-ignores low-EPSS
 * findings (LOW_EPSS) and applies the EPSS scoring tiers; the change is reflected in the Feed and Ignored counts
 * after refetch (the mutation invalidates those families).
 */
export default function SettingsPage() {
  const toast = useToast();
  const settings = useQuery({ queryKey: qk.workspaceSettings, queryFn: () => api.getWorkspaceSettings() });

  const save = useMutation({
    mutationFn: (enabled: boolean) => api.putWorkspaceSettings({ epssAutoIgnoreEnabled: enabled }),
    onSuccess: async () => {
      await invalidateFor("workspaceSettings");
    },
    onError: (e) => toast.error(errorMessage(e, "Could not save the setting.")),
  });

  // While saving show the in-flight value; once saved prefer the PUT response (no flip-back before the refetch).
  const enabled =
    (save.isPending
      ? save.variables
      : (save.data?.epssAutoIgnoreEnabled ?? settings.data?.epssAutoIgnoreEnabled)) ?? false;

  return (
    <section>
      <PageHeader title="Workspace settings" subtitle="Settings that apply to every repository in this workspace." />
      {settings.error ? <InlineError error={settings.error} /> : null}

      <Card className="settings-card">
        <div className="row-between">
          <div>
            <div className="field-label">Hide low-risk vulnerabilities (EPSS)</div>
            <p className="muted">
              EPSS is a published score that estimates how likely a vulnerability is to be exploited. When this is
              on, findings with a very low exploit likelihood (under 1%) are hidden from the feed — unless they are
              on CISA's Known Exploited Vulnerabilities list, which are always shown.
            </p>
          </div>
          <Toggle
            checked={enabled}
            disabled={settings.isLoading || save.isPending}
            onChange={(next) => save.mutate(next)}
            label="Hide low-risk vulnerabilities"
            testid="epss-toggle"
          />
        </div>
        <p className="muted consequence">
          {enabled
            ? "On — low-likelihood findings are hidden (reason LOW_EPSS) and re-checked on every scan."
            : "Off — every finding stays in the feed, whatever its exploit likelihood."}
        </p>
        <p>
          <Link to="/ignored?reason=LOW_EPSS" data-testid="low-epss-link">
            View findings ignored by this rule
          </Link>
        </p>
        {save.isSuccess ? (
          <p className="muted" data-testid="settings-saved">
            Saved.
          </p>
        ) : null}
      </Card>
    </section>
  );
}
