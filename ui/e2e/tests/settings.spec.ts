import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet } from "../helpers/oracle";
import type { Paged, IssueGroupView, SubIssueView, IgnoredItemView } from "../../src/api";

// R5: toggling EPSS auto-ignore on/off is reflected in the Feed and Ignored views. The seed has one low-EPSS
// (0.5%, not KEV) open finding in web-portal; LOW_EPSS auto-ignores it when the toggle is on. We assert on that
// specific finding (it moves between Focus and Ignored) rather than aggregate totals, which also shift because
// the seed carries an independent LOW_EPSS fixture the toggle-off sweep clears too.
const WEB_PORTAL = "20000000-0000-0000-0000-000000000003";
const LOW_EPSS_GROUP = "40000000-0000-0000-0000-000000000005";

async function lowEpssStatus(request: import("@playwright/test").APIRequestContext) {
  const subs = await apiGet<Paged<SubIssueView>>(request, `/api/v1/issue-groups/${LOW_EPSS_GROUP}/sub-issues`);
  return subs.content[0].status;
}
async function inFocusFeed(request: import("@playwright/test").APIRequestContext) {
  const feed = await apiGet<Paged<IssueGroupView>>(
    request, `/api/v1/issue-groups?view=focus&repositoryId=${WEB_PORTAL}&page=0&size=50`);
  return feed.content.some((g) => g.id === LOW_EPSS_GROUP);
}

test("the EPSS toggle moves the low-EPSS finding between Focus and Ignored", async ({ page, request }) => {
  await signIn(page, "/settings");
  const toggle = page.getByTestId("epss-toggle");
  await expect(toggle).toBeEnabled();

  // Establish a known OFF baseline — the finding is open and in the Focus feed.
  if (await toggle.isChecked()) {
    await toggle.click();
    await expect(toggle).not.toBeChecked();
    await expect(toggle).toBeEnabled();
  }
  await expect.poll(() => lowEpssStatus(request), { timeout: 20000 }).toBe("open");
  expect(await inFocusFeed(request)).toBe(true);

  // ON → the low-EPSS finding is auto-ignored (leaves Focus, enters Ignored as LOW_EPSS).
  await toggle.click();
  await expect(toggle).toBeChecked();
  await expect.poll(() => lowEpssStatus(request), { timeout: 20000 }).toBe("ignored");
  expect(await inFocusFeed(request)).toBe(false);
  const ignored = await apiGet<Paged<IgnoredItemView>>(request, "/api/v1/ignored?page=0&size=200");
  expect(ignored.content.some((i) => i.groupId === LOW_EPSS_GROUP && i.reasonCode === "LOW_EPSS")).toBe(true);

  // OFF → restored to open and back in the Focus feed.
  await toggle.click();
  await expect(toggle).not.toBeChecked();
  await expect.poll(() => lowEpssStatus(request), { timeout: 20000 }).toBe("open");
  expect(await inFocusFeed(request)).toBe(true);
});

test("Workspace settings shows plain-language EPSS text and links to the LOW_EPSS Ignored filter", async ({
  page,
}) => {
  await signIn(page, "/settings");
  await expect(page.getByRole("heading", { name: "Workspace settings" })).toBeVisible();
  await expect(page.getByText(/estimates how likely a vulnerability is to be exploited/i)).toBeVisible();

  await page.getByTestId("low-epss-link").click();
  await expect(page).toHaveURL(/\/ignored\?reason=LOW_EPSS/);
  await expect(page.getByTestId("reason-filter")).toBeVisible();
});
