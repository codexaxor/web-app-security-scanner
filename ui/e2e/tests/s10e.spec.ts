import { test, expect, signIn, openUserMenu, forceTheme } from "../fixtures/test-base";
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
  // size=20 matches the UI default page size (S11.3) so the rendered rows equal api.content.
  const api = await apiGet<Paged<IssueGroupView>>(request, "/api/v1/issue-groups?view=focus&page=0&size=20");

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
  await openUserMenu(page); // Sign out lives in the user menu (M8.11)
  const btn = page.getByRole("button", { name: "Sign out" });
  const box = await btn.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(1280);
});

// ---- item 10 / D-099: theme toggle (light default, dark via the user menu, no "system") ----
test("S10e — the theme toggle switches without reload and persists only the theme key", async ({ page }) => {
  await signIn(page, "/dashboard");
  const html = page.locator("html");
  // Light is the default.
  await expect(html).toHaveAttribute("data-theme", "light");

  await openUserMenu(page);
  await page.getByRole("button", { name: "Dark" }).click();
  await expect(html).toHaveAttribute("data-theme", "dark");
  const darkBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);

  await page.getByRole("button", { name: "Light" }).click();
  await expect(html).toHaveAttribute("data-theme", "light");
  const lightBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(darkBg).not.toBe(lightBg);
  expect(luminance(lightBg)).toBeGreaterThan(luminance(darkBg));

  // D-099: the choice is persisted, and the ONLY web-storage key is the theme (never a credential/token).
  const storage = await page.evaluate(() => ({
    keys: Object.keys(localStorage),
    theme: localStorage.getItem("secscan-theme"),
    session: sessionStorage.length,
  }));
  expect(storage.keys).toEqual(["secscan-theme"]);
  expect(storage.theme).toBe("light");
  expect(storage.session).toBe(0);
});

test("S10e — both the light and dark token sets meet AA contrast (explicit theme)", async ({ page }) => {
  for (const scheme of ["light", "dark"] as const) {
    await forceTheme(page, scheme); // D-099: explicit theme, not prefers-color-scheme
    await signIn(page, "/feed");
    await expect(page.locator("html")).toHaveAttribute("data-theme", scheme);
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
  }
});
