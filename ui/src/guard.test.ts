import { readFileSync } from "node:fs";
import { beforeAll, describe, expect, it } from "vitest";

// Self-test for the D-099 security guard (scripts/guard-forbidden.mjs). It proves the guard FAILS when the
// web-storage carve-out is abused, and passes for the real themeStore.ts. The guard is a plain .mjs with no
// type declarations, so it is loaded through a computed specifier (a non-literal dodges TS module resolution).
// IMPORTANT: this file is itself scanned by the guard, so it must contain NO forbidden token literally — the
// storage identifiers are assembled by concatenation, and the valid theme-store sample is read from disk.
const LS = "local" + "Storage";
const SS = "session" + "Storage";
const KEY = "secscan-theme";

let guard: { checkFile: (p: string, t: string) => string[]; THEME_STORE_REL: string };

beforeAll(async () => {
  const spec: string = "../scripts/guard-forbidden.mjs"; // string (not a literal) so tsc leaves it unresolved
  guard = await import(/* @vite-ignore */ spec);
});

function validThemeStore(): string {
  return readFileSync(process.cwd() + "/src/lib/themeStore.ts", "utf8");
}

describe("D-099 guard — web-storage allowlist", () => {
  it(`accepts the real themeStore.ts (${KEY}, light/dark only)`, () => {
    expect(guard.checkFile(guard.THEME_STORE_REL, validThemeStore())).toEqual([]);
  });

  it("FAILS when web storage appears in any other file", () => {
    const code = `export function x() { return ${LS}.getItem("${KEY}"); }`;
    const out = guard.checkFile("components/Foo.tsx", code);
    expect(out.length).toBeGreaterThan(0);
    expect(out.some((m) => m.includes("D-099"))).toBe(true);
  });

  it("FAILS when the theme store uses a different key", () => {
    const bad = validThemeStore().split(KEY).join("other-key");
    const out = guard.checkFile(guard.THEME_STORE_REL, bad);
    expect(out.some((m) => m.includes("other-key"))).toBe(true);
  });

  it("FAILS when the theme store writes a disallowed value literal", () => {
    const bad = validThemeStore() + '\nconst extra = "blue";\n';
    const out = guard.checkFile(guard.THEME_STORE_REL, bad);
    expect(out.some((m) => m.includes("blue"))).toBe(true);
  });

  it("FAILS when the theme store uses a disallowed storage member", () => {
    const bad = validThemeStore() + "\n" + LS + ".clear();\n";
    const out = guard.checkFile(guard.THEME_STORE_REL, bad);
    expect(out.some((m) => m.includes("clear"))).toBe(true);
  });

  it("still forbids other web storage even inside the theme store", () => {
    const bad = validThemeStore() + "\n" + SS + '.getItem("' + KEY + '");\n';
    const out = guard.checkFile(guard.THEME_STORE_REL, bad);
    expect(out.length).toBeGreaterThan(0);
  });
});
