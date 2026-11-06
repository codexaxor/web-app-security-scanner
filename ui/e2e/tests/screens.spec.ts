import { test, signIn, forceTheme } from "../fixtures/test-base";

// M8.11 S10 screenshot pass: every main page at 1440 and 390 (phone), in light AND dark. Saved under
// ./artifacts/screens (gitignored, bind-mounted to the host). Not an oracle — a capture pass. The theme is set
// via the D-099 `secscan-theme` key (the UI no longer follows prefers-color-scheme), so dark captures are real.

const COMMONS_TEXT = "40000000-0000-0000-0000-000000000001"; // an issue group → the feed side panel
const CORE_ID = "20000000-0000-0000-0000-000000000001"; // core-keycloak repository

// [name, path, needsSignIn]
const SCREENS: [string, string, boolean][] = [
  ["home", "/", false],
  ["login", "/login", false],
  ["dashboard", "/dashboard", true],
  ["feed", "/feed", true],
  ["feed-panel", `/feed/${COMMONS_TEXT}`, true],
  ["ignored", "/ignored", true],
  ["repositories", "/repositories", true],
  ["repository", `/repositories/${CORE_ID}`, true],
  ["sources", "/connect", true],
  ["select", "/select", true],
  ["settings", "/settings", true],
];

const WIDTHS: [number, number][] = [
  [1440, 900],
  [390, 844],
];

for (const theme of ["light", "dark"] as const) {
  for (const [w, h] of WIDTHS) {
    test.describe(`screens ${theme} ${w}`, () => {
      test.use({ viewport: { width: w, height: h } });

      for (const [name, path, needsSignIn] of SCREENS) {
        test(`${name} ${theme} ${w}`, async ({ page }) => {
          await forceTheme(page, theme);
          if (needsSignIn) {
            await signIn(page, path);
          } else {
            await page.goto(path);
          }
          await page.waitForLoadState("domcontentloaded");
          await page.waitForTimeout(700); // let band bars / pills / skeletons settle
          await page.screenshot({ path: `./artifacts/screens/${name}-${theme}-${w}.png`, fullPage: true });
        });
      }
    });
  }
}
