import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet } from "../helpers/oracle";
import type { Paged, RepositoryView } from "../../src/api";

// M8.1 (D-076): the repo's scan branch is shown on the Repositories row and the Dashboard recent-scans table,
// and the "Branch to scan" picker lets the user choose a branch (with a re-baseline explanation on a scanned
// repo). The e2e stack has no GitLab, so the branches endpoint and the PATCH are stubbed per-test (the brief's
// "stubbed GitLab" approach); the backend behaviour itself is covered by RepositoryBranchesIT /
// RepositoryScanBranchIT / RebaselineReconcileIT.
const CORE_KEYCLOAK = "acme/core-keycloak";

async function repoRow(page: import("@playwright/test").Page, path: string) {
  return page.getByRole("row").filter({ hasText: path });
}

test("the Repositories row shows the branch the repo scans", async ({ page, request }) => {
  const repos = await apiGet<Paged<RepositoryView>>(request, "/api/v1/repositories?page=0&size=50");
  const core = repos.content.find((r) => r.pathWithNamespace === CORE_KEYCLOAK)!;
  expect(core.effectiveBranch).toBeTruthy();

  await signIn(page, "/repositories");
  const row = await repoRow(page, CORE_KEYCLOAK);
  await expect(row.locator(".branch-label")).toContainText(`· ${core.effectiveBranch}`);
});

test("the Dashboard recent-scans table has a Branch column", async ({ page }) => {
  await signIn(page, "/dashboard");
  await expect(page.getByRole("columnheader", { name: "Branch", exact: true })).toBeVisible();
});

test("the branch picker lists branches and explains re-baseline before saving", async ({ page }) => {
  // Stub the branches endpoint (no GitLab in the e2e stack) and the settings PATCH so the full picker flow runs.
  await page.route(/\/api\/v1\/repositories\/[0-9a-f-]{36}\/branches/, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        content: [
          { name: "main", isDefault: true, isProtected: true, lastCommitAt: "2026-10-01T09:00:00Z" },
          { name: "dev", isDefault: false, isProtected: false, lastCommitAt: "2026-10-20T10:00:00Z" },
        ],
        page: 0,
        size: 100,
        totalElements: 2,
        totalPages: 1,
      }),
    }),
  );
  let patchBody: unknown = null;
  await page.route(/\/api\/v1\/repositories\/[0-9a-f-]{36}$/, async (route) => {
    if (route.request().method() !== "PATCH") return route.fallback();
    patchBody = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
  });

  await signIn(page, "/repositories");
  const row = await repoRow(page, CORE_KEYCLOAK);
  await row.getByRole("button", { name: "Settings", exact: true }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Branch to scan")).toBeVisible();
  // The picker lists the stubbed branches with their chips (the "main" branch option carries a default chip;
  // the first option is the "Default branch (main)" entry).
  await expect(dialog.getByRole("option", { name: "Default branch (main)" })).toBeVisible();
  const devOption = dialog.getByRole("option", { name: "dev", exact: true });
  await expect(devOption).toBeVisible();
  await expect(dialog.getByRole("option", { name: "main default protected" })).toBeVisible();

  await devOption.click();
  // A scanned repo warns about re-baseline before saving.
  await expect(dialog.getByText(/re-baselines its findings/i)).toBeVisible();
  await dialog.screenshot({ path: "./artifacts/screens/settings-branch-picker.png" });

  await dialog.getByRole("button", { name: "Save", exact: true }).click();
  await expect.poll(() => (patchBody as { scanBranch?: string } | null)?.scanBranch).toBe("dev");
});
