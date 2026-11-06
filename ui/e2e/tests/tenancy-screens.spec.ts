import { test, expect, signIn, forceTheme } from "../fixtures/test-base";
import { authHeader } from "../helpers/oracle";

// M8.10 S11 screenshot pass for the new tenancy pages, at 1440 and 390 in light AND dark (same capture rules as
// screens.spec.ts; saved under ./artifacts/screens). Not an oracle — a capture pass for the self-review.
//   - signup / signup-done: signed out. signup-done fulfils the POST with a canned 202 (same origin) so the
//     capture does not spend the real per-IP sign-up budget (5 per 15 min, D-098).
//   - awaiting: one REAL pending sign-up, created once; its correct-password login shows the screen.
//   - admin-signups / admin-users: the operator (bootstrap admin). A second real sign-up is approved and then
//     disabled (operator API, Basic auth) so the Users capture shows the Disabled / Re-enable state too.

const PASSWORD = "tenant-e2e-password-1";
const PENDING_EMAIL = `pending-${Date.now()}@e2e.example`;
const DISABLED_EMAIL = `disabled-${Date.now()}@e2e.example`;

const WIDTHS: [number, number][] = [
  [1440, 900],
  [390, 844],
];

test.beforeAll(async ({ request }) => {
  for (const [email, ws] of [
    [PENDING_EMAIL, "Pending Co"],
    [DISABLED_EMAIL, "Former Co"],
  ]) {
    const res = await request.post("/api/v1/auth/signup", { data: { email, password: PASSWORD, workspaceName: ws } });
    expect(res.status()).toBe(202);
  }
  const headers = { Authorization: authHeader() };
  const pending = await (await request.get("/api/v1/admin/sign-ups?status=pending&size=100", { headers })).json();
  const signup = pending.content.find((r: { email: string }) => r.email === DISABLED_EMAIL);
  expect((await request.post(`/api/v1/admin/sign-ups/${signup.id}/approve`, { headers })).status()).toBe(200);
  const users = await (await request.get("/api/v1/admin/users?size=100", { headers })).json();
  const user = users.content.find((u: { email: string }) => u.email === DISABLED_EMAIL);
  expect((await request.post(`/api/v1/admin/users/${user.id}/disable`, { headers })).status()).toBe(200);
});

for (const theme of ["light", "dark"] as const) {
  for (const [w, h] of WIDTHS) {
    test.describe(`tenancy screens ${theme} ${w}`, () => {
      test.use({ viewport: { width: w, height: h } });

      const shot = (name: string) => `./artifacts/screens/${name}-${theme}-${w}.png`;

      test(`signup ${theme} ${w}`, async ({ page }) => {
        await forceTheme(page, theme);
        await page.goto("/signup");
        await expect(page.getByTestId("signup-form")).toBeVisible();
        await page.screenshot({ path: shot("signup"), fullPage: true });

        // Field errors state (client-side validation, nothing sent).
        await page.getByRole("button", { name: "Request account" }).click();
        await expect(page.getByText("Enter a valid email address.")).toBeVisible();
        await page.screenshot({ path: shot("signup-errors"), fullPage: true });
      });

      test(`signup-done ${theme} ${w}`, async ({ page }) => {
        await forceTheme(page, theme);
        await page.route("**/api/v1/auth/signup", (route) =>
          route.fulfill({
            status: 202,
            contentType: "application/json",
            body: JSON.stringify({ status: "RECEIVED", message: "received" }),
          }),
        );
        await page.goto("/signup");
        await page.getByLabel("Work email").fill("someone@e2e.example");
        await page.getByLabel("Password").fill(PASSWORD);
        await page.getByLabel("Workspace name").fill("Someone Co");
        await page.getByRole("button", { name: "Request account" }).click();
        await expect(page.getByTestId("signup-done")).toBeVisible();
        await page.screenshot({ path: shot("signup-done"), fullPage: true });
      });

      test(`awaiting ${theme} ${w}`, async ({ page }) => {
        await forceTheme(page, theme);
        await page.goto("/login");
        await page.getByLabel("Email").fill(PENDING_EMAIL);
        await page.getByLabel("Password").fill(PASSWORD);
        await page.getByRole("button", { name: /continue|sign in/i }).click();
        await expect(page.getByTestId("account-pending")).toBeVisible();
        await page.screenshot({ path: shot("awaiting"), fullPage: true });
      });

      test(`admin-signups ${theme} ${w}`, async ({ page }) => {
        await forceTheme(page, theme);
        await signIn(page, "/admin/sign-ups");
        await expect(page.getByTestId("signup-row").first()).toBeVisible();
        await page.waitForTimeout(500);
        await page.screenshot({ path: shot("admin-signups"), fullPage: true });
      });

      test(`admin-users ${theme} ${w}`, async ({ page }) => {
        await forceTheme(page, theme);
        await signIn(page, "/admin/users");
        await expect(page.getByTestId("user-row").first()).toBeVisible();
        await page.waitForTimeout(500);
        await page.screenshot({ path: shot("admin-users"), fullPage: true });
      });
    });
  }
}
