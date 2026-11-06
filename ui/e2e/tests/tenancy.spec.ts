import { type Browser, type BrowserContext, type Page } from "@playwright/test";
import { test, expect, signIn, openUserMenu } from "../fixtures/test-base";
import { sqlCount } from "../helpers/oracle";

// M8.10 S11: the multi-tenant journey end to end, through the real UI and API.
//   sign up → a pending login shows "waiting for approval" → the operator approves → the new user signs in to an
//   EMPTY workspace with the first-run checklist and cannot see Default's repositories → the operator disables
//   the user → the user is signed out on their next request. A non-operator never sees the Operator section.
// Two browser contexts (= two people): the new tenant and the operator (the per-run bootstrap admin, who is the
// installation's operator — D-094).

const BASE_URL = process.env.E2E_BASE_URL ?? "http://ui:80";
const PASSWORD = "tenant-e2e-password-1";
const DEFAULT_REPO = "acme/core-keycloak"; // seeded in the Default workspace
const DEFAULT_REPO_ID = "20000000-0000-0000-0000-000000000001";

async function signInAs(page: Page, email: string, password: string): Promise<void> {
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: /continue|sign in/i }).click();
}

async function operatorContext(browser: Browser): Promise<{ ctx: BrowserContext; page: Page }> {
  const ctx = await browser.newContext({ baseURL: BASE_URL });
  const page = await ctx.newPage();
  await signIn(page, "/dashboard");
  return { ctx, page };
}

test("sign up → pending → approved → empty workspace → disabled is signed out", async ({ page, browser }) => {
  test.setTimeout(120_000);
  const email = `tenant-${Date.now()}@e2e.example`;
  const workspace = `E2E Tenant ${Date.now() % 100000}`;

  // 1. Sign up from the homepage's "Create account" link (M8.13: the nav and hero each offer one; take the
  //    first); the confirmation is the neutral, identical text.
  await page.goto("/");
  await page.getByRole("link", { name: "Create account" }).first().click();
  await expect(page).toHaveURL(/\/signup$/);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByLabel("Workspace name").fill(workspace);
  await page.getByRole("button", { name: "Request account" }).click();
  await expect(page.getByTestId("signup-done")).toContainText("your request was received");
  expect(await sqlCount("select count(*) from signup_request where email = $1 and status = 'pending'", [email])).toBe(1);

  // 2. The correct password for a pending sign-up shows "waiting for approval" (not "wrong password").
  await page.getByRole("link", { name: "Back to sign in" }).click();
  await signInAs(page, email, PASSWORD);
  await expect(page.getByTestId("account-pending")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Waiting for approval" })).toBeVisible();

  // 3. The operator approves it from the Operator → Sign-ups page.
  const op = await operatorContext(browser);
  const nav = op.page.getByRole("navigation", { name: "Primary" });
  await expect(nav.getByText("Operator")).toBeVisible();
  await nav.getByRole("link", { name: "Sign-ups" }).click();
  await expect(op.page).toHaveURL(/\/admin\/sign-ups$/);
  await expect(op.page.getByTestId("signup-row").filter({ hasText: email })).toBeVisible();
  await op.page.getByRole("button", { name: `Approve ${email}` }).click();
  await expect(op.page.getByText(`Approved ${email}.`)).toBeVisible();
  expect(await sqlCount("select count(*) from app_user where email = $1 and status = 'active'", [email])).toBe(1);

  // 4. The new user signs in to an EMPTY workspace: the first-run checklist, their workspace name, no Operator
  //    section, and none of Default's repositories (the API answers 404 on a Default repository id).
  await page.getByRole("button", { name: "Back to sign in" }).click();
  await signInAs(page, email, PASSWORD);
  await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
  await page.goto("/dashboard");
  await expect(page.getByTestId("first-run-checklist")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Primary" }).getByText("Operator")).toHaveCount(0);
  await openUserMenu(page);
  await expect(page.getByTestId("user-menu-workspace")).toContainText(workspace);
  await page.keyboard.press("Escape");

  await page.getByRole("link", { name: "Repositories", exact: true }).click();
  await expect(page.getByText("No repositories yet")).toBeVisible();
  await expect(page.getByText(DEFAULT_REPO)).toHaveCount(0);
  const foreign = await page.request.get(`/api/v1/repositories/${DEFAULT_REPO_ID}`);
  expect(foreign.status()).toBe(404);

  // 5. The operator disables the user (with a confirmation step).
  await op.page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Users" }).click();
  await op.page.getByRole("button", { name: `Disable ${email}` }).click();
  await op.page.getByRole("button", { name: "Disable account" }).click();
  await expect(op.page.getByText(`Disabled ${email}.`)).toBeVisible();

  // 6. The user's very next request ends their session → back at the gate; signing in again says "not active".
  await page.getByRole("link", { name: "Feed" }).click();
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await signInAs(page, email, PASSWORD);
  await expect(page.getByRole("alert")).toContainText("not active");

  await op.ctx.close();
});

test("a non-operator never sees the Operator section, and the operator API answers 403", async ({ browser }) => {
  test.setTimeout(90_000);
  const email = `member-${Date.now()}@e2e.example`;

  // Create + approve an account through the API (the UI path is covered above).
  const op = await operatorContext(browser);
  const anon = await browser.newContext({ baseURL: BASE_URL });
  const signup = await anon.request.post("/api/v1/auth/signup", {
    data: { email, password: PASSWORD, workspaceName: "Member Co" },
  });
  expect(signup.status()).toBe(202);
  await anon.close();
  await op.page.goto("/admin/sign-ups");
  await op.page.getByRole("button", { name: `Approve ${email}` }).click();
  await expect(op.page.getByText(`Approved ${email}.`)).toBeVisible();
  await op.ctx.close();

  const ctx = await browser.newContext({ baseURL: BASE_URL });
  const page = await ctx.newPage();
  await page.goto("/dashboard");
  await signInAs(page, email, PASSWORD);
  const nav = page.getByRole("navigation", { name: "Primary" });
  await expect(nav).toBeVisible();
  await expect(nav.getByText("Operator")).toHaveCount(0);
  await expect(nav.getByRole("link", { name: "Sign-ups" })).toHaveCount(0);
  await expect(nav.getByRole("link", { name: "Users" })).toHaveCount(0);

  // The route exists, but the server is the boundary: the page shows the API's 403, never operator data.
  await page.goto("/admin/users");
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByTestId("user-row")).toHaveCount(0);
  const res = await page.request.get("/api/v1/admin/users");
  expect(res.status()).toBe(403);
  await ctx.close();
});
