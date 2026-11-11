import { test, expect, signIn } from "../fixtures/test-base";

// M8.7 item 9: the Sources page (Connect→Sources) lists provider-tagged connections and offers add-forms for
// GitLab, GitHub and a public repository; the Repositories list shows a provider badge per row. The seeded data
// is GitLab, so provider badges assert data-provider="gitlab"; adding a live GitHub/public source is the
// end-of-train manual walkthrough (D-086).

test("the Sources page is a provider-first wizard and lists provider-tagged connections", async ({ page }) => {
  await signIn(page, "/connect");
  await expect(page.getByRole("heading", { name: "Sources", exact: true })).toBeVisible();

  // Each connection row carries a provider badge; the seeded connection is GitLab.
  const badge = page.getByTestId("connection-row").first().locator(".provider-badge");
  await expect(badge).toHaveAttribute("data-provider", "gitlab");
  await expect(badge).toHaveAttribute("aria-label", "GitLab");

  // The provider-first wizard: provider → (GitLab host) → private/public, one input, a per-path help box.
  const form = page.getByTestId("add-source-form");
  await expect(form.getByRole("button", { name: "GitLab", exact: true })).toBeVisible();
  await expect(form.getByRole("button", { name: "GitHub", exact: true })).toBeVisible();
  await expect(form.getByRole("button", { name: "Private (token)" })).toBeVisible();
  await expect(form.getByRole("button", { name: "Public (no token)" })).toBeVisible();
  await expect(page.getByTestId("help-box")).toBeVisible();

  // GitLab path shows the self-managed/gitlab.com step and a path input; detection shows before submit.
  await expect(form.getByRole("button", { name: "Self-managed" })).toBeVisible();
  await page.getByLabel("Group/project path or URL").fill("acme/platform/core-keycloak");
  await expect(page.getByTestId("detected")).toContainText("core-keycloak");

  // Switching to GitHub shows the GitHub fine-grained token help and the owner/repo input.
  await form.getByRole("button", { name: "GitHub", exact: true }).click();
  await expect(page.getByTestId("help-box")).toContainText("Contents: Read");
  await expect(page.getByLabel("Owner, owner/repo, or URL")).toBeVisible();
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
  const form = page.getByTestId("add-source-form");
  await form.getByRole("button", { name: "GitHub", exact: true }).click();
  await page.getByLabel("Owner, owner/repo, or URL").fill("octo");
  await page.getByLabel("Access token").fill("ghp_fake_not_real");
  await form.getByRole("button", { name: "Add source" }).click();

  // The one toast system shows the success (M8.8 item 6).
  await expect(page.getByRole("status").getByText(/Added GitHub source/)).toBeVisible();

  const select = page.getByTestId("select-source");
  await expect(select).toBeVisible();
  await expect(page.getByTestId("source-repo-row")).toHaveCount(2); // owner → several repos to choose from
  await select.getByRole("checkbox", { name: "Select octo/alpha" }).check();
  await select.getByTestId("add-source-repos").click();
  await expect(page.getByTestId("source-repos-added")).toBeVisible();
});

test("a failed add shows an inline field error, not a bottom banner", async ({ page }) => {
  await page.route("**/api/v1/sources/public", (route) =>
    route.fulfill({
      status: 400,
      contentType: "application/json",
      body: JSON.stringify({ error: "HOST_NOT_ALLOWED", message: "That host is not allowed." }),
    }),
  );
  await signIn(page, "/connect");
  const form = page.getByTestId("add-source-form");
  await form.getByRole("button", { name: "Public (no token)" }).click();
  await page.getByLabel("Repository URL (https)").fill("https://example.com/owner/repo");
  await form.getByRole("button", { name: "Add source" }).click();
  await expect(page.getByTestId("source-error")).toContainText("That host is not allowed.");
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
  const form = page.getByTestId("add-source-form");
  await form.getByRole("button", { name: "Public (no token)" }).click();
  await page.getByLabel("Repository URL (https)").fill("https://github.com/octo/widget");
  await form.getByRole("button", { name: "Add source" }).click();

  const select = page.getByTestId("select-source");
  await expect(select).toBeVisible();
  await expect(page.getByTestId("source-repo-row")).toHaveCount(1);
  // Single-repo/public → the one item is preselected, so adding it is one click (no checkbox toggle needed).
  await expect(select.getByRole("checkbox", { name: "Select octo/widget" })).toBeChecked();
  await select.getByTestId("add-source-repos").click();
  await expect(page.getByTestId("source-repos-added")).toBeVisible();
});
