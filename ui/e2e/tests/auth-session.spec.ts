import { test, expect, signIn } from "../fixtures/test-base";

// D-092: the browser authenticates with a server session cookie, so a reload keeps the user signed in (unlike
// the old in-memory Basic credentials). Signing out and reloading returns to the gate. No credential or token is
// ever written to web storage (the test-base end-of-test assertions enforce empty local/sessionStorage).
test("a reload keeps the user signed in; sign out then reload shows the gate", async ({ page }) => {
  await signIn(page, "/repositories");
  await expect(page.getByRole("link", { name: "Repositories", exact: true })).toBeVisible();

  // Reload → the session cookie resumes the session (the SPA calls /auth/session on load). No re-sign-in.
  await page.reload();
  await expect(page.getByRole("link", { name: "Repositories", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Sign in" })).toHaveCount(0);

  // Sign out → the gate; and it stays the gate across a reload (the session is gone).
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Repositories", exact: true })).toHaveCount(0);
});
