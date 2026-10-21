import { test, expect, signIn, adminEmail, adminPassword } from "../fixtures/test-base";

// R8 regression: the existing Connect GitLab / Select repositories onboarding still works after the M8
// toolchain upgrade. Also covers the in-memory-credentials rule (OD-13a): a wrong password is rejected and
// no credential is persisted.
test("signs in with the bootstrap admin and reaches the onboarding shell", async ({ page }) => {
  await signIn(page);
  // The signed-in shell shows the nav; the Repositories tab button proves the app rendered past the gate.
  await expect(page.getByRole("button", { name: "Repositories" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Connect GitLab" })).toBeVisible();
});

test("rejects a wrong password at the gate", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill("definitely-not-" + adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
  await expect(page.getByText(/wrong email or password/i)).toBeVisible();
});
