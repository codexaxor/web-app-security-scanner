import { test, expect, signIn, adminEmail, adminPassword } from "../fixtures/test-base";

// M8.6 item 9: the list → repo page → panel flow. Uses the seeded core-keycloak repo and the linkable secret
// finding (40-hex commit + file path + line) added to the fixture for the source-link assertions.
const CORE = "acme/core-keycloak";
const CORE_ID = "20000000-0000-0000-0000-000000000001";
const LINK_SECRET = "40000000-0000-0000-0000-0000000000aa";
const SHA = "1111111111111111111111111111111111111111";

test("repositories search + sort, then a row click opens the repository page", async ({ page }) => {
  await signIn(page, "/repositories");
  await page.getByRole("searchbox", { name: "Search repositories" }).fill("core-keycloak");
  const row = page.getByTestId("repo-row").filter({ hasText: CORE });
  await expect(row).toHaveCount(1);
  // Exercising the sort control keeps the filtered row (server-side sort).
  await page.getByRole("combobox", { name: "Sort" }).selectOption("name");
  await expect(row).toHaveCount(1);
  await row.click();
  await expect(page).toHaveURL(new RegExp(`/repositories/${CORE_ID}`));
  await expect(page.getByRole("heading", { name: "core-keycloak" })).toBeVisible();
});

test("the repository page tabs are URL-addressable", async ({ page }) => {
  await signIn(page, `/repositories/${CORE_ID}`);
  await expect(page.getByRole("tab", { name: "Issues" })).toHaveAttribute("aria-selected", "true");

  await page.getByRole("tab", { name: "Scans" }).click();
  await expect(page).toHaveURL(/tab=scans/);
  await expect(page.getByTestId("scan-row").first()).toBeVisible();

  await page.getByRole("tab", { name: "Settings" }).click();
  await expect(page).toHaveURL(/tab=settings/);
  await expect(page.getByLabel("Sensitivity")).toBeVisible();
});

test("an issue panel opens, navigates next/prev, and a reload keeps it open", async ({ page }) => {
  await signIn(page, `/repositories/${CORE_ID}?tab=issues`);
  await page.getByTestId("repo-issue-row").first().click();
  const panel = page.getByRole("dialog", { name: "Issue details" });
  await expect(panel).toBeVisible();
  const firstUrl = page.url();

  await panel.getByRole("button", { name: "Next issue" }).click();
  await expect(page).not.toHaveURL(firstUrl); // the open group id changed
  await panel.getByRole("button", { name: "Previous issue" }).click();
  await expect(page).toHaveURL(firstUrl);

  // D-067: a reload drops the in-memory credentials and shows the sign-in gate in place; the URL (incl.
  // ?issue) is preserved, so re-signing-in restores the route and reopens the panel.
  await page.reload();
  await page.getByLabel("Email").fill(adminEmail());
  await page.getByLabel("Password").fill(adminPassword());
  await page.getByRole("button", { name: /continue|sign in/i }).click();
  await expect(page.getByRole("dialog", { name: "Issue details" })).toBeVisible();
});

test("the secret panel renders source links with the exact expected hrefs", async ({ page }) => {
  await signIn(page, `/repositories/${CORE_ID}?tab=issues&issue=${LINK_SECRET}`);
  const panel = page.getByRole("dialog", { name: "Issue details" });
  await expect(panel).toBeVisible();

  const fileLink = panel.getByTestId("open-file-link");
  await expect(fileLink).toHaveAttribute(
    "href",
    `https://gitlab.example.com/acme/core-keycloak/-/blob/${SHA}/config/app.properties#L7`,
  );
  await expect(fileLink).toHaveAttribute("rel", "noopener noreferrer");
  await expect(panel.getByTestId("view-commit-link")).toHaveAttribute(
    "href",
    `https://gitlab.example.com/acme/core-keycloak/-/commit/${SHA}`,
  );
});

test.describe("dark mode", () => {
  test.use({ colorScheme: "dark" });
  test("the repository page renders in dark mode", async ({ page }) => {
    await signIn(page, `/repositories/${CORE_ID}`);
    await expect(page.getByRole("heading", { name: "core-keycloak" })).toBeVisible();
    const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(bg).not.toBe("rgb(255, 255, 255)"); // not the light background
  });
});
