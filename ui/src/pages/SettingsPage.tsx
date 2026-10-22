import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { invalidateFor, qk } from "../queryClient";
import ErrorBanner from "../components/ErrorBanner";

/**
 * Workspace settings (S7 / R5): the EPSS auto-ignore toggle (D-060). Turning it on auto-ignores low-EPSS
 * findings (LOW_EPSS) and applies the EPSS scoring tiers; the change is reflected in the Feed and Ignored counts
 * after refetch (the mutation invalidates those families).
 */
export default function SettingsPage() {
  const settings = useQuery({
    queryKey: qk.workspaceSettings,
    queryFn: () => api.getWorkspaceSettings(),
  });

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
      <h1>Settings</h1>
      {settings.error ? <ErrorBanner error={settings.error} /> : null}
      {save.error ? <ErrorBanner error={save.error} onDismiss={() => save.reset()} /> : null}

      <div className="card" style={{ maxWidth: 560 }}>
        <label className="row">
          <input
            type="checkbox"
            checked={enabled}
            disabled={settings.isLoading || save.isPending}
            onChange={(e) => save.mutate(e.target.checked)}
            data-testid="epss-toggle"
          />
          <strong>EPSS auto-ignore</strong>
        </label>
        <p className="muted">
          When on, findings with EPSS below 1% (and not on CISA KEV) are auto-ignored as <code>LOW_EPSS</code>,
          and the EPSS scoring tiers (−10 / −5) apply. Re-evaluated on the next scan and immediately on toggle.
        </p>
        {save.isSuccess ? <p className="muted" data-testid="settings-saved">Saved.</p> : null}
      </div>
    </section>
  );
}
