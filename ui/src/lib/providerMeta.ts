// Provider → display label + monogram for the provider badge (M8.7). No icon library (D-066): the badge is an
// accessible inline element keyed by `id` (data-provider), styled with plain CSS.

export interface ProviderMeta {
  id: string;
  label: string;
  monogram: string;
}

export function providerMeta(provider: string | null | undefined): ProviderMeta {
  switch ((provider ?? "").toLowerCase()) {
    case "github":
      return { id: "github", label: "GitHub", monogram: "GH" };
    case "gitlab":
      return { id: "gitlab", label: "GitLab", monogram: "GL" };
    default:
      return { id: "other", label: provider && provider.trim() ? provider : "Unknown", monogram: "·" };
  }
}

/** A short human label for a connection's target kind, for the Sources cards. */
export function targetLabel(targetType: string | null | undefined): string {
  switch ((targetType ?? "").toLowerCase()) {
    case "group":
      return "Group";
    case "project":
      return "Project";
    case "owner":
      return "Owner";
    case "repo":
      return "Repository";
    default:
      return targetType ?? "";
  }
}
