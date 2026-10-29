import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet, sqlCount } from "../helpers/oracle";
import type { FeedSummaryView, Paged, IssueGroupView, CategoryCount } from "../../src/api";

const WS = "00000000-0000-0000-0000-000000000001";
const CORE = "20000000-0000-0000-0000-000000000001"; // core-keycloak, 64 bulk groups

async function gotoFeed(page: import("@playwright/test").Page) {
  await signIn(page);
  await page.getByRole("link", { name: "Feed", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Feed", exact: true })).toBeVisible();
}

function leadingNumber(text: string): number {
  return Number((text.match(/\d+/) ?? ["0"])[0]);
}

test("summary counts match the API and direct SQL", async ({ page, request }) => {
  await gotoFeed(page);

  const api = await apiGet<FeedSummaryView>(request, "/api/v1/issue-groups/summary?view=focus");

  // UI == API
  expect(leadingNumber(await page.getByTestId("total-groups").innerText())).toBe(api.totalGroups);
  expect(leadingNumber(await page.getByTestId("band-critical").innerText())).toBe(api.bands.critical);
  expect(leadingNumber(await page.getByTestId("new-7d").innerText())).toBe(api.newFindings);
  expect(leadingNumber(await page.getByTestId("solved-7d").innerText())).toBe(api.solved);
  expect(leadingNumber(await page.getByTestId("autoignored-7d").innerText())).toBe(api.autoIgnored);

  // API == SQL (open groups, active repos only, critical band)
  const sqlCrit = await sqlCount(
    `select count(*) from issue_group g join repository r on r.id = g.repository_id
       where g.workspace_id = '${WS}' and g.status = 'open' and r.active = true and g.severity_score >= 90`, []);
  expect(api.bands.critical).toBe(sqlCrit);

  // Solved last-7-days == SQL (distinct sub-issues with a to_status=solved event in the window, active repos)
  const sqlSolved = await sqlCount(
    `select count(distinct e.sub_issue_id) from triage_event e
       join sub_issue s on s.id = e.sub_issue_id join repository r on r.id = s.repository_id
       where e.workspace_id = '${WS}' and e.to_status = 'solved' and r.active = true
         and e.at >= now() - interval '7 days'`, []);
  expect(api.solved).toBe(sqlSolved);
});

test("Focus hides ignored groups; All includes them", async ({ page, request }) => {
  await gotoFeed(page);
  const focus = await apiGet<FeedSummaryView>(request, "/api/v1/issue-groups/summary?view=focus");
  const all = await apiGet<FeedSummaryView>(request, "/api/v1/issue-groups/summary?view=all");
  // All has the 7 null-score ignored groups on top of the focus (open) groups.
  expect(all.totalGroups).toBeGreaterThan(focus.totalGroups);

  await page.getByRole("button", { name: "All" }).click();
  await expect(page.getByTestId("total-groups")).toHaveText(String(all.totalGroups));
});

test("server-side pagination for a repo with more than 50 groups", async ({ page, request }) => {
  await gotoFeed(page);
  // Filter to core-keycloak (64 bulk open groups).
  await page.getByLabel("Repository").selectOption(CORE);

  const api = await apiGet<Paged<IssueGroupView>>(
    request, `/api/v1/issue-groups?view=focus&repositoryId=${CORE}&page=0&size=50`);
  expect(api.totalElements).toBe(64);
  expect(api.content.length).toBe(50); // server caps the page, never the client

  // Pager "of N" is EXACTLY the API totalElements (== the SQL count); the range bounds are exact too.
  await expect(page.getByTestId("pager-range")).toHaveText(/^1.50 of 64$/);
  await expect(page.getByTestId("feed-row")).toHaveCount(50);
  await page.getByRole("button", { name: "Next" }).click();
  await expect(page.getByTestId("pager-range")).toHaveText(/^51.64 of 64$/);
  await expect(page.getByTestId("feed-row")).toHaveCount(14);
});

test("default sort is severity desc and filters narrow by type", async ({ page, request }) => {
  await gotoFeed(page);
  const api = await apiGet<Paged<IssueGroupView>>(request, "/api/v1/issue-groups?view=focus&page=0&size=50");
  // The API's first row is the highest severity (NULLS LAST handled server-side).
  const firstScore = api.content[0].severityScore ?? -1;
  const maxScore = Math.max(...api.content.map((g) => g.severityScore ?? -1));
  expect(firstScore).toBe(maxScore);

  await page.getByLabel("Type").selectOption("sast");
  await expect(page.getByTestId("feed-row").first()).toBeVisible();
  const typeCells = await page.getByTestId("feed-row").locator("td:first-child").allInnerTexts();
  expect(typeCells.length).toBeGreaterThan(0);
  expect(typeCells.every((b) => b.includes("Code"))).toBeTruthy(); // SAST badge → plain "Code" (S10f)
});

test("New (<7d) vs To Do status at the 7-day boundary", async ({ page }) => {
  await gotoFeed(page);
  // A fresh bulk group (hours old) is New.
  await page.getByLabel("Repository").selectOption(CORE);
  await expect(page.getByTestId("feed-row").first().getByText("New", { exact: true })).toBeVisible();

  // The inactive repo's group is 30 days old → To Do (shown only with Include inactive).
  await page.getByLabel("Repository").selectOption("");
  await page.getByText("Include inactive").click();
  const row = page.getByRole("row").filter({ hasText: "Inactive repo finding" });
  await expect(row.getByText("To Do", { exact: true })).toBeVisible();
});

// M8.6 item 9 + M8.5 level-2 taxonomy filter: the Feed category chip narrows the feed server-side and is
// URL-addressable. Oracle: the categories endpoint's count for a category == the feed's totalElements when
// filtered by that category (same focus scope, workspace-scoped, both default includeInactive=false).
test("the category filter narrows the feed server-side and is URL-addressable", async ({ page, request }) => {
  await gotoFeed(page);
  const cats = await apiGet<CategoryCount[]>(request, "/api/v1/issue-groups/categories?view=focus");
  expect(cats.length, "seed has at least one level-2 category").toBeGreaterThan(0);
  const cat = cats[0];

  const unfiltered = await apiGet<Paged<IssueGroupView>>(
    request,
    "/api/v1/issue-groups?view=focus&page=0&size=50",
  );
  const filtered = await apiGet<Paged<IssueGroupView>>(
    request,
    `/api/v1/issue-groups?view=focus&category=${encodeURIComponent(cat.category)}&page=0&size=50`,
  );
  // The categories endpoint count is the group total for that category under the same focus scope.
  expect(filtered.totalElements).toBe(cat.count);
  expect(filtered.totalElements).toBeLessThanOrEqual(unfiltered.totalElements);
  // Every group returned for the filter carries that level-2 category.
  expect(filtered.content.every((g) => g.level2Category === cat.category)).toBeTruthy();

  // Selecting the category sets ?category=<value> (URL-addressable) and narrows the visible rows to the
  // server-side result (page capped at 50, as the pagination test above asserts).
  await page.getByLabel("Category").selectOption(cat.category);
  await expect.poll(() => new URL(page.url()).searchParams.get("category")).toBe(cat.category);
  await expect(page.getByTestId("feed-row")).toHaveCount(Math.min(50, filtered.totalElements));
});
