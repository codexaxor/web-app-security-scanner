import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet } from "../helpers/oracle";
import type { Paged, SubIssueView } from "../../src/api";

// E2E for the M8 /review-pr fixes: SF-1 (panel header refreshes after ignore), SF-3 (401 → sign-in), SF-7 (SCA
// several-fixed-versions line), N-8 (invalid route id → not-found, no request), N-10 (global unignore confirm).

const MULTI_FIX = "40000000-0000-0000-0000-000000000012"; // SF-7: two different fixed versions (open)
const GLOBAL_HELD = "40000000-0000-0000-0000-000000000013"; // N-10: held by a global rule
const ALL_IGNORE = "40000000-0000-0000-0000-000000000014"; // SF-1: single open sub, ignore-group → all-ignored

test("SF-7: an SCA group with several fixed versions shows the 'see each location' fix line", async ({ page }) => {
  await signIn(page, `/feed/${MULTI_FIX}`);
  // M8.6: the fix guidance is the deterministic "How do I fix it?" text (issueFix); several fixed versions →
  // "see each location".
  await expect(page.getByTestId("howtofix")).toContainText("see each location");
});

test("SF-1: ignoring the whole group flips the panel header to 'Ignored' (no band) without a reload", async ({
  page,
  request,
}) => {
  await signIn(page, `/feed/${ALL_IGNORE}`);
  const header = page.locator(".panel-header");
  // M8.6: the header severity control is now a gauge (score + band), not a pill.
  await expect(header.locator(".gauge-band")).toHaveText("High"); // severity 80 before

  await page.getByRole("button", { name: "Ignore group…" }).click();
  const dialog = page.getByRole("dialog", { name: "Ignore finding" });
  await dialog.getByLabel("Reason (required)").fill("accepted — not shipped");
  await dialog.getByRole("button", { name: "Ignore", exact: true }).click();
  await expect(dialog).toBeHidden();

  // The header gauge became the neutral "No severity (ignored)" gauge — no page reload happened.
  await expect(header.getByLabel("No severity (ignored)")).toBeVisible();
  await expect(header.locator(".gauge-band")).toHaveCount(0);

  // Oracle: the group's sub-issues are all ignored (so the group left Focus / has a null score).
  const subs = await apiGet<Paged<SubIssueView>>(request, `/api/v1/issue-groups/${ALL_IGNORE}/sub-issues`);
  expect(subs.content.every((s) => s.status === "ignored")).toBeTruthy();
});

test("N-10: unignoring a GLOBAL rule from the panel requires a confirm", async ({ page, request }) => {
  await signIn(page, `/feed/${GLOBAL_HELD}`);
  const card = page.getByTestId("subissue").first();
  await card.getByTestId("unignore").click();

  // A confirm dialog appears first — the rule is removed only on confirm.
  const confirm = page.getByRole("dialog", { name: "Unignore across every repository?" });
  await expect(confirm).toBeVisible();
  await confirm.getByRole("button", { name: "Cancel" }).click();
  await expect(confirm).toBeHidden();

  // Cancelling left the finding ignored (no mutation).
  const subs = await apiGet<Paged<SubIssueView>>(request, `/api/v1/issue-groups/${GLOBAL_HELD}/sub-issues`);
  expect(subs.content[0].status).toBe("ignored");
  expect(subs.content[0].ruleGlobal).toBe(true);
});

test("N-8: an invalid route id shows a not-found page and fires no API request for it", async ({ page }) => {
  const badCalls: string[] = [];
  page.on("request", (r) => {
    if (r.url().includes("/api/v1/issue-groups/not-a-uuid")) badCalls.push(r.url());
  });
  await signIn(page, "/feed/not-a-uuid");
  await expect(page.getByTestId("not-found")).toBeVisible();
  expect(badCalls, "no request built from the invalid id").toEqual([]);
});

test("SF-3: a 401 on a client-side fetch drops the app back to the sign-in gate", async ({ page }) => {
  await signIn(page, "/feed");
  await expect(page.getByTestId("total-groups")).toBeVisible(); // signed in, feed loaded

  // From now on the feed endpoint rejects with 401 — the next fetch (switching to All) must drop to sign-in.
  await page.route("**/api/v1/issue-groups**", (route) =>
    route.fulfill({ status: 401, contentType: "application/json", body: "{}" }),
  );
  await page.getByRole("button", { name: "All", exact: true }).click();

  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
});
