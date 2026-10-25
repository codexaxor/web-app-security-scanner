import { test, expect, signIn } from "../fixtures/test-base";

// M8.1 (D-078): the per-connection "Rotate token" dialog drives the existing PUT and never shows the token after
// submit. The e2e stack has no GitLab, so the PUT is stubbed (the brief's "stubbed GitLab" approach); the backend
// rotation path is covered by GitLabConnectionIT.
const SECRET = "glpat-rotate-e2e-secret-token";

test("the Rotate token dialog sends the PUT and never shows the token after submit", async ({ page }) => {
  let putBody: Record<string, unknown> | null = null;
  await page.route(/\/api\/v1\/gitlab\/connections\/[0-9a-f-]{36}$/, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    putBody = route.request().postDataJSON();
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        ok: true, targetType: "group", gitlabVersion: "17.0",
        tokenScopes: ["read_api", "read_repository"], tokenExpiresAt: "2027-01-01",
        expiringSoon: false, errorCode: null, message: null,
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

  await dialog.getByRole("button", { name: "Rotate", exact: true }).click();

  // The PUT carries {baseUrl, token, path} (token in the REQUEST is expected)...
  await expect.poll(() => putBody && Object.keys(putBody).sort().join(",")).toBe("baseUrl,path,token");
  // ...but the token is never echoed back in the UI; the success state shows only the new scopes/expiry.
  await expect(dialog.getByTestId("rotate-result")).toContainText("read_api");
  await expect(dialog.getByLabel("New access token")).toHaveCount(0);
  await expect(dialog).not.toContainText(SECRET);
});

test("a target-mismatch rotation shows an inline error, clears the token, and does not sign out", async ({ page }) => {
  await page.route(/\/api\/v1\/gitlab\/connections\/[0-9a-f-]{36}$/, async (route) => {
    if (route.request().method() !== "PUT") return route.fallback();
    await route.fulfill({
      status: 409,
      contentType: "application/json",
      body: JSON.stringify({ error: "CONNECTION_TARGET_MISMATCH", message: "This token resolves a different target." }),
    });
  });

  await signIn(page, "/connect");
  const row = page.getByTestId("connection-row").first();
  await row.getByRole("button", { name: "Rotate token" }).click();

  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("New access token").fill(SECRET);
  await dialog.getByRole("button", { name: "Rotate", exact: true }).click();

  // Inline error, token field emptied (dropped from state), still on Connect (no drop to the sign-in gate).
  await expect(dialog.getByText(/different target/i)).toBeVisible();
  await expect(dialog.getByLabel("New access token")).toHaveValue("");
  await expect(dialog).not.toContainText(SECRET);
  await expect(page.getByRole("heading", { name: "GitLab connections" })).toBeVisible();
});
