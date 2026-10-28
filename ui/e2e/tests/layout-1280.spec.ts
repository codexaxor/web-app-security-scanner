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

test("L2 — the Issues-tab location cell is one line and the page does not overflow at 1280", async ({ page }) => {
  await signIn(page, `/repositories/${CORE}?tab=issues`);
  const loc = page.locator(".location-path").first();
  await expect(loc).toBeVisible();
  const box = await loc.boundingBox();
  expect(box, "location has a box").not.toBeNull();
  expect(box!.height, "location is a single line").toBeLessThan(28);
  expect(await noHorizontalPageOverflow(page), "no horizontal page overflow").toBe(true);
});
