import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { invalidateFor, qk } from "../queryClient";
import ErrorBanner from "../components/ErrorBanner";
import Toggle from "../components/Toggle";
import { Card, PageHeader } from "../components/ui";

/**
 * Workspace settings (S7 / R5): the EPSS auto-ignore toggle (D-060). Turning it on auto-ignores low-EPSS
 * findings (LOW_EPSS) and applies the EPSS scoring tiers; the change is reflected in the Feed and Ignored counts
 * after refetch (the mutation invalidates those families).
 */
export default function SettingsPage() {
  const settings = useQuery({ queryKey: qk.workspaceSettings, queryFn: () => api.getWorkspaceSettings() });

  const save = useMutation({
    mutationFn: (enabled: boolean) => api.putWorkspaceSettings({ epssAutoIgnoreEnabled: enabled }),
    onSuccess: async () => {
      await invalidateFor("workspaceSettings");
    },
  });

  // While saving show the in-flight value; once saved prefer the PUT response (no flip-back before the refetch).
  const enabled =
    (save.isPending
      ? save.variables
      : (save.data?.epssAutoIgnoreEnabled ?? settings.data?.epssAutoIgnoreEnabled)) ?? false;

  return (
    <section>
      <PageHeader title="Settings" subtitle="Workspace-wide scanning and triage preferences." />
      {settings.error ? <ErrorBanner error={settings.error} /> : null}
      {save.error ? <ErrorBanner error={save.error} onDismiss={() => save.reset()} /> : null}

      <Card className="settings-card">
        <div className="row-between">
          <div>
            <div className="field-label">EPSS auto-ignore</div>
            <p className="muted">
              Auto-ignore findings with EPSS below 1% that are not on CISA KEV, and apply the EPSS scoring tiers.
            </p>
          </div>
          <Toggle
            checked={enabled}
            disabled={settings.isLoading || save.isPending}
            onChange={(next) => save.mutate(next)}
            label="EPSS auto-ignore"
            testid="epss-toggle"
          />
        </div>
        <p className="muted consequence">
          {enabled
            ? "On — low-EPSS findings are ignored as LOW_EPSS and re-evaluated on each scan and immediately on toggle."
            : "Off — low-EPSS findings stay in the feed and no EPSS scoring adjustment is applied."}
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
