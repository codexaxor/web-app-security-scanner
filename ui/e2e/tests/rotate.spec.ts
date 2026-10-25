import { test, expect, signIn } from "../fixtures/test-base";

// M8.1 (D-078): the per-connection "Rotate token" dialog drives the existing PUT and never shows the token after
// submit. The e2e stack has no GitLab, so the PUT is stubbed to succeed (the brief's "stubbed GitLab" approach);
// the backend rotation path is covered by GitLabConnectionIT.
const SECRET = "glpat-rotate-e2e-secret-token";

test("the Rotate token dialog sends the PUT and never shows the token after submit", async ({ page }) => {
  let putSent = false;
  await page.route(/\/api\/v1\/gitlab\/connections\/[0-9a-f-]{36}$/, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    putSent = true;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        ok: true,
        targetType: "group",
        gitlabVersion: "17.0",
        tokenScopes: ["read_api", "read_repository"],
        tokenExpiresAt: "2027-01-01",
        expiringSoon: false,
        errorCode: null,
        message: null,
      }),
    });
  });

  await signIn(page, "/connect");
  await expect(page.getByRole("heading", { name: "GitLab connections" })).toBeVisible();

  const row = page.getByTestId("connection-row").first();
  await row.getByRole("button", { name: "Rotate token" }).click();

  const dialog = page.getByRole("dialog");
  const tokenInput = dialog.getByLabel("New access token");
  await tokenInput.fill(SECRET);
  await expect(tokenInput).toHaveAttribute("type", "password");
  await dialog.screenshot({ path: "./artifacts/screens/connect-rotate-dialog.png" });

  await dialog.getByRole("button", { name: "Rotate", exact: true }).click();

  // The PUT was sent, the success state shows the new scopes, and the token field is gone (never shown after).
  await expect.poll(() => putSent).toBe(true);
  await expect(dialog.getByTestId("rotate-result")).toContainText("read_api");
  await expect(dialog.getByLabel("New access token")).toHaveCount(0);
  await expect(dialog).not.toContainText(SECRET);
});
