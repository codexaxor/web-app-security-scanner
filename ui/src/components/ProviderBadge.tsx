import { providerMeta } from "../lib/providerMeta";

/**
 * Accessible provider badge: an inline monogram keyed by data-provider (no icon library, D-066), styled with
 * plain CSS. Used on the Sources cards, the Repositories list and the Feed rows (M8.7).
 */
export default function ProviderBadge({ provider }: { provider: string | null | undefined }) {
  const m = providerMeta(provider);
  return (
    <span className="provider-badge" data-provider={m.id} title={m.label} aria-label={m.label}>
      {m.monogram}
    </span>
  );
}
