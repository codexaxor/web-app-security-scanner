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

// SF-1: the seeded GitHub repo (octo/widget) proves a GitHub source appears in the list with the GitHub badge,
// and the URL-addressable provider filter narrows both the Repositories list and the Feed (seeded, no live GitHub).
const GH_REPO = "octo/widget";

test("a GitHub source shows with its badge and the provider filter narrows the Repositories list", async ({
  page,
}) => {
  await signIn(page, "/repositories");
  const ghRow = page.getByTestId("repo-row").filter({ hasText: GH_REPO });
  await expect(ghRow).toHaveCount(1);
  await expect(ghRow.locator(".provider-badge")).toHaveAttribute("data-provider", "github");

  // Provider = GitHub → the GitHub repo stays, a known GitLab repo is gone; the filter is URL-addressable.
  await page.getByRole("combobox", { name: "Provider" }).selectOption("github");
  await expect(page).toHaveURL(/provider=github/);
  await expect(page.getByTestId("repo-row").filter({ hasText: GH_REPO })).toHaveCount(1);
  await expect(page.getByTestId("repo-row").filter({ hasText: "acme/core-keycloak" })).toHaveCount(0);

  // Provider = GitLab → the GitHub repo is excluded.
  await page.getByRole("combobox", { name: "Provider" }).selectOption("gitlab");
  await expect(page).toHaveURL(/provider=gitlab/);
  await expect(page.getByTestId("repo-row").filter({ hasText: GH_REPO })).toHaveCount(0);
});

test("the Feed provider filter narrows to a provider's findings", async ({ page }) => {
  await signIn(page, "/feed");
  await page.getByRole("combobox", { name: "Provider" }).selectOption("github");
  await expect(page).toHaveURL(/provider=github/);
  await expect(page.getByTestId("feed-row").filter({ hasText: "GitHub widget dependency" })).toHaveCount(1);
  await expect(page.getByTestId("feed-row").filter({ hasText: "commons-text" })).toHaveCount(0);
});

// SF-1: the add-a-source → Select-repositories step, driven against MOCKED source endpoints (no live GitHub).
// A GitHub owner lists several repositories to choose from; a public repository is a single preselected item.
const GH_CONN = "aaaaaaaa-0000-0000-0000-000000000001";
const PUB_CONN = "aaaaaaaa-0000-0000-0000-000000000002";

function conn(id: string, targetType: string, credentialKind: string) {
  return {
    id,
    provider: "github",
    host: "github.com",
    credentialKind,
    targetType,
    baseUrl: "https://github.com",
    gitlabGroupId: null,
    groupFullPath: null,
    gitlabProjectId: null,
    projectFullPath: null,
    ownerLogin: targetType === "owner" ? "octo" : null,
    repoFullName: targetType === "repo" ? "octo/widget" : null,
    tokenScopes: [],
    tokenExpiresAt: null,
  };
}

function sourceRepo(id: string, fullName: string, visibility: string) {
  return { providerRepoId: id, fullName, defaultBranch: "main", visibility, empty: false, notSelectableReason: null };
}

test("adding a GitHub owner opens the Select-repositories step (mocked, multi-select)", async ({ page }) => {
  await page.route("**/api/v1/sources/github/owner", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify(conn(GH_CONN, "owner", "token")) }),
  );
  await page.route(`**/api/v1/sources/connections/${GH_CONN}/repositories**`, (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        content: [sourceRepo("111", "octo/alpha", "private"), sourceRepo("222", "octo/beta", "public")],
        page: 0,
        size: 50,
        totalElements: 2,
        totalPages: 1,
      }),
    }),
  );
  await page.route(`**/api/v1/sources/connections/${GH_CONN}/select`, (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify([{ id: "r1" }]) }),
  );

  await signIn(page, "/connect");
  await page.getByLabel("Owner login (user or org)").fill("octo");
  await page.getByLabel("Fine-grained PAT (read-only Contents + Metadata)").fill("ghp_fake_not_real");
  await page.getByRole("button", { name: "Add GitHub source" }).click();

  const select = page.getByTestId("select-source");
  await expect(select).toBeVisible();
  await expect(page.getByTestId("source-repo-row")).toHaveCount(2); // owner → several repos to choose from
  await select.getByRole("checkbox", { name: "Select octo/alpha" }).check();
  await select.getByTestId("add-source-repos").click();
  await expect(page.getByTestId("source-repos-added")).toBeVisible();
});

test("adding a public repository preselects its single repository (mocked, one click)", async ({ page }) => {
  await page.route("**/api/v1/sources/public", (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify(conn(PUB_CONN, "repo", "none")) }),
  );
  await page.route(`**/api/v1/sources/connections/${PUB_CONN}/repositories**`, (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        content: [sourceRepo("900001", "octo/widget", "public")],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      }),
    }),
  );
  await page.route(`**/api/v1/sources/connections/${PUB_CONN}/select`, (route) =>
    route.fulfill({ contentType: "application/json", body: JSON.stringify([{ id: "r2" }]) }),
  );

  await signIn(page, "/connect");
  await page.getByLabel("Repository URL (https)").fill("https://github.com/octo/widget");
  await page.getByRole("button", { name: "Add public repository" }).click();

  const select = page.getByTestId("select-source");
  await expect(select).toBeVisible();
  await expect(page.getByTestId("source-repo-row")).toHaveCount(1);
  // Single-repo/public → the one item is preselected, so adding it is one click (no checkbox toggle needed).
  await expect(select.getByRole("checkbox", { name: "Select octo/widget" })).toBeChecked();
  await select.getByTestId("add-source-repos").click();
  await expect(page.getByTestId("source-repos-added")).toBeVisible();
});
