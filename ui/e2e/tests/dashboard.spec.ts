import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet, sqlCount } from "../helpers/oracle";
import type { DashboardSummaryView } from "../../src/api";

const WS = "00000000-0000-0000-0000-000000000001";

function leadingNumber(text: string): number {
  return Number((text.match(/\d+/) ?? ["0"])[0]);
}

test("dashboard numbers match the API and SQL, and exclude the other workspace", async ({ page, request }) => {
  await signIn(page, "/dashboard");
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();

  const api = await apiGet<DashboardSummaryView>(request, "/api/v1/dashboard/summary?includeInactive=false");

  // Totals UI == API.
  expect(leadingNumber(await page.getByTestId("group-total").innerText())).toBe(api.totalGroups);
  expect(leadingNumber(await page.getByTestId("finding-total").innerText())).toBe(api.totalFindings);
  expect(leadingNumber(await page.getByTestId("group-critical").innerText())).toBe(api.groupBands.critical);
  expect(leadingNumber(await page.getByTestId("finding-critical").innerText())).toBe(api.findingBands.critical);

  // API == SQL (open findings, active repos only, critical band), workspace-scoped.
  const sqlFindingCrit = await sqlCount(
    `select count(*) from sub_issue s join repository r on r.id = s.repository_id
       where s.workspace_id = '${WS}' and s.status = 'open' and r.active = true and s.score >= 90`, []);
  expect(api.findingBands.critical).toBe(sqlFindingCrit);

  // The second workspace is never counted (its open critical finding has score 100).
  const sqlAllWsCrit = await sqlCount(
    `select count(*) from sub_issue s join repository r on r.id = s.repository_id
       where s.status = 'open' and r.active = true and s.score >= 90`, []);
  expect(sqlAllWsCrit).toBeGreaterThan(sqlFindingCrit); // the other workspace has one more
  // ...and the dashboard shows only this workspace's count.
  expect(api.findingBands.critical).toBe(sqlFindingCrit);

  // Scan health flags the partial/failed repos (seed: ib-api-v2 partial, web-portal failed, core-keycloak warnings).
  expect(api.scanHealth.unhealthyRepoCount).toBeGreaterThan(0);
  await expect(page.getByTestId("scan-health-count")).toContainText(String(api.scanHealth.unhealthyRepoCount));

  // Type breakdown + inactive line render.
  await expect(page.getByTestId("type-breakdown")).toBeVisible();
  await expect(page.getByTestId("inactive-line")).toContainText("inactive repo");
});

test("the issue-type breakdown equals the API counts", async ({ page, request }) => {
  await signIn(page, "/dashboard");
  const api = await apiGet<DashboardSummaryView>(request, "/api/v1/dashboard/summary?includeInactive=false");
  for (const t of api.byType) {
    const row = page.getByTestId(`type-${t.type}`);
    await expect(row).toContainText(String(t.count));
  }
});
