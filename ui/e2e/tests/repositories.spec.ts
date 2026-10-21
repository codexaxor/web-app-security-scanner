import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet, sqlCount } from "../helpers/oracle";
import type { Paged, RepositoryView } from "../../src/api";

// R1: repository rollups UI = API = SQL; inactive line + badge; scan enqueues; polling to terminal refreshes;
// warnings drawer.
const CORE_KEYCLOAK = "acme/core-keycloak";

async function repoRow(page: import("@playwright/test").Page, path: string) {
  return page.getByRole("row").filter({ hasText: path });
}

test("rollup numbers match the API and direct SQL", async ({ page, request }) => {
  await signIn(page);
  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Repositories", exact: true })).toBeVisible();

  const repos = await apiGet<Paged<RepositoryView>>(request, "/api/v1/repositories?page=0&size=50");
  const core = repos.content.find((r) => r.pathWithNamespace === CORE_KEYCLOAK)!;
  expect(core).toBeTruthy();

  const row = await repoRow(page, CORE_KEYCLOAK);
  for (const band of ["critical", "high", "medium", "low"] as const) {
    const uiCount = Number(await row.locator(`[data-sev="${band}"]`).first().innerText());
    expect(uiCount, `UI ${band}`).toBe(core.openBySeverity[band]);
    const sql = await sqlCount(
      `select count(*) from issue_group where workspace_id = '00000000-0000-0000-0000-000000000001'
         and repository_id = $1 and status = 'open'
         and severity_score ${band === "critical" ? ">= 90" : band === "high" ? "between 70 and 89"
           : band === "medium" ? "between 40 and 69" : "between 1 and 39"}`,
      [core.id],
    );
    expect(core.openBySeverity[band], `API ${band} = SQL`).toBe(sql);
  }
});

test("shows the inactive line and an Inactive badge, and the scan button is disabled", async ({ page }) => {
  await signIn(page);
  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  await expect(page.getByTestId("inactive-line")).toContainText("inactive repo");

  const legacy = await repoRow(page, "acme/legacy-batch");
  await expect(legacy.getByText("Inactive")).toBeVisible();
  await expect(legacy.getByRole("button", { name: "Scan", exact: true })).toBeDisabled();
});

test("Start Scan enqueues a scan for an active repository", async ({ page }) => {
  // web-portal — chosen so this scan does not change core-keycloak / ib-api-v2 last-scan state used elsewhere.
  const WEB_PORTAL_ID = "20000000-0000-0000-0000-000000000003";
  await signIn(page);
  await page.getByRole("link", { name: "Repositories", exact: true }).click();

  const before = await sqlCount(`select count(*) from scan where repository_id = '${WEB_PORTAL_ID}'`, []);
  const row = await repoRow(page, "acme/web-portal");
  await row.getByRole("button", { name: "Scan", exact: true }).click();
  await expect(row.getByRole("button", { name: /scanning|starting/i })).toBeVisible();
  await expect
    .poll(async () => sqlCount(`select count(*) from scan where repository_id = '${WEB_PORTAL_ID}'`, []))
    .toBe(before + 1);
});

test("polling drives the button back to idle once the scan reaches a terminal status", async ({ page }) => {
  // Intercept the status poll: first running, then completed (the real stack has no worker).
  let calls = 0;
  await page.route(/\/api\/v1\/scans\/[0-9a-f-]{36}$/, async (route) => {
    if (route.request().method() !== "GET") {
      return route.fallback();
    }
    calls += 1;
    const status = calls >= 2 ? "completed" : "running";
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ scan: { id: "x", repositoryId: "x", trigger: "manual", status,
        commitSha: null, startedAt: null, finishedAt: null, createdAt: null } }),
    });
  });

  await signIn(page);
  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  const row = await repoRow(page, "acme/ib-api-v2");
  await row.getByRole("button", { name: "Scan", exact: true }).click();
  await expect(row.getByRole("button", { name: "Scanning…" })).toBeVisible();
  // After the mocked completion the button returns to idle.
  await expect(row.getByRole("button", { name: "Scan", exact: true })).toBeVisible({ timeout: 10000 });
});

test("the warnings drawer lists a scan's warnings", async ({ page }) => {
  await signIn(page);
  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  const row = await repoRow(page, CORE_KEYCLOAK);
  await row.getByRole("button", { name: /warning/ }).click();
  const drawer = page.getByRole("dialog", { name: "Scan warnings" });
  await expect(drawer).toContainText("MISSING_GRADLE_LOCKFILE");
});
