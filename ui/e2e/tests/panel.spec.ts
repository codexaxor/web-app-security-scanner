import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet } from "../helpers/oracle";
import type { Paged, IssueGroupView, SubIssueView, FeedSummaryView } from "../../src/api";

const COMMONS_TEXT = "40000000-0000-0000-0000-000000000001"; // KEV + EPSS SCA finding
const SECSCANYML = "40000000-0000-0000-0000-000000000004"; // ignored via .secscan.yml
const AWS_SECRET = "40000000-0000-0000-0000-000000000002"; // has a triage_event (history)

async function groupIdByTitle(request: import("@playwright/test").APIRequestContext, title: string) {
  const page = await apiGet<Paged<IssueGroupView>>(
    request, "/api/v1/issue-groups?view=all&includeInactive=true&page=0&size=200");
  const g = page.content.find((x) => x.title === title);
  expect(g, `group "${title}"`).toBeTruthy();
  return g!.id;
}

test("renders CVE link, KEV badge, EPSS + attribution and installed→fixed", async ({ page }) => {
  await signIn(page, `/feed/${COMMONS_TEXT}`);
  const card = page.getByTestId("subissue").first();
  await expect(card.getByTestId("kev-badge")).toBeVisible();
  await expect(card.getByTestId("epss")).toContainText("%");
  await expect(card.getByTestId("epss-attribution")).toContainText("FIRST.org");
  await expect(card.getByText("1.9 → 1.10.0")).toBeVisible();
  const cve = card.getByRole("link", { name: "CVE-2022-42889" });
  await expect(cve).toHaveAttribute("href", "https://nvd.nist.gov/vuln/detail/CVE-2022-42889");
});

test("reason is required and global scopes need a confirm", async ({ page }) => {
  await signIn(page, `/feed/${COMMONS_TEXT}`);
  await page.getByRole("button", { name: "Ignore…" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Ignore finding" });
  const ignore = dialog.getByRole("button", { name: "Ignore", exact: true });
  await expect(ignore).toBeDisabled(); // no reason yet
  await dialog.getByLabel("Reason (required)").fill("accepted risk");
  await expect(ignore).toBeEnabled(); // subissue scope needs no value
  await dialog.getByLabel("Ignore scope").selectOption("cve-global");
  await expect(ignore).toBeDisabled(); // global needs the confirm
  await dialog.getByRole("checkbox").check();
  await expect(ignore).toBeEnabled();
});

test("ignoring a finding (subissue scope) removes its group from Focus", async ({ page, request }) => {
  // The first focus group of core-keycloak has exactly one sub-issue, so ignoring it ignores the group.
  const feed = await apiGet<Paged<IssueGroupView>>(
    request, "/api/v1/issue-groups?view=focus&repositoryId=20000000-0000-0000-0000-000000000001&page=0&size=1");
  const target = feed.content[0].id;
  const before = (await apiGet<FeedSummaryView>(request, "/api/v1/issue-groups/summary?view=focus")).totalGroups;

  await signIn(page, `/feed/${target}`);
  await page.getByRole("button", { name: "Ignore…" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Ignore finding" });
  await dialog.getByLabel("Reason (required)").fill("dev-only, not shipped");
  await dialog.getByRole("button", { name: "Ignore", exact: true }).click();
  await expect(dialog).toBeHidden();

  // The finding is now ignored and the group has left Focus (API oracle).
  await expect
    .poll(async () => (await apiGet<FeedSummaryView>(request, "/api/v1/issue-groups/summary?view=focus")).totalGroups)
    .toBe(before - 1);
  const subs = await apiGet<Paged<SubIssueView>>(request, `/api/v1/issue-groups/${target}/sub-issues`);
  expect(subs.content[0].status).toBe("ignored");
});

test("a .secscan.yml finding shows the edit-in-repo message and no unignore", async ({ page }) => {
  await signIn(page, `/feed/${SECSCANYML}`);
  const card = page.getByTestId("subissue").first();
  await expect(card.getByTestId("secscanyml-msg")).toContainText(".secscan.yml");
  await expect(card.getByTestId("unignore")).toHaveCount(0);
});

test("an auto-ignored finding shows its cause and no unignore button", async ({ page, request }) => {
  const id = await groupIdByTitle(request, "Ignored (LOW_EPSS)");
  await signIn(page, `/feed/${id}`);
  const card = page.getByTestId("subissue").first();
  await expect(card.getByTestId("autocause-msg")).toContainText("LOW_EPSS");
  await expect(card.getByTestId("unignore")).toHaveCount(0);
});

test("unignore removes the holding rule and restores the finding", async ({ page, request }) => {
  const id = await groupIdByTitle(request, "Ignored (IGNORE_RULE)");
  await signIn(page, `/feed/${id}`);
  await page.getByTestId("unignore").first().click();
  await expect
    .poll(async () => {
      const subs = await apiGet<Paged<SubIssueView>>(request, `/api/v1/issue-groups/${id}/sub-issues`);
      return subs.content[0].status;
    })
    .toBe("open");
});

test("history lists a sub-issue's status changes", async ({ page }) => {
  await signIn(page, `/feed/${AWS_SECRET}`);
  const card = page.getByTestId("subissue").first();
  await card.getByRole("button", { name: "History" }).click();
  await expect(card.getByTestId("history")).toContainText("system");
});
