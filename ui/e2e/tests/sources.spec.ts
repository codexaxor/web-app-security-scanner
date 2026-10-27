import { test, expect, signIn } from "../fixtures/test-base";

// M8.7 item 9: the Sources page (Connect→Sources) lists provider-tagged connections and offers add-forms for
// GitLab, GitHub and a public repository; the Repositories list shows a provider badge per row. The seeded data
// is GitLab, so provider badges assert data-provider="gitlab"; adding a live GitHub/public source is the
// end-of-train manual walkthrough (D-086).

test("the Sources page lists provider-tagged connections and offers add-forms", async ({ page }) => {
  await signIn(page, "/connect");
  await expect(page.getByRole("heading", { name: "Sources", exact: true })).toBeVisible();

  // Each connection card carries a provider badge; the seeded connection is GitLab.
  const badge = page.getByTestId("connection-row").first().locator(".provider-badge");
  await expect(badge).toHaveAttribute("data-provider", "gitlab");
  await expect(badge).toHaveAttribute("aria-label", "GitLab");

  // The GitHub and public-repository add-forms are present.
  await expect(page.getByTestId("add-github-form")).toBeVisible();
  await expect(page.getByTestId("add-public-form")).toBeVisible();
  await expect(page.getByLabel("Fine-grained PAT (read-only Contents + Metadata)")).toBeVisible();
  await expect(page.getByLabel("Repository URL (https)")).toBeVisible();
});

test("the Repositories list shows a provider badge per row", async ({ page }) => {
  await signIn(page, "/repositories");
  const badge = page.getByTestId("repo-row").first().locator(".provider-badge");
  await expect(badge).toHaveAttribute("data-provider", "gitlab");
});
