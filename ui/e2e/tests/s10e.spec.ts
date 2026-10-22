import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet, sqlCount } from "../helpers/oracle";
import type { Paged, IssueGroupView, DashboardSummaryView } from "../../src/api";

const WS = "00000000-0000-0000-0000-000000000001";
const COMMONS_TEXT = "40000000-0000-0000-0000-000000000001";

function luminance(rgb: string): number {
  const m = rgb.match(/\d+(\.\d+)?/g)!.map(Number);
  const [r, g, b] = m.map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(fg: string, bg: string): number {
  const a = luminance(fg);
  const b = luminance(bg);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

// ---- item 1: finding-count semantics ----
test("S10e — Feed count = API openCount = SQL open sub-issues; Σ Focus openCount = Dashboard findings", async ({
  page,
  request,
}) => {
  await signIn(page, "/feed");
  const api = await apiGet<Paged<IssueGroupView>>(request, "/api/v1/issue-groups?view=focus&page=0&size=50");

  // Wait for the page's rows to fully render before reading them.
  await expect(page.getByTestId("feed-row")).toHaveCount(api.content.length);

  // Every visible row's displayed open count (data-open) == API openCount, in order.
  const uiOpen = await page
    .getByTestId("findings-count")
    .evaluateAll((els) => els.map((e) => Number(e.getAttribute("data-open"))));
  expect(uiOpen).toEqual(api.content.map((g) => g.openCount));

  // API openCount == SQL open sub-issues of that group (spot-check the first row).
  const g0 = api.content[0];
  const sqlOpen = await sqlCount(
    `select count(*) from sub_issue where workspace_id = '${WS}' and group_id = $1 and status = 'open'`,
    [g0.id],
  );
  expect(g0.openCount).toBe(sqlOpen);

  // Σ openCount over ALL Focus groups == Dashboard findings total.
  const dash = await apiGet<DashboardSummaryView>(request, "/api/v1/dashboard/summary?includeInactive=false");
  let total = 0;
  let p = 0;
  let pages = 1;
  do {
    const pg = await apiGet<Paged<IssueGroupView>>(
      request,
      `/api/v1/issue-groups?view=focus&page=${p}&size=50`,
    );
    total += pg.content.reduce((s, g) => s + g.openCount, 0);
    pages = pg.totalPages;
    p += 1;
  } while (p < pages);
  expect(total).toBe(dash.totalFindings);
});

test("S10e — the All view shows 'N open · M ignored' for a mixed group", async ({ page, request }) => {
  await signIn(page, "/feed?view=all");
  const api = await apiGet<Paged<IssueGroupView>>(
    request,
    "/api/v1/issue-groups?view=all&page=0&size=200&includeInactive=true",
  );
  const mixed = api.content.find((g) => g.openCount > 0 && g.ignoredCount > 0);
  // The seed may or may not have a mixed group on page 0; assert the label format when one exists.
  if (mixed) {
    const cell = page.getByTestId("findings-count").filter({ hasText: "ignored" }).first();
    await expect(cell).toContainText("open");
    await expect(cell).toContainText("ignored");
  }
});

// ---- item 2: dashboard type bars render with ordered widths ----
test("S10e — Dashboard type bars render (width > 0) and are ordered by count", async ({ page, request }) => {
  await signIn(page, "/dashboard");
  const api = await apiGet<DashboardSummaryView>(request, "/api/v1/dashboard/summary?includeInactive=false");
  const widths: { count: number; w: number }[] = [];
  for (const t of api.byType) {
    const fill = page.getByTestId(`type-${t.type}`).locator(".hbar-fill");
    const w = await fill.evaluate((el) => el.getBoundingClientRect().width);
    expect(w, `type ${t.type} bar width`).toBeGreaterThan(0);
    widths.push({ count: t.count, w });
  }
  const byCount = [...widths].sort((a, b) => b.count - a.count);
  for (let i = 1; i < byCount.length; i++) {
    expect(byCount[i].w).toBeLessThanOrEqual(byCount[i - 1].w + 0.5);
  }
});

// ---- item 4: side panel has no horizontal overflow ----
for (const width of [1280, 1440]) {
  test(`S10e — side panel fits with no horizontal overflow at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await signIn(page, `/feed/${COMMONS_TEXT}`);
    const dialog = page.getByRole("dialog", { name: "Issue details" });
    await expect(dialog).toBeVisible();
    const res = await dialog.evaluate((el) => ({ sw: el.scrollWidth, cw: el.clientWidth }));
    expect(res.sw).toBeLessThanOrEqual(res.cw);
  });
}

// ---- item 6: Sign out button fully inside the viewport ----
test("S10e — the Sign out button is fully inside the viewport at 1280", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await signIn(page, "/dashboard");
  const btn = page.getByRole("button", { name: "Sign out" });
  const box = await btn.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(1280);
});

// ---- item 10: light theme ----
test("S10e — theme control switches theme without reload and keeps storage empty", async ({ page }) => {
  await signIn(page, "/dashboard");
  const html = page.locator("html");
  await page.getByRole("button", { name: "Dark" }).click();
  await expect(html).toHaveAttribute("data-theme", "dark");
  const darkBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);

  await page.getByRole("button", { name: "Light" }).click();
  await expect(html).toHaveAttribute("data-theme", "light");
  const lightBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(darkBg).not.toBe(lightBg);
  expect(luminance(lightBg)).toBeGreaterThan(luminance(darkBg));

  await page.getByRole("button", { name: "System" }).click();
  await expect(html).not.toHaveAttribute("data-theme");

  const storage = await page.evaluate(() => ({ l: localStorage.length, s: sessionStorage.length }));
  expect(storage.l).toBe(0);
  expect(storage.s).toBe(0);
});

test("S10e — System theme follows prefers-color-scheme; both token sets meet AA contrast", async ({ page }) => {
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await signIn(page, "/feed");
    const bodyFg = await page.evaluate(() => getComputedStyle(document.body).color);
    const bodyBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(contrast(bodyFg, bodyBg), `body contrast (${scheme})`).toBeGreaterThanOrEqual(4.5);
    if (scheme === "dark") {
      expect(luminance(bodyBg), "dark bg is dark").toBeLessThan(0.2);
    } else {
      expect(luminance(bodyBg), "light bg is light").toBeGreaterThan(0.6);
    }
    const label = page.locator(".stat-label").first();
    const labelFg = await label.evaluate((el) => getComputedStyle(el).color);
    const cardBg = await page
      .locator(".card")
      .first()
      .evaluate((el) => getComputedStyle(el).backgroundColor);
    expect(contrast(labelFg, cardBg), `muted-on-card contrast (${scheme})`).toBeGreaterThanOrEqual(4.5);
    await page.evaluate(() => {
      window.localStorage?.clear?.();
    });
  }
});
