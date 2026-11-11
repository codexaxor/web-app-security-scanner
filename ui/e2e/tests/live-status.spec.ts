import { test, expect, signIn } from "../fixtures/test-base";
import { sqlExec } from "../helpers/oracle";

// M8.8 item 5 / SF-7: live scan-status polling WITHOUT a worker. We seed a queued scan by SQL, then drive it to
// running and completed by SQL; the Repositories list polls every 3 s, so the row's status chip updates and the
// completion toast appears with NO reload, and polling then stops.
const CORE = "20000000-0000-0000-0000-000000000001"; // acme/core-keycloak
const CORE_PATH = "acme/core-keycloak";
const WS = "00000000-0000-0000-0000-000000000001";
const SCAN = "3f000000-0000-0000-0000-0000000000aa"; // a dedicated scan id for this test

test.afterEach(async () => {
  await sqlExec("delete from scan where id = $1", [SCAN]); // restore the seed (serial run)
});

test("a scan's status updates live and toasts on completion, then polling stops (no reload)", async ({ page }) => {
  // A fresh queued scan becomes the repo's latest — the row should show it as Queued on load.
  await sqlExec(
    "insert into scan (id, workspace_id, repository_id, trigger, commit_sha, status, started_at, finished_at) "
      + "values ($1, $2, $3, 'manual', null, 'queued', now(), null)",
    [SCAN, WS, CORE],
  );

  // Count the list-poll requests so we can prove polling stops after completion.
  let repoRequests = 0;
  page.on("request", (req) => {
    if (/\/api\/v1\/repositories\?/.test(req.url())) {
      repoRequests++;
    }
  });

  await signIn(page, "/repositories");
  const row = page.getByTestId("repo-row").filter({ hasText: CORE_PATH });
  await expect(row.locator(".chip")).toContainText("Queued");

  // running → the chip updates within one poll, no reload.
  await sqlExec("update scan set status = 'running' where id = $1", [SCAN]);
  await expect(row.locator(".chip")).toContainText("Running");

  // completed → the chip updates AND the completion toast appears (no reload).
  await sqlExec("update scan set status = 'completed', finished_at = now() where id = $1", [SCAN]);
  await expect(row.locator(".chip")).toContainText("Completed");
  await expect(page.getByRole("status").getByText(new RegExp(`Scan of ${CORE_PATH} completed`))).toBeVisible();

  // Polling stops: after completion settles, no further list-poll requests over a window > 2 intervals.
  await page.waitForTimeout(1500); // let the completion invalidation settle
  const before = repoRequests;
  await page.waitForTimeout(8000); // > 2 × 3 s poll interval
  expect(repoRequests).toBe(before);
});
