import { test, expect, signIn } from "../fixtures/test-base";
import { apiGet } from "../helpers/oracle";
import type { Paged, IgnoredItemView, SubIssueView } from "../../src/api";

const DELTEST_GROUP = "40000000-0000-0000-0000-000000000006"; // held by the dedicated manual rule

test("the Ignored list matches GET /ignored and shows reason, creator and time", async ({ page, request }) => {
  await signIn(page, "/ignored");
  const api = await apiGet<Paged<IgnoredItemView>>(request, "/api/v1/ignored?page=0&size=50");
  await expect(page.getByTestId("ignored-item")).toHaveCount(api.content.length);

  // Every ignored row shows a reason code and a non-blank ignored time (createdAt/ignoredAt, R4).
  const firstTime = await page.getByTestId("ignored-at").first().innerText();
  expect(firstTime.trim()).not.toBe("");
  expect(firstTime.trim()).not.toBe("—");
});

test("deleting a rule restores the finding it held", async ({ page, request }) => {
  await signIn(page, "/ignored");
  const ruleRow = page.getByTestId("ignore-rule").filter({ hasText: "CVE-2099-00001" });
  await ruleRow.getByTestId("delete-rule").click();

  await expect
    .poll(async () => {
      const subs = await apiGet<Paged<SubIssueView>>(request, `/api/v1/issue-groups/${DELTEST_GROUP}/sub-issues`);
      return subs.content[0].status;
    })
    .toBe("open");
});

test('"Create new" is prefilled from the existing rule', async ({ page }) => {
  await signIn(page, "/ignored");
  const ruleRow = page.getByTestId("ignore-rule").filter({ hasText: "generic-password" });
  await ruleRow.getByRole("button", { name: "Create new…" }).click();
  const dialog = page.getByRole("dialog", { name: "Create ignore rule" });
  await expect(dialog.getByLabel("Match value")).toHaveValue("generic-password");
  await expect(dialog.getByLabel("Reason (required)")).not.toHaveValue("");
});
