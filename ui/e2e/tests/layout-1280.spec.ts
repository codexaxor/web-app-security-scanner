import { test, expect, signIn } from "../fixtures/test-base";

// M8.8 L1/L2 at 1280: the Repositories name row and the Issues-tab location cell are single-line (height ≤ one
// line) and the page never overflows horizontally.
const CORE = "20000000-0000-0000-0000-000000000001"; // acme/core-keycloak

test.use({ viewport: { width: 1280, height: 800 } });

async function noHorizontalPageOverflow(page: import("@playwright/test").Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
  );
}

test("L1 — the Repositories name row is one line and the page does not overflow at 1280", async ({ page }) => {
  await signIn(page, "/repositories");
  const nameLine = page.getByTestId("repo-row").first().locator(".repo-line");
  await expect(nameLine).toBeVisible();
  const box = await nameLine.boundingBox();
  expect(box, "name row has a box").not.toBeNull();
  expect(box!.height, "name row is a single line").toBeLessThan(30);
  expect(await noHorizontalPageOverflow(page), "no horizontal page overflow").toBe(true);
});

test("S11.1 — the navy sidebar fills the full page height on a long page", async ({ page }) => {
  await signIn(page, "/feed"); // a long, scrolling page
  const sidebar = page.locator(".sidebar");
  await expect(sidebar).toBeVisible();
  const viewportH = page.viewportSize()!.height;
  // At the top: the sidebar covers the whole viewport height.
  const top = await sidebar.boundingBox();
  expect(top!.height, "sidebar covers viewport at top").toBeGreaterThanOrEqual(viewportH - 1);
  // Scroll to the bottom of a long page: the sidebar's navy box still reaches the viewport bottom.
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await page.waitForTimeout(100);
  const box = await sidebar.boundingBox();
  expect(box, "sidebar has a box after scroll").not.toBeNull();
  expect(box!.y, "sidebar top is at/above the viewport top").toBeLessThanOrEqual(1);
  expect(box!.y + box!.height, "sidebar reaches the viewport bottom").toBeGreaterThanOrEqual(viewportH - 1);
});

test("L2 — the Issues-tab location cell is one line and the page does not overflow at 1280", async ({ page }) => {
  await signIn(page, `/repositories/${CORE}?tab=issues`);
  const loc = page.locator(".location-path").first();
  await expect(loc).toBeVisible();
  const box = await loc.boundingBox();
  expect(box, "location has a box").not.toBeNull();
  expect(box!.height, "location is a single line").toBeLessThan(28);
  expect(await noHorizontalPageOverflow(page), "no horizontal page overflow").toBe(true);
});
