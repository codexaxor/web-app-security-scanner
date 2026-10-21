import { test, expect, signIn, adminEmail, adminPassword } from "../fixtures/test-base";

// R8 regression + S3 shell: sign-in, the nav shell, the onboarding screens, and sign-out clearing everything.
test("signs in and shows the app shell with navigation", async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Feed" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Repositories" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();

  // The M1 Connect GitLab onboarding screen is still reachable and renders.
  await page.getByRole("link", { name: "Connect GitLab" }).click();
  await expect(page.getByRole("heading", { name: "Connect GitLab" })).toBeVisible();
});

test("rejects a wrong password at the gate", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill("definitely-not-" + adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
  await expect(page.getByText(/wrong email or password/i)).toBeVisible();
});

test("sign-out returns to the gate with no data and restores the route after re-auth", async ({ page }) => {
  await signIn(page);
  await page.getByRole("link", { name: "Repositories" }).click();
  await expect(page).toHaveURL(/\/repositories$/);

  await page.getByRole("button", { name: "Sign out" }).click();
  // Back at the gate; the nav (and any data) is gone.
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Repositories" })).toHaveCount(0);

  // The URL is preserved, so signing in again lands back on /repositories (route restore, change 10).
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill(adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
  await expect(page).toHaveURL(/\/repositories$/);
  await expect(page.getByRole("link", { name: "Repositories" })).toBeVisible();
});
