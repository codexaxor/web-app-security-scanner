import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet } from "../helpers/oracle";
import type { Paged, IssueGroupView } from "../../src/api";

// The technical acronyms must never appear as visible text (tooltips/title attrs are fine).
const FORBIDDEN = ["SCA", "SAST", "IaC", "EOL"];

async function forbiddenTextNodes(page: import("@playwright/test").Page): Promise<string[]> {
  return page.evaluate((bad: string[]) => {
    const set = new Set(bad);
    const hits: string[] = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n: Node | null;
    while ((n = walker.nextNode())) {
      const t = (n.textContent ?? "").trim();
      if (set.has(t)) hits.push(t);
    }
    return hits;
  }, FORBIDDEN);
}

test("S10f — no visible text node is a bare type acronym on Feed / Dashboard / Ignored", async ({ page }) => {
  await signIn(page, "/feed");
  await expect(page.getByTestId("feed-row").first()).toBeVisible();
  expect(await forbiddenTextNodes(page), "Feed").toEqual([]);

  await page.getByRole("link", { name: "Dashboard" }).click();
  await expect(page.getByTestId("type-breakdown")).toBeVisible();
  expect(await forbiddenTextNodes(page), "Dashboard").toEqual([]);

  await page.getByRole("link", { name: "Ignored" }).click();
  await expect(page.getByTestId("ignored-item").first()).toBeVisible();
  expect(await forbiddenTextNodes(page), "Ignored").toEqual([]);
});

test("S10f — filtering by the full name 'Vulnerable dependency' returns the same rows as type=sca", async ({
  page,
  request,
}) => {
  await signIn(page, "/feed");
  const apiSca = await apiGet<Paged<IssueGroupView>>(
    request,
    "/api/v1/issue-groups?view=focus&type=sca&page=0&size=50",
  );

  await page.getByLabel("Type").selectOption({ label: "Vulnerable dependency" });
  await expect(page).toHaveURL(/type=sca/);
  await expect(page.getByTestId("feed-row")).toHaveCount(apiSca.content.length);

  // Every visible row is a dependency finding (badge reads the plain "Dependency").
  const typeCells = await page.getByTestId("feed-row").locator("td:first-child").allInnerTexts();
  expect(typeCells.length).toBeGreaterThan(0);
  expect(typeCells.every((c) => c.includes("Dependency"))).toBeTruthy();
});
