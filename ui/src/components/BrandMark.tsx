/**
 * SecScan brand mark (M8.11) — a simple, original inline SVG: a shield (security) with a scan/verify check.
 * Stroke-based with currentColor so it is legible on the navy shell (near-white), on the light sign-in screen
 * (dark) and in dark mode, without a bitmap or external asset. It deliberately resembles no third-party logo.
 * Decorative — the adjacent "SecScan" wordmark is the accessible name, so this is aria-hidden.
 */
export default function BrandMark({ className, size = 22 }: { className?: string; size?: number }) {
  return (
    <svg
      className={["brand-mark", className ?? ""].filter(Boolean).join(" ")}
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M12 2.6l7.2 2.5v5.6c0 4.6-3.1 8.4-7.2 10.1-4.1-1.7-7.2-5.5-7.2-10.1V5.1L12 2.6Z" />
      <polyline points="8.6 12.2 11 14.6 15.6 9.2" />
    </svg>
  );
}
