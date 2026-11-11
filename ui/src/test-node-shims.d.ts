// Minimal ambient Node declarations for the few Node APIs used by *test* files that read source from disk
// (e.g. the token-contrast test). The UI build has no @types/node (D-066: a locked-down, browser-only SPA),
// and these tests only ever run under vitest (a Node runtime). Declaring just what we use keeps tsc happy
// without pulling the whole Node global surface into the app's type scope. Not bundled by the vite build.
declare module "node:fs" {
  export function readFileSync(path: string, encoding: "utf8"): string;
}
declare const process: { cwd(): string };
