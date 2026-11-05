import { test, expect, signIn } from "../fixtures/test-base";

// M8.11 review N-3: between 721 and 1024 px the navy app-bar is sticky at the top, so a sticky table header
// must stick BELOW it (not slide underneath). Scroll the long Feed table and check the header's top edge sits
// at the app-bar's bottom edge and is not covered by it.
test.use({ viewport: { width: 900, height: 700 } });

test("N-3 — at 900px the sticky table header stays below the sticky app-bar", async ({ page }) => {
  await signIn(page, "/feed");
  await expect(page.getByTestId("feed-row").first()).toBeVisible();
  const appbar = page.locator(".appbar");
  await expect(appbar).toBeVisible();

  const th = page.locator(".table thead th").first();
  // Scroll the page so the table header would otherwise pass under the app-bar.
  await th.evaluate((el) => window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY + 400));
  await page.waitForTimeout(100);

  const bar = await appbar.boundingBox();
  const head = await th.boundingBox();
  expect(bar && head, "both have boxes").toBeTruthy();
  // The page really scrolled past the table's natural header position.
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(300);
  // Header top is at the app-bar's bottom (±1px), so the header is fully visible, not under the bar.
  expect(Math.abs(head!.y - (bar!.y + bar!.height)), "header sticks right under the app-bar").toBeLessThanOrEqual(1);
  // And the element at the header's centre is the header itself (not the app-bar covering it).
  const hit = await page.evaluate(
    ([x, y]) => document.elementFromPoint(x, y)?.closest("th") !== null,
    [head!.x + head!.width / 2, head!.y + head!.height / 2],
  );
  expect(hit, "header is not covered").toBe(true);
});
