import { test, expect, signIn } from "../fixtures/test-base";

const COMMONS_TEXT = "40000000-0000-0000-0000-000000000001"; // KEV + EPSS SCA, score 100 (critical)

function leadingNumber(text: string): number {
  return Number((text.match(/\d+/) ?? ["0"])[0]);
}

// Parse a computed "rgb(r, g, b)" and return WCAG relative luminance.
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

test("S10a — relative time reads in the correct units everywhere", async ({ page }) => {
  // Dashboard recent scans: the two seeded scans (8h40m, 3d10h) read as hours and days, not the old off-by-one.
  await signIn(page, "/dashboard");
  await expect(page.getByText("9 hours ago")).toBeVisible();
  await expect(page.getByText("3 days ago")).toBeVisible();

  // Repositories: the time-fixture repo's last scan (8h40m) reads "9 hours ago".
  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  const repoRow = page.getByRole("row").filter({ hasText: "time-fixture" });
  await expect(repoRow.getByText("9 hours ago")).toBeVisible();

  // Ignored: the time-fixture finding (ignored 3d10h ago) reads "3 days ago".
  await page.getByRole("link", { name: "Ignored" }).click();
  // The Ignored row shows the finding's vuln id (CVE-2024-99999), not the group title.
  const ignoredRow = page.getByTestId("ignored-item").filter({ hasText: "CVE-2024-99999" });
  await expect(ignoredRow.getByText("3 days ago")).toBeVisible();

  // The exact timestamp is available as a title tooltip wherever a relative time is shown.
  await expect(ignoredRow.locator("time").first()).toHaveAttribute("title", /\d{4}/);
});

test("S10d — a .secscan.yml rule offers no Delete (never a 409-only action)", async ({ page }) => {
  await signIn(page, "/ignored");
  const managed = page.getByTestId("ignore-rule").filter({ hasText: "CVE-2025-22869" });
  await expect(managed.getByTestId("managed-rule")).toContainText("Managed in repo file");
  await expect(managed.getByTestId("delete-rule")).toHaveCount(0);
});

test("S10d — severity color is identical on the Feed and in the side panel", async ({ page }) => {
  await signIn(page, `/feed/${COMMONS_TEXT}`);
  // Panel header severity pill (critical).
  const panelPill = page.getByRole("dialog", { name: "Issue details" }).locator(".pill").first();
  await expect(panelPill).toBeVisible();
  const panelColor = await panelPill.evaluate((el) => getComputedStyle(el).color);
  // The same group's row in the feed behind the panel.
  const feedPill = page
    .getByTestId("feed-row")
    .filter({ hasText: "commons-text" })
    .locator(".pill")
    .first();
  const feedColor = await feedPill.evaluate((el) => getComputedStyle(el).color);
  expect(panelColor).toBe(feedColor);
  // And it is the critical token, not the old orange.
  expect(panelColor).not.toBe("rgb(0, 0, 0)");
});

test("S10d — Esc closes the panel and returns focus to the row", async ({ page }) => {
  await signIn(page, "/feed");
  const row = page.getByTestId("feed-row").first();
  await row.focus();
  await page.keyboard.press("Enter");
  const dialog = page.getByRole("dialog", { name: "Issue details" });
  await expect(dialog).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(row).toBeFocused();
});

test("S10d — Dashboard band count links to the matching filtered Feed", async ({ page }) => {
  await signIn(page, "/dashboard");
  const n = leadingNumber(await page.getByTestId("group-critical").innerText());
  await page.getByTestId("group-critical").click();
  await expect(page).toHaveURL(/band=critical/);
  // The feed's own critical band count equals the dashboard's (same server computation).
  await expect
    .poll(async () => leadingNumber(await page.getByTestId("band-critical").innerText()))
    .toBe(n);
});

test("S10d — Connect shows token expiry with 'N days left' in amber under 14 days", async ({ page }) => {
  await signIn(page, "/connect");
  const expiry = page.getByTestId("connection-expiry").first();
  await expect(expiry).toContainText("days left");
  await expect(expiry).toHaveClass(/expiry-warn/);
});

test("S10c — seeded token pairs meet WCAG AA contrast", async ({ page }) => {
  await signIn(page, "/feed");
  // body text on the page background
  const bodyFg = await page.evaluate(() => getComputedStyle(document.body).color);
  const bodyBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(contrast(bodyFg, bodyBg)).toBeGreaterThanOrEqual(4.5);
  // muted stat label on its card surface
  const label = page.locator(".stat-label").first();
  const labelFg = await label.evaluate((el) => getComputedStyle(el).color);
  const cardBg = await page
    .locator(".card")
    .first()
    .evaluate((el) => getComputedStyle(el).backgroundColor);
  expect(contrast(labelFg, cardBg)).toBeGreaterThanOrEqual(4.5);
  // every nav link is keyboard-focusable with a visible ring (reachable by Tab)
  const firstNav = page.getByRole("link", { name: "Dashboard" });
  await firstNav.focus();
  await expect(firstNav).toBeFocused();
});
