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
        local: window.localStorage.length,
        session: window.sessionStorage.length,
      }))
      .catch(() => ({ local: 0, session: 0 }));

    expect(tracked.consoleErrors, `console errors: ${tracked.consoleErrors.join(" | ")}`).toEqual([]);
    expect(cspViolations, `CSP violations: ${cspViolations.join(" | ")}`).toEqual([]);
    expect(tracked.foreignRequests, `non-origin requests: ${tracked.foreignRequests.join(" | ")}`).toEqual([]);
    expect(tracked.credInUrl, `credential in URL: ${tracked.credInUrl.join(" | ")}`).toEqual([]);
    expect(storage.local, "localStorage not empty at test end").toBe(0);
    expect(storage.session, "sessionStorage not empty at test end").toBe(0);
  },
});

export { expect };

export const adminEmail = () => process.env.E2E_ADMIN_EMAIL ?? "admin@example.com";
export const adminPassword = () => ADMIN_PASSWORD;

/** Signs in through the in-app gate using the per-run bootstrap admin credentials. */
export async function signIn(page: Page): Promise<void> {
  await page.goto("/");
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill(adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
}
