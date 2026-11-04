import { test as base, expect, type Page } from "@playwright/test";

// Global security assertions applied to EVERY test (harness spec): zero console errors, zero CSP violations,
// zero requests to any origin other than the ui origin, no credential in any URL, and empty web storage at the
// end. Tests import { test, expect } from this module instead of @playwright/test.

const BASE_URL = process.env.E2E_BASE_URL ?? "http://ui:80";
const BASE_ORIGIN = new URL(BASE_URL).origin;
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "";

type Tracked = { consoleErrors: string[]; foreignRequests: string[]; credInUrl: string[] };

export const test = base.extend<{ tracked: Tracked }>({
  tracked: async ({ page }, use) => {
    const tracked: Tracked = { consoleErrors: [], foreignRequests: [], credInUrl: [] };

    // Capture CSP violations in-page (securitypolicyviolation) before any app code runs.
    await page.addInitScript(() => {
      (window as unknown as { __csp: string[] }).__csp = [];
      document.addEventListener("securitypolicyviolation", (e) => {
        (window as unknown as { __csp: string[] }).__csp.push(
          `${e.violatedDirective} blocked ${e.blockedURI}`,
        );
      });
    });

    page.on("console", (msg) => {
      if (msg.type() === "error") tracked.consoleErrors.push(msg.text());
    });
    page.on("pageerror", (err) => tracked.consoleErrors.push(String(err)));

    page.on("request", (req) => {
      const url = req.url();
      if (url.startsWith("data:") || url.startsWith("about:")) return;
      if (new URL(url).origin !== BASE_ORIGIN) tracked.foreignRequests.push(url);
      if (ADMIN_PASSWORD && url.includes(ADMIN_PASSWORD)) tracked.credInUrl.push(url);
      if (/\/\/[^/]*:[^/]*@/.test(url)) tracked.credInUrl.push(url); // userinfo in URL
    });

    await use(tracked);

    // End-of-test security assertions.
    const cspViolations: string[] = await page
      .evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? [])
      .catch(() => []);
    const storage = await page
      .evaluate(() => ({
        localKeys: Object.keys(window.localStorage),
        theme: window.localStorage.getItem("secscan-theme"),
        session: window.sessionStorage.length,
      }))
      .catch(() => ({ localKeys: [] as string[], theme: null as string | null, session: 0 }));

    expect(tracked.consoleErrors, `console errors: ${tracked.consoleErrors.join(" | ")}`).toEqual([]);
    expect(cspViolations, `CSP violations: ${cspViolations.join(" | ")}`).toEqual([]);
    expect(tracked.foreignRequests, `non-origin requests: ${tracked.foreignRequests.join(" | ")}`).toEqual([]);
    expect(tracked.credInUrl, `credential in URL: ${tracked.credInUrl.join(" | ")}`).toEqual([]);
    // D-099: the ONLY permitted web-storage entry is the theme preference (light|dark) — never a credential or
    // token, and sessionStorage stays empty. This keeps the no-secrets-in-storage guarantee while allowing the
    // one approved key.
    const unexpected = storage.localKeys.filter((k) => k !== "secscan-theme");
    expect(unexpected, `unexpected localStorage keys: ${unexpected.join(", ")}`).toEqual([]);
    if (storage.localKeys.includes("secscan-theme")) {
      expect(["light", "dark"], `theme value: ${storage.theme}`).toContain(storage.theme);
    }
    expect(storage.session, "sessionStorage not empty at test end").toBe(0);
  },
});

export { expect };

export const adminEmail = () => process.env.E2E_ADMIN_EMAIL ?? "admin@example.com";
export const adminPassword = () => ADMIN_PASSWORD;

/** Open the sidebar user menu (M8.11) — the theme toggle and Sign out live inside it. */
export async function openUserMenu(page: Page): Promise<void> {
  await page.getByRole("button", { name: /Account/ }).click();
}

/**
 * Force the persisted theme (D-099 single key `secscan-theme`) BEFORE the first navigation, so a page loads
 * directly in that theme. Must be called before signIn/goto. The UI no longer follows prefers-color-scheme.
 */
export async function forceTheme(page: Page, theme: "light" | "dark"): Promise<void> {
  await page.addInitScript((t) => {
    try {
      localStorage.setItem("secscan-theme", t);
    } catch {
      /* ignore */
    }
  }, theme);
}

/**
 * Signs in through the in-app gate using the per-run bootstrap admin credentials. Navigates to `path` FIRST (a
 * full load that wipes the in-memory credentials), then signs in there — the gate preserves the URL, so the
 * requested route renders after sign-in (route restore). Credentials are never reloaded across a page.goto.
 */
export async function signIn(page: Page, path = "/"): Promise<void> {
  await page.goto(path);
  // D-092: the session cookie may already be active (a second signIn in the same context, e.g. a light/dark
  // loop), so sign in only when the gate is actually showing. Wait for the app to settle into one or the other.
  await page.waitForSelector('#signin-email, nav[aria-label="Primary"]', { timeout: 15000 });
  if ((await page.locator("#signin-email").count()) > 0) {
    await page.getByLabel("Email").fill(adminEmail());
    await page.getByLabel("Password").fill(adminPassword());
    await page.getByRole("button", { name: /continue|sign in/i }).click();
    await page.waitForSelector('nav[aria-label="Primary"]', { timeout: 15000 });
  }
}
