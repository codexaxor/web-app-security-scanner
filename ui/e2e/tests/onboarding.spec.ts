import { test, expect, signIn, openUserMenu, adminEmail, adminPassword } from "../fixtures/test-base";

// R8 regression + S3 shell: sign-in, the nav shell, the onboarding screens, and sign-out clearing everything.
test("signs in and shows the app shell with navigation", async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Feed" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Repositories", exact: true })).toBeVisible();
  // Sign out moved into the user menu (M8.11); open it to confirm the action is present.
  await openUserMenu(page);
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await page.keyboard.press("Escape");

  // The Sources onboarding screen is still reachable and renders. The sidebar nav item and page heading are
  // "Sources" (M8.7); it lists connections and the add-source forms.
  await page.getByRole("link", { name: "Sources", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Sources", exact: true })).toBeVisible();
});

test("rejects a wrong password at the gate", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill("definitely-not-" + adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
  await expect(page.getByText(/wrong email or password/i)).toBeVisible();
});

// M8.13 (D-103, owner decision 2026-11-07): an explicit sign-out lands on plain /login with no ?next, so re-auth
// starts on /dashboard — this supersedes M8 change 10's "route restore after sign-out" for an explicit sign-out
// only. Deep-link and expired-session restore via ?next remain (homepage.spec "a signed-out deep link bounces to
// /login?next and restores the route after sign-in").
test("sign-out returns to the gate with no data and re-auth starts on the dashboard", async ({ page }) => {
  await signIn(page);
  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  await expect(page).toHaveURL(/\/repositories$/);

  await openUserMenu(page);
  await page.getByRole("button", { name: "Sign out" }).click();
  // Back at the gate on plain /login (no ?next); the nav (and any data) is gone.
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole("link", { name: "Repositories", exact: true })).toHaveCount(0);

  // Signing in again starts on the dashboard, not the previous page.
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill(adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("link", { name: "Repositories", exact: true })).toBeVisible();
});
