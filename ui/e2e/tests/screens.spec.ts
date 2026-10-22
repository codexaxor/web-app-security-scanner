import { test, signIn } from "../fixtures/test-base";

// Full-page screenshots of every screen at two desktop widths, for visual review. Saved under
// ./artifacts/screens (gitignored, bind-mounted back to the host). Not an oracle — a capture pass.

const COMMONS_TEXT = "40000000-0000-0000-0000-000000000001";

const SCREENS: [string, string][] = [
  ["dashboard", "/dashboard"],
  ["feed", "/feed"],
  ["feed-panel", `/feed/${COMMONS_TEXT}`],
  ["ignored", "/ignored"],
  ["repositories", "/repositories"],
  ["connect", "/connect"],
  ["select", "/select"],
  ["settings", "/settings"],
];

const VIEWPORTS = [
  { w: 1440, h: 900 },
  { w: 1280, h: 800 },
];

for (const vp of VIEWPORTS) {
  test.describe(`screens ${vp.w}x${vp.h}`, () => {
    test.use({ viewport: { width: vp.w, height: vp.h } });

    for (const [name, path] of SCREENS) {
      test(`${name} ${vp.w}x${vp.h}`, async ({ page }) => {
        await signIn(page, path);
        await page.waitForLoadState("networkidle");
        await page.waitForTimeout(250); // let any band bars / pills settle
        await page.screenshot({ path: `./artifacts/screens/${name}-${vp.w}x${vp.h}.png`, fullPage: true });
      });
    }
  });
}

// Explicit light + dark theme captures (via prefers-color-scheme → System theme follows it) of the key screens
// at 1440, so both token sets are reviewable (S10e item 10).
const THEMED: [string, string][] = [
  ["dashboard", "/dashboard"],
  ["feed-panel", `/feed/${COMMONS_TEXT}`],
  ["feed-secret", "/feed?type=secret"], // Feed filtered to secret findings (S10f label review)
  ["ignored", "/ignored"],
  ["repositories", "/repositories"],
];

for (const scheme of ["light", "dark"] as const) {
  test.describe(`themed ${scheme} 1440`, () => {
    test.use({ viewport: { width: 1440, height: 900 }, colorScheme: scheme });

    for (const [name, path] of THEMED) {
      test(`${name} ${scheme} 1440`, async ({ page }) => {
        await signIn(page, path);
        await page.waitForLoadState("networkidle");
        await page.waitForTimeout(250);
        await page.screenshot({ path: `./artifacts/screens/${name}-${scheme}-1440.png`, fullPage: true });
      });
    }
  });
}
