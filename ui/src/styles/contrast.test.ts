import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

// This test runs only under vitest (a Node runtime). node:fs + process are typed by src/test-node-shims.d.ts
// (no @types/node — D-066). vitest runs with cwd at the ui/ package root.
const css = readFileSync(process.cwd() + "/src/styles/tokens.css", "utf8");

// WCAG AA contrast guard over the design tokens (design pass, item 2). Rather than render screens, we parse
// tokens.css and compute the contrast ratio for every foreground/background pair the UI actually renders, in
// BOTH the light (:root) and dark (:root[data-theme="dark"]) token sets. Body/secondary text must reach AA for
// normal text (>= 4.5:1); colored chips/pills are bold "strong" text and must reach AA large (>= 3:1). This
// complements the runtime Playwright check (s10e) with a fast, exhaustive unit check.

/** Parse the `--name: #hex;` declarations inside the FIRST `{ ... }` that follows `selector`. */
function tokenBlock(selector: string): Record<string, string> {
  const start = css.indexOf(selector);
  if (start < 0) throw new Error(`token block not found: ${selector}`);
  const open = css.indexOf("{", start);
  const close = css.indexOf("}", open);
  const body = css.slice(open + 1, close);
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/(--[\w-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g)) {
    out[m[1]] = m[2];
  }
  return out;
}

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}
function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const r = (n >> 16) & 0xff;
  const g = (n >> 8) & 0xff;
  const b = n & 0xff;
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}
function contrast(fg: string, bg: string): number {
  const l1 = luminance(fg);
  const l2 = luminance(bg);
  const [hi, lo] = l1 >= l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

// Normal text pairs: AA normal (>= 4.5:1).
const TEXT_PAIRS: [string, string][] = [
  ["--text", "--surface"],
  ["--text-2", "--surface"],
  ["--muted", "--surface"],
  ["--text", "--bg"],
  ["--text", "--surface-2"],
  ["--error-text", "--error-bg"],
];

// Bold chip/pill/link pairs: AA large/strong (>= 3:1).
const STRONG_PAIRS: [string, string][] = [
  ["--accent", "--surface"],
  ["--status-new", "--status-new-bg"],
  ["--status-todo", "--status-todo-bg"],
  ["--status-ignored", "--status-ignored-bg"],
  ["--status-solved", "--status-solved-bg"],
  ["--sev-critical", "--sev-critical-bg"],
  ["--sev-high", "--sev-high-bg"],
  ["--sev-medium", "--sev-medium-bg"],
  ["--sev-low", "--sev-low-bg"],
  // L4: the Feed band-bar medium amber must stay a clean, AA-contrast color against the card surface.
  ["--sev-medium", "--surface"],
];

// Shell/brand chrome (M8.11): the navy sidebar + top bar are CONSTANT across light/dark (defined once in
// :root, never overridden by the dark blocks), so their contrast is checked once against :root. shell-text /
// shell-muted are normal text on navy (>= 4.5:1); the active-item text and the accent indicator are strong
// (>= 3:1).
const SHELL_TEXT_PAIRS: [string, string][] = [
  ["--shell-text", "--shell-bg"],
  ["--shell-muted", "--shell-bg"],
];
const SHELL_STRONG_PAIRS: [string, string][] = [
  ["--shell-active-text", "--shell-active-bg"],
  ["--shell-accent", "--shell-bg"],
];

describe("WCAG AA contrast — shell (navy, constant across themes)", () => {
  const t = tokenBlock(":root {");
  it.each(SHELL_TEXT_PAIRS)("normal text %s on %s is >= 4.5:1", (fg, bg) => {
    expect(t[fg], fg).toBeTruthy();
    expect(t[bg], bg).toBeTruthy();
    expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(4.5);
  });
  it.each(SHELL_STRONG_PAIRS)("strong text %s on %s is >= 3:1", (fg, bg) => {
    expect(t[fg], fg).toBeTruthy();
    expect(t[bg], bg).toBeTruthy();
    expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(3);
  });
});

for (const [theme, selector] of [
  ["light", ":root {"],
  ["dark", ':root[data-theme="dark"] {'],
] as const) {
  describe(`WCAG AA contrast — ${theme} tokens`, () => {
    const t = tokenBlock(selector);

    it.each(TEXT_PAIRS)("normal text %s on %s is >= 4.5:1", (fg, bg) => {
      expect(t[fg], fg).toBeTruthy();
      expect(t[bg], bg).toBeTruthy();
      expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(4.5);
    });

    it.each(STRONG_PAIRS)("strong text %s on %s is >= 3:1", (fg, bg) => {
      expect(t[fg], fg).toBeTruthy();
      expect(t[bg], bg).toBeTruthy();
      expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(3);
    });
  });
}

// M8.11 review N-5: the issue-type colours are GRAPHICS (Dashboard type-bar icons + fills, the repository Issues
// type icon), so WCAG 1.4.11 non-text contrast applies: >= 3:1 against what they sit on — the card/row surface,
// and the bar track / hovered row (--surface-2) — in both themes.
const TYPES = ["sca", "sast", "secret", "iac", "eol"] as const;
const GRAPHIC_PAIRS: [string, string][] = TYPES.flatMap((ty) => [
  [`--type-${ty}`, "--surface"],
  [`--type-${ty}`, "--surface-2"],
] as [string, string][]);

for (const [theme, selector] of [
  ["light", ":root {"],
  ["dark", ':root[data-theme="dark"] {'],
] as const) {
  describe(`WCAG non-text contrast — ${theme} issue-type colours`, () => {
    const t = tokenBlock(selector);
    it.each(GRAPHIC_PAIRS)("graphic %s on %s is >= 3:1", (fg, bg) => {
      expect(t[fg], fg).toBeTruthy();
      expect(t[bg], bg).toBeTruthy();
      expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(3);
    });
  });
}

// M8.11 review N-4 (D-099): dark is ONLY an explicit [data-theme="dark"]; the OS preference is never followed,
// so there must be no prefers-color-scheme block that could paint dark before the app sets data-theme.
describe("theme selection is explicit only (N-4)", () => {
  it("tokens.css has no @media (prefers-color-scheme) rule", () => {
    const code = css.replace(/\/\*[\s\S]*?\*\//g, "");
    expect(code).not.toMatch(/@media\s*\([^)]*prefers-color-scheme/);
  });
});
