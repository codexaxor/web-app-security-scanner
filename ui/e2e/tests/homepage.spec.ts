import { test, expect, adminEmail, adminPassword } from "../fixtures/test-base";

// M8.13 public homepage (/): the CTAs, the signed-out deep-link redirect through /login?next, and the
// open-redirect rejection. These run signed out except where they sign in with the per-run bootstrap admin.

test("homepage → Create account goes to sign-up", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("homepage")).toBeVisible();
  await page.getByRole("link", { name: "Create account" }).first().click();
  await expect(page).toHaveURL(/\/signup$/);
  await expect(page.getByTestId("signup-form")).toBeVisible();
});

test("homepage → Sign in goes to /login", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in" }).first().click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByLabel("Email")).toBeVisible();
});

test("a signed-out deep link bounces to /login?next and restores the route after sign-in", async ({ page }) => {
  await page.goto("/feed");
  await expect(page).toHaveURL(/\/login\?next=%2Ffeed$/);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill(adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();

  await expect(page).toHaveURL(/\/feed$/);
  await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
});

test("an open-redirect next is rejected — sign-in lands on /dashboard, not the attacker origin", async ({ page }) => {
  await page.goto("/login?next=" + encodeURIComponent("//evil.example"));
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill(adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();

  // The sanitizer dropped the protocol-relative target; we stay on our own origin at /dashboard.
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page).not.toHaveURL(/evil\.example/);
  await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
});

test("the homepage makes no business-API calls when signed out", async ({ page }) => {
  const apiCalls: string[] = [];
  page.on("request", (req) => {
    const p = new URL(req.url()).pathname;
    if (p.startsWith("/api/")) apiCalls.push(p);
  });
  await page.goto("/");
  await expect(page.getByTestId("homepage")).toBeVisible();
  await page.waitForTimeout(500);
  // The homepage fetches no data of its own. The one allowed request is the SPA's shared session bootstrap
  // (/api/v1/auth/session, which 401s when signed out); there are no repository/finding/settings calls.
  const business = apiCalls.filter((p) => p !== "/api/v1/auth/session");
  expect(business, `unexpected API calls on the homepage: ${business.join(", ")}`).toEqual([]);
});
